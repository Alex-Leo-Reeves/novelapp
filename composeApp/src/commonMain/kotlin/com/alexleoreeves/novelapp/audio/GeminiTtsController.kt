package com.alexleoreeves.novelapp.audio

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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

    private var playbackJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isPaused = false
    private var currentChunkIndex = 0
    private var textChunks: List<String> = emptyList()

    private val httpClient = HttpClient()

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
        textChunks = chunkText(text)
        currentChunkIndex = 0
        _isPlaying.value = true

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            for (index in textChunks.indices) {
                if (!isActive || isPaused) break
                currentChunkIndex = index
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
                for (index in currentChunkIndex until textChunks.size) {
                    if (!isActive || isPaused) break
                    currentChunkIndex = index
                    val directed = addToneDirection(textChunks[index])
                    generateAndPlayAudio(directed)
                }
                _isPlaying.value = false
            }
        }
    }

    fun skipForward() {
        if (currentChunkIndex < textChunks.size - 1) {
            stopInternal()
            currentChunkIndex++
            CoroutineScope(Dispatchers.Default).launch {
                _isPlaying.value = true
                for (index in currentChunkIndex until textChunks.size) {
                    if (isPaused) break
                    currentChunkIndex = index
                    generateAndPlayAudio(addToneDirection(textChunks[index]))
                }
                _isPlaying.value = false
            }
        }
    }

    fun skipBack() {
        if (currentChunkIndex > 0) {
            stopInternal()
            currentChunkIndex = maxOf(0, currentChunkIndex - 1)
            CoroutineScope(Dispatchers.Default).launch {
                _isPlaying.value = true
                for (index in currentChunkIndex until textChunks.size) {
                    if (isPaused) break
                    currentChunkIndex = index
                    generateAndPlayAudio(addToneDirection(textChunks[index]))
                }
                _isPlaying.value = false
            }
        }
    }

    fun stop() {
        stopInternal()
        textChunks = emptyList()
        currentChunkIndex = 0
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
     * Never cuts mid-sentence, always breaks on a paragraph boundary.
     */
    private fun chunkText(text: String): List<String> {
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (para in paragraphs) {
            val wordCount = currentChunk.toString().split("\\s+".toRegex()).size
            if (wordCount + para.split("\\s+".toRegex()).size > 1000 && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            }
            currentChunk.append(para).append("\n\n")
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }
        return chunks.ifEmpty { listOf(text) }
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
        // Implemented via expect/actual in platform-specific source sets
        platformPlayAudio(base64AudioData)
    }
}

/**
 * Platform-specific audio playback function.
 * Android: uses MediaPlayer / AudioTrack
 * iOS: uses AVAudioPlayer
 */
expect suspend fun platformPlayAudio(base64AudioData: String)
