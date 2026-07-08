package com.alexleoreeves.novelapp.tv.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

data class TtsSettings(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val isPlaying: Boolean = false,
    val currentText: String = "",
    val currentProgress: Float = 0f
)

class TvTtsEngine(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(TtsSettings())
    val settings: StateFlow<TtsSettings> = _settings

    private var isInitialized = false

    suspend fun init(): Boolean {
        if (isInitialized) return true
        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred

        withContext(Dispatchers.Main.immediate) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    isInitialized = true
                    deferred.complete(true)
                } else {
                    deferred.complete(false)
                }
            }
        }
        return deferred.await()
    }

    fun speak(text: String) {
        if (!isInitialized || text.isBlank()) return

        _settings.value = _settings.value.copy(
            isPlaying = true,
            currentText = text,
            currentProgress = 0f
        )

        val engine = tts ?: return
        val utteranceId = "novaread_${UUID.randomUUID()}"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    _settings.value = _settings.value.copy(
                        isPlaying = false,
                        currentProgress = 1f
                    )
                }
            }

            override fun onError(id: String?) {
                if (id == utteranceId) {
                    _settings.value = _settings.value.copy(isPlaying = false)
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    _settings.value = _settings.value.copy(isPlaying = false)
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                if (utteranceId == utteranceId && text.length > 0) {
                    val progress = start.toFloat() / text.length.coerceAtLeast(1)
                    _settings.value = _settings.value.copy(currentProgress = progress.coerceIn(0f, 1f))
                }
            }
        })

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, _settings.value.volume.coerceIn(0f, 1f))
        }

        engine.language = Locale.US
        engine.setSpeechRate(_settings.value.speed.coerceIn(0.1f, 2.0f))
        engine.setPitch(_settings.value.pitch.coerceIn(0.1f, 2.0f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        _settings.value = _settings.value.copy(isPlaying = false, currentProgress = 0f)
        runCatching { tts?.stop() }
        runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
        mediaPlayer = null
    }

    fun pause() {
        runCatching { tts?.stop() }
        _settings.value = _settings.value.copy(isPlaying = false)
    }

    fun resume() {
        val text = _settings.value.currentText
        if (text.isNotBlank()) {
            speak(text)
        }
    }

    fun updateSpeed(speed: Float) {
        _settings.value = _settings.value.copy(speed = speed.coerceIn(0.1f, 2.0f))
    }

    fun updatePitch(pitch: Float) {
        _settings.value = _settings.value.copy(pitch = pitch.coerceIn(0.1f, 2.0f))
    }

    fun release() {
        scope.cancel()
        runCatching { tts?.stop(); tts?.shutdown() }
        runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
        tts = null
        mediaPlayer = null
        isInitialized = false
    }
}
