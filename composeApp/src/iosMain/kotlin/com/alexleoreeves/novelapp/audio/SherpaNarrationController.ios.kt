package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class SherpaNarrationController {
    actual val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    actual val currentChunkIndex: StateFlow<Int> = MutableStateFlow(0)
    actual val chunkBoundaries: StateFlow<List<Int>> = MutableStateFlow(emptyList())
    actual val currentParagraphIndex: StateFlow<Int> = MutableStateFlow(-1)
    actual val currentWordIndex: StateFlow<Int> = MutableStateFlow(-1)
    actual val playbackProgress: StateFlow<Float> = MutableStateFlow(0f)
    actual val isBuffering: StateFlow<Boolean> = MutableStateFlow(false)
    
    actual val voiceSetupStatus: StateFlow<VoiceSetupStatus> = MutableStateFlow(
        VoiceSetupStatus(phase = VoiceSetupPhase.Error, message = "Sherpa-ONNX TTS is currently only supported on Android")
    )
    actual val lastError: StateFlow<String?> = MutableStateFlow("TTS is only supported on Android")
    actual val settings: StateFlow<NarrationSettings> = MutableStateFlow(NarrationSettings())
    actual val sleepTimerMinutes = MutableStateFlow(0)

    actual fun updateSettings(transform: (NarrationSettings) -> NarrationSettings) {}
    actual fun startSleepTimer(minutes: Int, onTimerFinished: () -> Unit) {}
    actual fun cancelSleepTimer() {}
    actual fun playText(text: String, cacheKey: String?, persistAudioCache: Boolean) {}
    actual fun pause() {}
    actual fun resume() {}
    actual fun stop() {}
    actual fun skipForward() {}
    actual fun skipBack() {}
    actual fun seekToProgress(progress: Float) {}
    actual fun close() {}
}
