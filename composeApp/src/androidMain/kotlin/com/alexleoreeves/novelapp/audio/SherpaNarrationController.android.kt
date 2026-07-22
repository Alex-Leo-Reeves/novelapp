package com.alexleoreeves.novelapp.audio

import android.content.Context
import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

actual class SherpaNarrationController actual constructor() {
    
    private val context = AppContextHolder.applicationContext ?: error("App context not initialized")
    private val prefs = context.getSharedPreferences("narration_settings", Context.MODE_PRIVATE)
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
    
    /** Load persisted settings or use defaults */
    private val _settings = MutableStateFlow(loadSavedSettings())
    actual val settings: StateFlow<NarrationSettings> = _settings
    
    actual val sleepTimerMinutes = MutableStateFlow(0)

    /** Load narration settings from SharedPreferences, falling back to defaults */
    private fun loadSavedSettings(): NarrationSettings {
        val narratorVoiceId = prefs.getInt("narrator_voice_id", 0)
        val characterVoiceId = prefs.getInt("character_voice_id", 17)
        val narratorVolume = prefs.getFloat("narrator_volume", 1.0f)
        val ambienceVolume = prefs.getFloat("ambience_volume", 0.18f)
        val ambienceEnabled = prefs.getBoolean("ambience_enabled", false)
        val voiceModeOrdinal = prefs.getInt("voice_mode", VoiceMode.NarratorOnly.ordinal)
        val voiceMode = VoiceMode.entries.getOrElse(voiceModeOrdinal) { VoiceMode.NarratorOnly }
        val backgroundPlayback = prefs.getBoolean("background_playback", false)
        return NarrationSettings(
            narratorVolume = narratorVolume,
            ambienceVolume = ambienceVolume,
            ambienceEnabled = ambienceEnabled,
            voiceMode = voiceMode,
            narratorVoiceId = narratorVoiceId,
            characterVoiceId = characterVoiceId,
            backgroundPlaybackEnabled = backgroundPlayback
        )
    }

    /** Persist settings to SharedPreferences whenever they change */
    actual fun updateSettings(transform: (NarrationSettings) -> NarrationSettings) {
        val newSettings = transform(_settings.value)
        _settings.value = newSettings
        prefs.edit()
            .putInt("narrator_voice_id", newSettings.narratorVoiceId)
            .putInt("character_voice_id", newSettings.characterVoiceId)
            .putFloat("narrator_volume", newSettings.narratorVolume)
            .putFloat("ambience_volume", newSettings.ambienceVolume)
            .putBoolean("ambience_enabled", newSettings.ambienceEnabled)
            .putInt("voice_mode", newSettings.voiceMode.ordinal)
            .putBoolean("background_playback", newSettings.backgroundPlaybackEnabled)
            .apply()
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

    private fun startForegroundServiceIfEnabled() {
        val s = _settings.value
        if (s.backgroundPlaybackEnabled) {
            updateNarrationForegroundService(
                enabled = true,
                title = s.backgroundTitle.ifBlank { "NovelApp narration" },
                subtitle = s.backgroundSubtitle.ifBlank { "Reading in background" }
            )
        }
    }

    private fun stopForegroundService() {
        updateNarrationForegroundService(enabled = false, title = "", subtitle = "")
    }

    actual fun playText(text: String, cacheKey: String?, persistAudioCache: Boolean, isDialogueOnly: Boolean) {
        val paragraphs = text.toNarrationBlocks()
        if (paragraphs.isEmpty()) return
        
        // Start foreground service if background playback is enabled
        startForegroundServiceIfEnabled()
        
        scope.launch {
            _lastError.value = null
            
            if (!modelManager.isModelReady()) {
                _voiceSetupStatus.value = VoiceSetupStatus(
                    phase = VoiceSetupPhase.Downloading,
                    message = "Extracting bundled Sherpa-ONNX model..."
                )
                val success = modelManager.prepareModel { progress ->
                    _voiceSetupStatus.value = VoiceSetupStatus(
                        phase = VoiceSetupPhase.Downloading,
                        downloadedBytes = progress.toLong(),
                        totalBytes = 100,
                        message = "Extracting bundled Sherpa-ONNX model... $progress%"
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
                phase = VoiceSetupPhase.Ready
            )
            
            stutterFreeNarrator.streamText(paragraphs, _settings.value, isDialogueOnly)
        }
    }

    actual fun testVoice(voiceId: Int) {
        scope.launch(Dispatchers.IO) {
            val audioResult = chapterNarrator.generateAudioWavBytes("This is a test of the selected voice.", voiceId)
            if (audioResult != null) {
                platformPlayAudio(audioResult.first)
            }
        }
    }

    actual fun pause() {
        stutterFreeNarrator.pause()
        // Keep foreground service alive while paused (user may resume)
    }

    actual fun resume() {
        stutterFreeNarrator.resume()
        // Re-start foreground service in case Android killed it while paused
        startForegroundServiceIfEnabled()
    }

    actual fun stop() {
        stutterFreeNarrator.stop()
        // Stop foreground service when narration ends
        stopForegroundService()
        _lastError.value = null
    }

    actual fun skipForward() {
        stutterFreeNarrator.seekToProgress((stutterFreeNarrator.playbackProgress.value + 0.05f).coerceIn(0f, 1f))
    }

    actual fun skipBack() {
        stutterFreeNarrator.seekToProgress((stutterFreeNarrator.playbackProgress.value - 0.05f).coerceIn(0f, 1f))
    }

    actual fun seekToProgress(progress: Float) {
        stutterFreeNarrator.seekToProgress(progress)
    }

    actual suspend fun downloadChapterAudio(text: String, chapterName: String): String? {
        val paragraphs = text.toNarrationBlocks()
        if (paragraphs.isEmpty()) return null
        
        if (!modelManager.isModelReady()) {
            val success = modelManager.prepareModel { }
            if (!success) return null
        }
        
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                chapterNarrator.downloadChapterAudio(
                    paragraphs = paragraphs,
                    voiceId = settings.value.narratorVoiceId,
                    chapterName = chapterName,
                    onComplete = { (file, _) ->
                        continuation.resume(file.absolutePath) {}
                    },
                    volumeGain = settings.value.narratorVolume
                )
            }
        }
    }

    actual fun close() {
        stop()
    }
}

/**
 * Must mirror [ReaderScreen.toReaderBlocks] exactly so that paragraphIndex
 * values emitted by the narrator correspond 1:1 with the LazyColumn items.
 */
private fun String.toNarrationBlocks(): List<String> =
    split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { paragraph ->
            if (paragraph.length <= 520) {
                listOf(paragraph)
            } else {
                paragraph.splitNarrationSentenceBlocks(maxChars = 420)
            }
        }

private fun String.splitNarrationSentenceBlocks(maxChars: Int): List<String> {
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
