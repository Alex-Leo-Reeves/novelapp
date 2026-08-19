package com.alexleoreeves.novelapp.tv.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

data class TtsSettings(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val isPlaying: Boolean = false,
    val currentText: String = "",
    val currentProgress: Float = 0f,
    val engineName: String = "Android",
    val modelExtracting: Boolean = false,
    val extractionProgress: Int = 0,
    val voiceSpeakerId: Int = 0
)

/**
 * TV narration engine — Sherpa-ONNX offline neural TTS with Android system TTS fallback.
 *
 * Sherpa is initialized lazily so a JNI load failure (missing native lib, ABI
 * mismatch, OOM) never crashes the app. On any failure we degrade to the
 * system TTS and the user still gets narration.
 */
class TvTtsEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Lazy: class loading of OfflineTts (and its JNI .so) only happens when
    // we actually try to use Sherpa, not in the constructor.  This prevents
    // an UnsatisfiedLinkError from crashing the entire app on devices that
    // can't load the Sherpa native library.
    private val modelManager by lazy { TvSherpaModelManager(context) }
    private val narrator by lazy { TvSherpaChapterNarrator(context, modelManager) }

    @Volatile
    private var sherpaReady = false

    private val _settings = MutableStateFlow(TtsSettings())
    val settings: StateFlow<TtsSettings> = _settings

    private var streamingJob: Job? = null
    private var playbackPaused = false

    suspend fun init(): Boolean {
        if (isInitialized) return true

        // Kick off Sherpa model extraction in the background (first run: 80MB zip).
        // Until it's ready we use the system TTS.
        scope.launch {
            try {
                _settings.value = _settings.value.copy(modelExtracting = true, extractionProgress = 0)
                val ok = modelManager.prepareModel { progress ->
                    _settings.value = _settings.value.copy(extractionProgress = progress)
                }
                sherpaReady = ok
                _settings.value = _settings.value.copy(
                    modelExtracting = false,
                    extractionProgress = 100,
                    engineName = if (ok) "Sherpa" else "Android"
                )
            } catch (t: Throwable) {
                // Sherpa init failed (JNI load, OOM, missing model, etc.)
                // Degrade to system TTS silently.
                android.util.Log.w("TvTtsEngine", "Sherpa init failed; using system TTS", t)
                sherpaReady = false
                _settings.value = _settings.value.copy(
                    modelExtracting = false,
                    extractionProgress = 0,
                    engineName = "Android"
                )
            }
        }

        val deferred = CompletableDeferred<Boolean>()
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
        if (text.isBlank()) return
        stopInternal(clearText = false)

        _settings.value = TtsSettings(
            speed = _settings.value.speed,
            pitch = _settings.value.pitch,
            volume = _settings.value.volume,
            isPlaying = true,
            currentText = text,
            currentProgress = 0f,
            engineName = if (sherpaReady) "Sherpa" else "Android"
        )

        if (sherpaReady) {
            streamingJob = scope.launch {
                try {
                    val blocks = narrator.splitBlocks(text)
                    var playedMs = 0L
                    var totalMs = 0L

                    for (block in blocks) {
                        if (!sherpaReady) break
                        if (!_settings.value.isPlaying) break
                        val audio = narrator.synthesizeToWav(
                            text = block,
                            voiceId = _settings.value.voiceSpeakerId,
                            speed = _settings.value.speed / 1.15f,
                            gain = _settings.value.volume.coerceIn(0.1f, 2.0f)
                        ) ?: continue

                        totalMs += audio.second
                        TvAudioTrackPlayer.playWavBytes(audio.first)
                        playedMs += audio.second
                        _settings.value = _settings.value.copy(
                            currentProgress = (playedMs.toFloat() / totalMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        )
                    }
                    if (_settings.value.isPlaying) {
                        _settings.value = _settings.value.copy(isPlaying = false, currentProgress = 1f)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("TvTtsEngine", "Sherpa playback failed; falling back to system TTS", t)
                    // Fall back to system TTS for this utterance
                    sherpaReady = false
                    _settings.value = _settings.value.copy(engineName = "Android")
                    withContext(Dispatchers.Main) {
                        speakWithSystemTts(text)
                    }
                }
            }
        } else {
            speakWithSystemTts(text)
        }
    }

    private fun speakWithSystemTts(text: String) {
        if (!isInitialized) return
        val engine = tts ?: return
        val utteranceId = "novaread_${UUID.randomUUID()}"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    _settings.value = _settings.value.copy(isPlaying = false, currentProgress = 1f)
                }
            }
            override fun onError(id: String?) {
                if (id == utteranceId) _settings.value = _settings.value.copy(isPlaying = false)
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) _settings.value = _settings.value.copy(isPlaying = false)
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

    fun stop() = stopInternal(clearText = true)

    private fun stopInternal(clearText: Boolean) {
        streamingJob?.cancel()
        streamingJob = null
        playbackPaused = false
        TvAudioTrackPlayer.stop()
        runCatching { tts?.stop() }
        _settings.value = _settings.value.copy(
            isPlaying = false,
            currentProgress = 0f,
            currentText = if (clearText) "" else _settings.value.currentText
        )
    }

    fun pause() {
        if (_settings.value.engineName == "Sherpa") {
            playbackPaused = true
            runCatching { TvAudioTrackPlayer.pause() }
            _settings.value = _settings.value.copy(isPlaying = false)
        } else {
            runCatching { tts?.stop() }
            _settings.value = _settings.value.copy(isPlaying = false)
        }
    }

    fun resume() {
        if (_settings.value.engineName == "Sherpa") {
            if (streamingJob?.isActive == true) {
                playbackPaused = false
                runCatching { TvAudioTrackPlayer.resume() }
                _settings.value = _settings.value.copy(isPlaying = true)
            } else {
                val text = _settings.value.currentText
                if (text.isNotBlank()) speak(text)
            }
        } else {
            val text = _settings.value.currentText
            if (text.isNotBlank()) speak(text)
        }
    }

    fun updateSpeed(speed: Float) {
        _settings.value = _settings.value.copy(speed = speed.coerceIn(0.5f, 2.0f))
    }

    fun updatePitch(pitch: Float) {
        _settings.value = _settings.value.copy(pitch = pitch.coerceIn(0.5f, 2.0f))
    }

    fun updateVolume(vol: Float) {
        _settings.value = _settings.value.copy(volume = vol.coerceIn(0.0f, 2.0f))
    }

    fun updateVoiceSpeakerId(id: Int) {
        _settings.value = _settings.value.copy(voiceSpeakerId = id.coerceIn(0, 108))
    }

    fun release() {
        scope.cancel()
        runCatching { TvAudioTrackPlayer.stop() }
        _settings.value = _settings.value.copy(isPlaying = false)
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        isInitialized = false
    }
}
