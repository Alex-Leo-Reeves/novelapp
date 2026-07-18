package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NarrationSettings(
    val narratorVolume: Float = 1f,
    val ambienceVolume: Float = 0.18f,
    val ambienceEnabled: Boolean = false,
    val voiceMode: VoiceMode = VoiceMode.NarratorOnly,
    val backgroundPlaybackEnabled: Boolean = false,
    val backgroundTitle: String = "NovelApp narration",
    val backgroundSubtitle: String = "Reading in background"
)

enum class VoiceMode {
    Dynamic,
    NarratorOnly
}

enum class VoiceSetupPhase {
    Idle,
    Checking,
    Downloading,
    Installing,
    Synthesizing,
    Ready,
    Fallback,
    Error
}

data class VoiceSetupStatus(
    val phase: VoiceSetupPhase = VoiceSetupPhase.Idle,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val message: String? = null
) {
    val progressFraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val shouldShow: Boolean
        get() = phase == VoiceSetupPhase.Downloading ||
            phase == VoiceSetupPhase.Installing ||
            phase == VoiceSetupPhase.Synthesizing ||
            phase == VoiceSetupPhase.Fallback ||
            phase == VoiceSetupPhase.Error

    val userMessage: String
        get() = message ?: when (phase) {
            VoiceSetupPhase.Idle -> ""
            VoiceSetupPhase.Checking -> "Checking voice setup."
            VoiceSetupPhase.Downloading -> "Preparing voice model."
            VoiceSetupPhase.Installing -> "Installing voice model."
            VoiceSetupPhase.Synthesizing -> "Preparing voice."
            VoiceSetupPhase.Ready -> "Voice is ready."
            VoiceSetupPhase.Fallback -> "Voice is unavailable."
            VoiceSetupPhase.Error -> "Voice setup failed."
        }
}

expect class SherpaNarrationController() {
    val isPlaying: StateFlow<Boolean>
    val currentChunkIndex: StateFlow<Int>
    val chunkBoundaries: StateFlow<List<Int>>
    val currentParagraphIndex: StateFlow<Int>
    val currentWordIndex: StateFlow<Int>
    val playbackProgress: StateFlow<Float>
    val isBuffering: StateFlow<Boolean>
    val voiceSetupStatus: StateFlow<VoiceSetupStatus>
    val lastError: StateFlow<String?>
    val settings: StateFlow<NarrationSettings>
    val sleepTimerMinutes: MutableStateFlow<Int>

    fun updateSettings(transform: (NarrationSettings) -> NarrationSettings)
    fun startSleepTimer(minutes: Int, onTimerFinished: () -> Unit)
    fun cancelSleepTimer()
    fun playText(text: String, cacheKey: String? = null, persistAudioCache: Boolean = false)
    fun pause()
    fun resume()
    fun stop()
    fun skipForward()
    fun skipBack()
    fun seekToProgress(progress: Float)
    fun close()
}
