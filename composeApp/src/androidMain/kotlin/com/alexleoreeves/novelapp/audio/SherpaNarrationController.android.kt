package com.alexleoreeves.novelapp.audio

import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

actual class SherpaNarrationController {
    
    private val context = AppContextHolder.applicationContext ?: error("App context not initialized")
    private val modelManager = SherpaModelManager(context)
    private val chapterNarrator = SherpaChapterNarrator(context, modelManager)
    private val stutterFreeNarrator = SherpaStutterFreeNarrator(context, chapterNarrator)
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var sleepTimerJob: Job? = null

    actual val isPlaying: StateFlow<Boolean> = stutterFreeNarrator.isPlaying
    
    private val _currentChunkIndex = MutableStateFlow(0)
    actual val currentChunkIndex: StateFlow<Int> = _currentChunkIndex
    
    private val _chunkBoundaries = MutableStateFlow(emptyList<Int>())
    actual val chunkBoundaries: StateFlow<List<Int>> = _chunkBoundaries
    
    actual val currentParagraphIndex: StateFlow<Int> = stutterFreeNarrator.currentParagraphIndex
    actual val currentWordIndex: StateFlow<Int> = stutterFreeNarrator.currentWordIndex
    actual val playbackProgress: StateFlow<Float> = stutterFreeNarrator.playbackProgress
    actual val isBuffering: StateFlow<Boolean> = stutterFreeNarrator.isBuffering
    
    private val _voiceSetupStatus = MutableStateFlow(VoiceSetupStatus())
    actual val voiceSetupStatus: StateFlow<VoiceSetupStatus> = _voiceSetupStatus
    
    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: StateFlow<String?> = _lastError
    
    private val _settings = MutableStateFlow(NarrationSettings())
    actual val settings: StateFlow<NarrationSettings> = _settings
    
    actual val sleepTimerMinutes = MutableStateFlow(0)

    actual fun updateSettings(transform: (NarrationSettings) -> NarrationSettings) {
        _settings.value = transform(_settings.value)
    }

    actual fun startSleepTimer(minutes: Int, onTimerFinished: () -> Unit) {
        sleepTimerJob?.cancel()
        sleepTimerMinutes.value = minutes
        if (minutes <= 0) return
        sleepTimerJob = scope.launch {
            delay(minutes * 60 * 1000L)
            stop()
            sleepTimerMinutes.value = 0
            onTimerFinished()
        }
    }

    actual fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerMinutes.value = 0
    }

    actual fun playText(text: String, cacheKey: String?, persistAudioCache: Boolean) {
        val paragraphs = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return
        
        scope.launch {
            _lastError.value = null
            
            if (!modelManager.isModelReady()) {
                _voiceSetupStatus.value = VoiceSetupStatus(
                    phase = VoiceSetupPhase.Downloading,
                    message = "Downloading Sherpa-ONNX model..."
                )
                val success = modelManager.prepareModel { progress ->
                    _voiceSetupStatus.value = VoiceSetupStatus(
                        phase = VoiceSetupPhase.Downloading,
                        downloadedBytes = progress.toLong(),
                        totalBytes = 100,
                        message = "Downloading Sherpa-ONNX model... $progress%"
                    )
                }
                if (!success) {
                    _voiceSetupStatus.value = VoiceSetupStatus(
                        phase = VoiceSetupPhase.Error,
                        message = "Failed to download model"
                    )
                    _lastError.value = "Failed to download TTS model"
                    return@launch
                }
            }
            
            _voiceSetupStatus.value = VoiceSetupStatus(
                phase = VoiceSetupPhase.Synthesizing,
                message = "Synthesizing audio offline..."
            )
            
            // voiceId mapping:
            // 12: Deep Fantasy, 4: Classic, 22: Ancient, 0: Youth Male, 1: Youth Female, 51: Wise Elder
            val voiceId = when (_settings.value.voiceMode) {
                VoiceMode.NarratorOnly -> 12
                VoiceMode.Dynamic -> 4 
            }
            
            chapterNarrator.downloadChapterAudio(paragraphs, voiceId, cacheKey ?: "temp_chapter") { result ->
                _voiceSetupStatus.value = VoiceSetupStatus(phase = VoiceSetupPhase.Ready)
                stutterFreeNarrator.playAudioFileWithTimings(result.first, result.second)
            }
        }
    }

    actual fun pause() {
        stutterFreeNarrator.pause()
    }

    actual fun resume() {
        stutterFreeNarrator.resume()
    }

    actual fun stop() {
        stutterFreeNarrator.stop()
    }

    actual fun skipForward() {
        // Skip logic (e.g. advance 15 seconds or next paragraph)
        stutterFreeNarrator.seekToProgress((stutterFreeNarrator.playbackProgress.value + 0.05f).coerceIn(0f, 1f))
    }

    actual fun skipBack() {
        stutterFreeNarrator.seekToProgress((stutterFreeNarrator.playbackProgress.value - 0.05f).coerceIn(0f, 1f))
    }

    actual fun seekToProgress(progress: Float) {
        stutterFreeNarrator.seekToProgress(progress)
    }

    actual fun close() {
        stop()
    }
}
