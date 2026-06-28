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
 * Controller for Gemini TTS audio narration.
 *
 * Architecture:
 *  1. Splits input text into paragraph/sentence chunks.
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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

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
        _lastError.value = null
        isPaused = false
        if (textChunks.isEmpty() || textChunks.first().isBlank()) {
            _lastError.value = "There is no readable text in this chapter."
            return
        }
        _isPlaying.value = true

        val job = CoroutineScope(Dispatchers.Default).launch { playFromCursor(0) }
        playbackJob = job
        job.join()
    }

    fun pause() {
        isPaused = true
        playbackJob?.cancel()
        _isPlaying.value = false
    }

    fun resume() {
        if (textChunks.isNotEmpty()) {
            isPaused = false
            playbackJob?.cancel()
            _isPlaying.value = true
            playbackJob = CoroutineScope(Dispatchers.Default).launch { playFromCursor(_chunkCursor) }
        }
    }

    fun skipForward() {
        if (_chunkCursor < textChunks.size - 1) {
            stopInternal()
            _chunkCursor++
            _currentChunkFlow.value = _chunkCursor
            _isPlaying.value = true
            playbackJob = CoroutineScope(Dispatchers.Default).launch { playFromCursor(_chunkCursor) }
        }
    }

    fun skipBack() {
        if (_chunkCursor > 0) {
            stopInternal()
            _chunkCursor = maxOf(0, _chunkCursor - 1)
            _currentChunkFlow.value = _chunkCursor
            _isPlaying.value = true
            playbackJob = CoroutineScope(Dispatchers.Default).launch { playFromCursor(_chunkCursor) }
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
     * Split text into display-sized blocks.
     * Returns a Pair of (chunks, chunkBoundaries) where boundaries[i] is the
     * index of the display block that belongs to chunk i.
     */
    private fun chunkTextWithBoundaries(text: String): Pair<List<String>, List<Int>> {
        val blocks = text.toReadableTtsBlocks()
        return if (blocks.isEmpty()) {
            Pair(emptyList(), emptyList())
        } else {
            Pair(blocks, blocks.indices.toList())
        }
    }

    private suspend fun playFromCursor(startIndex: Int) {
        try {
            for (index in startIndex until textChunks.size) {
                if (!currentCoroutineContext().isActive || isPaused) break
                _chunkCursor = index
                _currentChunkFlow.value = index
                val directedText = addToneDirection(textChunks[index])
                generateAndPlayAudio(directedText)
            }
        } finally {
            if (!isPaused) {
                _isPlaying.value = false
            }
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
            if (apiKey.isBlank()) {
                _lastError.value = "Gemini API key is missing."
                return
            }
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
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
            ) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }.bodyAsText()

            // Parse audio data and hand to platform audio player
            val json = Json.parseToJsonElement(response).jsonObject
            val apiError = json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            if (!apiError.isNullOrBlank()) {
                _lastError.value = apiError
                return
            }
            val parts = json["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray
            val audioData = parts?.firstNotNullOfOrNull { part ->
                val obj = part.jsonObject
                obj["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                    ?: obj["inline_data"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
            }

            if (audioData != null) {
                _lastError.value = null
                playAudioBytes(audioData)
            } else {
                _lastError.value = "Gemini returned no audio for this chunk."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _lastError.value = e.message ?: "Audio generation failed."
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

private fun String.toReadableTtsBlocks(): List<String> =
    split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { paragraph ->
            if (paragraph.length <= 520) {
                listOf(paragraph)
            } else {
                paragraph.splitIntoSentenceBlocks(maxChars = 420)
            }
        }

private fun String.splitIntoSentenceBlocks(maxChars: Int): List<String> {
    val sentences = split(Regex("""(?<=[.!?。！？…])\s+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (sentences.isEmpty()) return chunked(maxChars)

    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    for (sentence in sentences) {
        if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
            blocks.add(current.toString().trim())
            current.clear()
        }
        if (sentence.length > maxChars) {
            if (current.isNotEmpty()) {
                blocks.add(current.toString().trim())
                current.clear()
            }
            blocks.addAll(sentence.chunked(maxChars))
        } else {
            current.append(sentence).append(' ')
        }
    }
    if (current.isNotEmpty()) blocks.add(current.toString().trim())
    return blocks
}

/**
 * Platform-specific audio playback function.
 * Android: uses MediaPlayer / AudioTrack
 * iOS: uses AVAudioPlayer
 */
expect suspend fun platformPlayAudio(audioBytes: ByteArray)
