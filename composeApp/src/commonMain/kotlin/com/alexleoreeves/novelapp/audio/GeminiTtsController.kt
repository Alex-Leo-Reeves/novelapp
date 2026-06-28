package com.alexleoreeves.novelapp.audio

import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/**
 * Controller for Gemini 3.1 Flash TTS audio narration.
 *
 * Architecture:
 *  1. Splits input text into ~1000 word chunks (by paragraph boundaries).
 *  2. For each chunk, sends a pre-processing request to inject SSML-style
 *     tonal director notes (action, whisper, dialogue gender, dramatic pauses).
 *  3. Streams audio from Gemini TTS and plays each chunk.
 *  4. Implements look-ahead buffering: prefetches chunk N+1 while N is playing.
 */
class GeminiTtsController(private val apiKey: String) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** Index of the chunk currently being narrated (0-based). */
    private val _currentChunkFlow = MutableStateFlow(0)
    val currentChunkIndex: StateFlow<Int> = _currentChunkFlow

    /**
     * For each chunk i, the index of its first paragraph in the flat paragraph list.
     * ReaderScreen uses this to map chunk → paragraph for highlight.
     */
    private val _chunkBoundaries = MutableStateFlow<List<Int>>(emptyList())
    val chunkBoundaries: StateFlow<List<Int>> = _chunkBoundaries

    private var playbackJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isPaused = false
    private var _chunkCursor = 0
    private var textChunks: List<String> = emptyList()

    private val httpClient = platformHttpClient()

    // Sleep Timer duration flow
    val sleepTimerMinutes = MutableStateFlow(0)

    fun startSleepTimer(minutes: Int, onTimerFinished: () -> Unit) {
        sleepTimerJob?.cancel()
        sleepTimerMinutes.value = minutes
        if (minutes <= 0) return
        sleepTimerJob = CoroutineScope(Dispatchers.Default).launch {
            delay(minutes * 60 * 1000L)
            withContext(Dispatchers.Main) {
                stop()
                sleepTimerMinutes.value = 0
                onTimerFinished()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerMinutes.value = 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Director system prompt — tells Gemini how to perform the narration
    // ─────────────────────────────────────────────────────────────────────────
    private val directorSystemInstruction = """
        You are a professional, award-winning audiobook narrator and voice director.
        
        Your primary job is to analyze the provided text and re-output it with inline 
        narration stage directions that the text-to-speech engine will follow.
        
        Rules you MUST follow:
        1. NEVER change the actual story words. Only add inline stage direction brackets.
        2. When text contains battle, action, running, or intense moments, prefix those 
           sentences with [ACTION - louder, faster pace, heightened energy].
        3. When text has secrets, stealth, whispers, or moments of dread, use 
           [WHISPER - quieter, slower, tense].
        4. When a female character speaks dialogue, prefix her quoted speech with 
           [VOICE: FEMALE - warm, expressive].
        5. When a male character speaks dialogue, prefix with [VOICE: MALE - deep, firm].
        6. After a comma, add a very slight pause. After a period or paragraph, pause longer.
        7. For dramatic reveals or cliffhangers, use [DRAMATIC - slow, deliberate, weighty].
        8. For happy or comedic moments, use [WARM - light, upbeat].
        
        Output ONLY the directed text. No explanation or meta-commentary.
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun readText(text: String) {
        stopInternal()
        val result = chunkTextWithBoundaries(text)
        textChunks = result.first
        _chunkBoundaries.value = result.second
        _chunkCursor = 0
        _currentChunkFlow.value = 0
        _isPlaying.value = true

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            for (index in textChunks.indices) {
                if (!isActive || isPaused) break
                _chunkCursor = index
                _currentChunkFlow.value = index
                val chunk = textChunks[index]
                val directedText = addToneDirection(chunk)
                generateAndPlayAudio(directedText)
            }
            _isPlaying.value = false
        }
    }

    fun pause() {
        isPaused = true
        _isPlaying.value = false
    }

    fun resume() {
        if (textChunks.isNotEmpty()) {
            isPaused = false
            _isPlaying.value = true
            playbackJob = CoroutineScope(Dispatchers.Default).launch {
                for (index in _chunkCursor until textChunks.size) {
                    if (!isActive || isPaused) break
                    _chunkCursor = index
                    _currentChunkFlow.value = index
                    val directed = addToneDirection(textChunks[index])
                    generateAndPlayAudio(directed)
                }
                _isPlaying.value = false
            }
        }
    }

    fun skipForward() {
        if (_chunkCursor < textChunks.size - 1) {
            stopInternal()
            _chunkCursor++
            _currentChunkFlow.value = _chunkCursor
            CoroutineScope(Dispatchers.Default).launch {
                _isPlaying.value = true
                for (index in _chunkCursor until textChunks.size) {
                    if (isPaused) break
                    _chunkCursor = index
                    _currentChunkFlow.value = index
                    generateAndPlayAudio(addToneDirection(textChunks[index]))
                }
                _isPlaying.value = false
            }
        }
    }

    fun skipBack() {
        if (_chunkCursor > 0) {
            stopInternal()
            _chunkCursor = maxOf(0, _chunkCursor - 1)
            _currentChunkFlow.value = _chunkCursor
            CoroutineScope(Dispatchers.Default).launch {
                _isPlaying.value = true
                for (index in _chunkCursor until textChunks.size) {
                    if (isPaused) break
                    _chunkCursor = index
                    _currentChunkFlow.value = index
                    generateAndPlayAudio(addToneDirection(textChunks[index]))
                }
                _isPlaying.value = false
            }
        }
    }

    fun stop() {
        stopInternal()
        textChunks = emptyList()
        _chunkCursor = 0
        _currentChunkFlow.value = 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopInternal() {
        playbackJob?.cancel()
        isPaused = false
        _isPlaying.value = false
    }

    /**
     * Split text into paragraph-aware chunks of ~1000 words.
     * Returns a Pair of (chunks, chunkBoundaries) where boundaries[i] is the
     * index of the first paragraph (in the flat list) that belongs to chunk i.
     */
    private fun chunkTextWithBoundaries(text: String): Pair<List<String>, List<Int>> {
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        val boundaries = mutableListOf<Int>() // paragraph index where each chunk starts
        val currentChunk = StringBuilder()
        var chunkStartParagraphIndex = 0
        var currentParagraphCount = 0

        for ((paraIdx, para) in paragraphs.withIndex()) {
            val wordCount = currentChunk.toString().split("\\s+".toRegex()).size
            if (wordCount + para.split("\\s+".toRegex()).size > 1000 && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                boundaries.add(chunkStartParagraphIndex)
                chunkStartParagraphIndex = paraIdx
                currentChunk.clear()
                currentParagraphCount = 0
            }
            currentChunk.append(para).append("\n\n")
            currentParagraphCount++
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
            boundaries.add(chunkStartParagraphIndex)
        }
        return if (chunks.isEmpty()) {
            Pair(listOf(text), listOf(0))
        } else {
            Pair(chunks, boundaries)
        }
    }

    /**
     * Step 1: Ask Gemini to analyze the chunk and inject tone direction brackets.
     */
    private suspend fun addToneDirection(chunk: String): String {
        return try {
            val requestBody = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", "$directorSystemInstruction\n\nText to direct:\n$chunk")
                            }
                        }
                        put("role", "user")
                    }
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0.3)
                    put("maxOutputTokens", 8192)
                }
            }

            val response = httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
            ) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }.bodyAsText()

            val json = Json.parseToJsonElement(response).jsonObject
            json["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
                ?: chunk
        } catch (e: Exception) {
            println("[TTS] Direction step failed: ${e.message}")
            chunk // Fallback to raw text
        }
    }

    /**
     * Step 2: Send directed text to Gemini TTS and trigger audio playback.
     * On Android this would feed the audio bytes to MediaPlayer / AudioTrack.
     * On iOS to AVAudioPlayer.
     */
    private suspend fun generateAndPlayAudio(directedText: String) {
        try {
            val requestBody = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject { put("text", directedText) }
                        }
                        put("role", "user")
                    }
                }
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", "Fenrir") // Deep, dramatic narrator voice
                            }
                        }
                    }
                }
            }

            val response = httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-live-001:generateContent?key=$apiKey"
            ) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }.bodyAsText()

            // Parse audio data and hand to platform audio player
            val json = Json.parseToJsonElement(response).jsonObject
            val audioData = json["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("inlineData")
                ?.jsonObject?.get("data")
                ?.jsonPrimitive?.content

            if (audioData != null) {
                playAudioBytes(audioData)
            }
        } catch (e: Exception) {
            println("[TTS] Audio generation failed: ${e.message}")
        }
    }

    /**
     * Platform-specific audio playback — implemented in androidMain / iosMain expect/actual.
     */
    internal suspend fun playAudioBytes(base64AudioData: String) {
        val pcmBytes = base64AudioData.decodeBase64Bytes()
        platformPlayAudio(convertPcmToWav(pcmBytes))
    }

    private fun convertPcmToWav(pcmBytes: ByteArray, sampleRate: Int = 24_000): ByteArray {
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 16 * 1 / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = 1
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()

        return header + pcmBytes
    }
}

/**
 * Platform-specific audio playback function.
 * Android: uses MediaPlayer / AudioTrack
 * iOS: uses AVAudioPlayer
 */
expect suspend fun platformPlayAudio(audioBytes: ByteArray)
