package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlin.math.max
import kotlin.math.roundToInt

private const val MACRO_WORD_TARGET = 200
private const val NEXT_MACRO_PRELOAD_AT_WORD = 160

class KokoroNarrationController(
    initialSettings: KokoroNarrationSettings = KokoroNarrationSettings()
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentChunkFlow = MutableStateFlow(0)
    val currentChunkIndex: StateFlow<Int> = _currentChunkFlow

    private val _chunkBoundaries = MutableStateFlow<List<Int>>(emptyList())
    val chunkBoundaries: StateFlow<List<Int>> = _chunkBoundaries

    private val _currentParagraphIndex = MutableStateFlow(-1)
    val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex

    private val _currentWordIndex = MutableStateFlow(-1)
    val currentWordIndex: StateFlow<Int> = _currentWordIndex

    private val _currentSegment = MutableStateFlow<NarrationSegment?>(null)
    val currentSegment: StateFlow<NarrationSegment?> = _currentSegment

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    val voiceSetupStatus: StateFlow<KokoroVoiceSetupStatus> = KokoroVoiceSetup.status

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<KokoroNarrationSettings> = _settings

    val sleepTimerMinutes = MutableStateFlow(0)

    private var playbackJob: Job? = null
    private var readJob: Job? = null
    private var timelineJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isPaused = false
    private var currentSegmentCursor = 0
    private var currentMacroCursor = 0
    private var segments: List<NarrationSegment> = emptyList()
    private var macroStartSegmentIndexes: List<Int> = emptyList()
    private val audioCache = mutableMapOf<Int, Deferred<KokoroSynthesisResult>>()
    private var plannedTextKey: Int? = null
    private var isPlayingCachedChapterAudio = false

    init {
        clearTemporaryNarrationAudioCache()
    }

    fun updateSettings(transform: (KokoroNarrationSettings) -> KokoroNarrationSettings) {
        _settings.value = transform(_settings.value)
        syncBackgroundService()
    }

    fun startSleepTimer(minutes: Int, onTimerFinished: () -> Unit) {
        sleepTimerJob?.cancel()
        sleepTimerMinutes.value = minutes
        if (minutes <= 0) return
        sleepTimerJob = controllerScope.launch {
            delay(minutes * 60 * 1000L)
            stop()
            sleepTimerMinutes.value = 0
            onTimerFinished()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerMinutes.value = 0
    }

    suspend fun readText(
        text: String,
        cacheKey: String? = null,
        persistAudioCache: Boolean = false
    ) {
        stopInternal(clearPlan = false)
        ensurePlan(text, resetCursor = true)
        _lastError.value = null
        isPaused = false

        if (segments.isEmpty()) {
            _lastError.value = "There is no readable text in this chapter."
            return
        }

        playbackJob = controllerScope.launch {
            if (cacheKey.isNullOrBlank()) {
                playFromSegment(0)
            } else {
                playCachedChapterAudio(cacheKey, persistAudioCache)
            }
        }
        playbackJob?.join()
    }

    fun playText(
        text: String,
        cacheKey: String? = null,
        persistAudioCache: Boolean = false
    ) {
        readJob?.cancel()
        readJob = controllerScope.launch {
            readText(
                text = text,
                cacheKey = cacheKey,
                persistAudioCache = persistAudioCache
            )
        }
    }

    suspend fun prepareText(text: String) {
        if (text.isBlank()) return
        ensurePlan(text, resetCursor = true)
        if (segments.isNotEmpty()) {
            _isBuffering.value = true
            runCatching { prepareSegment(0).await() }
                .onFailure { _lastError.value = it.message ?: "Voice preparation failed." }
            _isBuffering.value = false
        }
    }

    fun prepareChapterAudio(
        text: String,
        cacheKey: String,
        persistAudioCache: Boolean = false
    ) {
        if (text.isBlank() || cacheKey.isBlank()) return
        controllerScope.launch {
            ensurePlan(text, resetCursor = false)
            prepareChapterAudioFile(cacheKey, persistAudioCache)
        }
    }

    fun pause() {
        isPaused = true
        timelineJob?.cancel()
        pauseKokoroAudio()
        pauseAmbientCue()
        _isPlaying.value = false
        updateNarrationForegroundService(enabled = false)
        if (!isPlayingCachedChapterAudio) {
            playbackJob?.cancel()
        }
    }

    fun resume() {
        if (segments.isEmpty()) return
        isPaused = false
        resumeKokoroAudio()
        resumeAmbientCue()
        _isPlaying.value = true
        syncBackgroundService()
        if (isPlayingCachedChapterAudio) {
            timelineJob?.cancel()
            timelineJob = controllerScope.launch {
                runChapterTimeline(currentSegmentCursor.coerceIn(0, segments.lastIndex))
            }
        } else {
            playbackJob?.cancel()
            playbackJob = controllerScope.launch { playFromSegment(currentSegmentCursor.coerceIn(0, segments.lastIndex)) }
        }
    }

    fun skipForward() {
        if (segments.isEmpty()) return
        val nextMacro = (currentMacroCursor + 1).coerceAtMost(macroStartSegmentIndexes.lastIndex)
        jumpToMacro(nextMacro)
    }

    fun skipBack() {
        if (segments.isEmpty()) return
        val previousMacro = (currentMacroCursor - 1).coerceAtLeast(0)
        jumpToMacro(previousMacro)
    }

    fun seekToProgress(progress: Float) {
        if (segments.isEmpty()) return
        val targetIndex = ((segments.size - 1) * progress.coerceIn(0f, 1f)).roundToInt()
            .coerceIn(0, segments.lastIndex)
        jumpToSegment(targetIndex)
    }

    fun stop() {
        readJob?.cancel()
        stopInternal(clearPlan = true)
    }

    fun close() {
        readJob?.cancel()
        stopInternal(clearPlan = true)
        controllerScope.cancel()
    }

    private fun jumpToMacro(macroIndex: Int) {
        jumpToSegment(macroStartSegmentIndexes.getOrElse(macroIndex) { 0 })
    }

    private fun jumpToSegment(segmentIndex: Int) {
        val segment = segments.getOrNull(segmentIndex) ?: return
        isPaused = false
        playbackJob?.cancel()
        timelineJob?.cancel()
        stopKokoroAudio()
        stopAmbientCue()
        currentMacroCursor = segment.macroIndex
        currentSegmentCursor = segmentIndex
        _currentChunkFlow.value = segment.macroIndex
        _playbackProgress.value = segmentIndex.toProgressFraction()
        playbackJob = controllerScope.launch { playFromSegment(currentSegmentCursor) }
    }

    private fun stopInternal(clearPlan: Boolean) {
        playbackJob?.cancel()
        timelineJob?.cancel()
        stopKokoroAudio()
        stopAmbientCue()
        isPlayingCachedChapterAudio = false
        _isPlaying.value = false
        _isBuffering.value = false
        _currentParagraphIndex.value = -1
        _currentWordIndex.value = -1
        _currentSegment.value = null
        updateNarrationForegroundService(enabled = false)
        isPaused = false
        if (clearPlan) {
            segments = emptyList()
            macroStartSegmentIndexes = emptyList()
            audioCache.clear()
            plannedTextKey = null
            currentSegmentCursor = 0
            currentMacroCursor = 0
            _currentChunkFlow.value = 0
            _chunkBoundaries.value = emptyList()
            _playbackProgress.value = 0f
        }
    }

    private suspend fun playFromSegment(startIndex: Int) {
        _isPlaying.value = true
        syncBackgroundService()
        try {
            for (index in startIndex until segments.size) {
                if (!currentCoroutineContext().isActive || isPaused) break
                val segment = segments[index]
                currentSegmentCursor = index
                currentMacroCursor = segment.macroIndex
                _currentChunkFlow.value = segment.macroIndex
                _currentParagraphIndex.value = segment.paragraphIndex
                _currentWordIndex.value = segment.wordStartIndex
                _currentSegment.value = segment
                _playbackProgress.value = index.toProgressFraction()

                if (segment.wordEndInMacro >= NEXT_MACRO_PRELOAD_AT_WORD) {
                    preloadMacro(segment.macroIndex + 1)
                }

                val request = segment.toRequest(_settings.value)
                val audio = try {
                    _isBuffering.value = true
                    prepareSegment(index).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _lastError.value = e.message ?: "Kokoro audio generation failed."
                    continue
                } finally {
                    _isBuffering.value = false
                }

                _lastError.value = null
                if (_settings.value.ambienceEnabled) {
                    playAmbientCue(segment.ambientCue, _settings.value.ambienceVolume)
                } else {
                    stopAmbientCue()
                }

                coroutineScope {
                    timelineJob = launch { runTimeline(segment, audio.durationMs) }
                    try {
                        playKokoroAudio(audio, request)
                    } finally {
                        timelineJob?.cancelAndJoin()
                        _currentWordIndex.value = segment.wordStartIndex + segment.wordCount - 1
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Narration failed."
        } finally {
            if (!isPaused) {
                _isPlaying.value = false
                _isBuffering.value = false
                stopAmbientCue()
                updateNarrationForegroundService(enabled = false)
            }
        }
    }

    private suspend fun playCachedChapterAudio(cacheKey: String, persistent: Boolean) {
        val cachedPath = existingNarrationAudioCachePath(cacheKey, persistent)
        if (cachedPath == null) {
            playFromSegment(0)
            return
        }

        _isPlaying.value = true
        syncBackgroundService()
        _lastError.value = null
        isPlayingCachedChapterAudio = true
        try {
            coroutineScope {
                timelineJob = launch { runChapterTimeline(0) }
                try {
                    playKokoroAudioFile(cachedPath)
                } finally {
                    timelineJob?.cancelAndJoin()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Cached chapter audio failed."
            isPlayingCachedChapterAudio = false
            playFromSegment(currentSegmentCursor.coerceIn(0, segments.lastIndex))
        } finally {
            if (!isPaused) {
                _isPlaying.value = false
                _isBuffering.value = false
                stopAmbientCue()
                updateNarrationForegroundService(enabled = false)
            }
            isPlayingCachedChapterAudio = false
        }
    }

    private suspend fun prepareChapterAudioFile(cacheKey: String, persistent: Boolean): String? {
        existingNarrationAudioCachePath(cacheKey, persistent)?.let { return it }
        if (segments.isEmpty()) return null

        _isBuffering.value = true
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Synthesizing,
            message = "Creating chapter audio file for smooth playback."
        )
        return try {
            val rendered = mutableListOf<KokoroSynthesisResult>()
            for (index in segments.indices) {
                if (!currentCoroutineContext().isActive) return null
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Synthesizing,
                    downloadedBytes = index.toLong(),
                    totalBytes = segments.size.toLong(),
                    message = "Creating chapter audio ${index + 1} of ${segments.size}."
                )
                val result = prepareSegment(index).await()
                if (result.audioBytes.isEmpty()) return null
                rendered.add(result)
            }

            val chapterAudio = combineWavSegments(rendered) ?: return null
            writeNarrationAudioCache(cacheKey, persistent, chapterAudio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Could not create chapter audio file."
            null
        } finally {
            _isBuffering.value = false
        }
    }

    private suspend fun runChapterTimeline(startIndex: Int) {
        for (index in startIndex until segments.size) {
            if (!currentCoroutineContext().isActive || !_isPlaying.value || isPaused) break
            val segment = segments[index]
            currentSegmentCursor = index
            currentMacroCursor = segment.macroIndex
            _currentChunkFlow.value = segment.macroIndex
            _currentParagraphIndex.value = segment.paragraphIndex
            _currentWordIndex.value = segment.wordStartIndex
            _currentSegment.value = segment
            _playbackProgress.value = index.toProgressFraction()

            if (_settings.value.ambienceEnabled) {
                playAmbientCue(segment.ambientCue, _settings.value.ambienceVolume)
            } else {
                stopAmbientCue()
            }

            val duration = runCatching { prepareSegment(index).await().durationMs }
                .getOrDefault(segment.wordCount.coerceAtLeast(1) * 330L)
            runTimeline(segment, duration)
        }
    }

    private fun prepareSegment(index: Int): Deferred<KokoroSynthesisResult> {
        return audioCache.getOrPut(index) {
            controllerScope.async {
                val segment = segments[index]
                val activeStatus = KokoroVoiceSetup.status.value.phase
                if (activeStatus != KokoroVoiceSetupPhase.Downloading && activeStatus != KokoroVoiceSetupPhase.Installing) {
                    KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                        phase = KokoroVoiceSetupPhase.Synthesizing,
                        message = if (index == 0) {
                            "Preparing voice. First-time Kokoro setup may download once."
                        } else {
                            "Preparing the next voice segment."
                        }
                    )
                }
                synthesizeKokoroSpeech(segment.toRequest(_settings.value)).also { result ->
                    KokoroVoiceSetup.status.value = if (result.audioBytes.isNotEmpty()) {
                        KokoroVoiceSetupStatus(
                            phase = KokoroVoiceSetupPhase.Ready,
                            message = "Kokoro voice is ready on this device."
                        )
                    } else {
                        KokoroVoiceSetupStatus(
                            phase = KokoroVoiceSetupPhase.Error,
                            message = "Kokoro voice is unavailable. Check the bundled model and try again."
                        )
                    }
                }
            }
        }
    }

    private fun preloadMacro(macroIndex: Int) {
        if (macroIndex !in macroStartSegmentIndexes.indices) return
        val start = macroStartSegmentIndexes[macroIndex]
        val endExclusive = macroStartSegmentIndexes.getOrNull(macroIndex + 1) ?: segments.size
        for (index in start until endExclusive) {
            if (index !in audioCache) prepareSegment(index)
        }
    }

    private suspend fun runTimeline(segment: NarrationSegment, durationMs: Long) {
        val words = segment.words
        if (words.isEmpty()) return
        val weights = words.map { wordTimingWeight(it) }
        val totalWeight = weights.sum().takeIf { it > 0f } ?: words.size.toFloat()
        val adjustedDurationMs = (durationMs * 1.1f).roundToInt().toLong()
        val wordDurations = weights.map { weight ->
            max(145L, (adjustedDurationMs * (weight / totalWeight)).roundToInt().toLong())
        }
        var elapsed = 0L
        for (wordIndex in words.indices) {
            if (!currentCoroutineContext().isActive || !_isPlaying.value) break
            _currentParagraphIndex.value = segment.paragraphIndex
            _currentWordIndex.value = segment.wordStartIndex + wordIndex
            _playbackProgress.value = segment.index.toProgressFraction(words.size, wordIndex)
            val step = wordDurations[wordIndex]
            var spent = 0L
            while (spent < step) {
                if (!currentCoroutineContext().isActive || !_isPlaying.value) return
                delay(55L)
                spent += 55L
                elapsed += 55L
                if (elapsed >= durationMs) return
            }
        }
    }

    private fun NarrationSegment.toRequest(settings: KokoroNarrationSettings): KokoroSynthesisRequest {
        return KokoroSynthesisRequest(
            text = cleanForSpeech(text),
            tokenIds = IntArray(0),
            voiceId = voiceId(settings),
            speed = speed,
            narratorVolume = settings.narratorVolume,
            segmentIndex = index,
            macroIndex = macroIndex,
            tone = tone,
            ambientCue = ambientCue
        )
    }

    private fun ensurePlan(text: String, resetCursor: Boolean) {
        val key = text.hashCode()
        if (plannedTextKey == key && segments.isNotEmpty()) {
            if (resetCursor) {
                currentSegmentCursor = 0
                currentMacroCursor = 0
                _currentChunkFlow.value = 0
                _playbackProgress.value = 0f
            }
            return
        }
        val plan = buildNarrationPlan(text)
        segments = plan.segments
        macroStartSegmentIndexes = plan.macroStartSegmentIndexes
        _chunkBoundaries.value = plan.macroParagraphBoundaries
        _currentChunkFlow.value = 0
        currentSegmentCursor = 0
        currentMacroCursor = 0
        audioCache.clear()
        plannedTextKey = key
        _playbackProgress.value = 0f
    }

    private fun syncBackgroundService() {
        val currentSettings = _settings.value
        updateNarrationForegroundService(
            enabled = currentSettings.backgroundPlaybackEnabled && _isPlaying.value,
            title = currentSettings.backgroundTitle,
            subtitle = currentSettings.backgroundSubtitle
        )
    }

    private fun Int.toProgressFraction(wordCount: Int = 1, wordIndex: Int = 0): Float {
        if (segments.isEmpty()) return 0f
        val segmentPart = if (wordCount <= 0) 0f else wordIndex.toFloat() / wordCount.toFloat()
        return ((coerceIn(0, segments.lastIndex) + segmentPart) / segments.size.toFloat()).coerceIn(0f, 1f)
    }
}

data class KokoroNarrationSettings(
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

enum class NarrationTone {
    Neutral,
    MaleDialogue,
    FemaleDialogue,
    Action,
    Suspense,
    Soft
}

enum class AmbientCue(val id: String) {
    Rain("rain"),
    Battle("battle"),
    Suspense("suspense"),
    Calm("calm"),
    Sad("sad")
}

enum class KokoroVoiceSetupPhase {
    Idle,
    Checking,
    Downloading,
    Installing,
    Synthesizing,
    Ready,
    Fallback,
    Error
}

data class KokoroVoiceSetupStatus(
    val phase: KokoroVoiceSetupPhase = KokoroVoiceSetupPhase.Idle,
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
        get() = phase == KokoroVoiceSetupPhase.Downloading ||
            phase == KokoroVoiceSetupPhase.Installing ||
            phase == KokoroVoiceSetupPhase.Synthesizing ||
            phase == KokoroVoiceSetupPhase.Fallback ||
            phase == KokoroVoiceSetupPhase.Error

    val userMessage: String
        get() = message ?: when (phase) {
            KokoroVoiceSetupPhase.Idle -> ""
            KokoroVoiceSetupPhase.Checking -> "Checking Kokoro voice setup."
            KokoroVoiceSetupPhase.Downloading -> "Preparing Kokoro voice model."
            KokoroVoiceSetupPhase.Installing -> "Installing Kokoro voice model."
            KokoroVoiceSetupPhase.Synthesizing -> "Preparing voice."
            KokoroVoiceSetupPhase.Ready -> "Kokoro voice is ready."
            KokoroVoiceSetupPhase.Fallback -> "Kokoro voice is unavailable."
            KokoroVoiceSetupPhase.Error -> "Kokoro voice setup failed."
        }
}

object KokoroVoiceSetup {
    val status = MutableStateFlow(KokoroVoiceSetupStatus())
}

data class NarrationSegment(
    val index: Int,
    val macroIndex: Int,
    val text: String,
    val paragraphIndex: Int,
    val wordStartIndex: Int,
    val wordStartInMacro: Int,
    val wordCount: Int,
    val tone: NarrationTone,
    val ambientCue: AmbientCue?
) {
    val words: List<String> = text.wordsOnly()
    val wordEndInMacro: Int = wordStartInMacro + wordCount

    fun voiceId(settings: KokoroNarrationSettings): String {
        if (settings.voiceMode == VoiceMode.NarratorOnly) return "am_adam"
        return when (tone) {
            NarrationTone.FemaleDialogue -> "af_sarah"
            NarrationTone.MaleDialogue -> "am_michael"
            NarrationTone.Action -> "am_fenrir"
            NarrationTone.Suspense -> "bm_george"
            NarrationTone.Soft -> "af_sky"
            NarrationTone.Neutral -> "am_adam"
        }
    }

    val speed: Float
        get() = when (tone) {
            NarrationTone.Action -> 1.1f
            NarrationTone.Suspense -> 0.9f
            NarrationTone.Soft -> 0.95f
            else -> 1.0f
        }
}

data class KokoroSynthesisRequest(
    val text: String,
    val tokenIds: IntArray,
    val voiceId: String,
    val speed: Float,
    val narratorVolume: Float,
    val segmentIndex: Int,
    val macroIndex: Int,
    val tone: NarrationTone,
    val ambientCue: AmbientCue?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KokoroSynthesisRequest) return false
        return text == other.text &&
            tokenIds.contentEquals(other.tokenIds) &&
            voiceId == other.voiceId &&
            speed == other.speed &&
            narratorVolume == other.narratorVolume &&
            segmentIndex == other.segmentIndex &&
            macroIndex == other.macroIndex &&
            tone == other.tone &&
            ambientCue == other.ambientCue
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + tokenIds.contentHashCode()
        result = 31 * result + voiceId.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + narratorVolume.hashCode()
        result = 31 * result + segmentIndex
        result = 31 * result + macroIndex
        result = 31 * result + tone.hashCode()
        result = 31 * result + (ambientCue?.hashCode() ?: 0)
        return result
    }
}

data class KokoroSynthesisResult(
    val audioBytes: ByteArray,
    val durationMs: Long,
    val sampleRate: Int = 24_000,
    val engineName: String = "Kokoro"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KokoroSynthesisResult) return false
        return audioBytes.contentEquals(other.audioBytes) &&
            durationMs == other.durationMs &&
            sampleRate == other.sampleRate &&
            engineName == other.engineName
    }

    override fun hashCode(): Int {
        var result = audioBytes.contentHashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + engineName.hashCode()
        return result
    }
}

expect suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult
expect suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest)
expect suspend fun platformPlayAudio(audioBytes: ByteArray)
expect suspend fun playKokoroAudioFile(filePath: String)
expect fun stopKokoroAudio()
expect fun pauseKokoroAudio()
expect fun resumeKokoroAudio()
expect fun clearTemporaryNarrationAudioCache()
expect fun existingNarrationAudioCachePath(cacheKey: String, persistent: Boolean): String?
expect fun writeNarrationAudioCache(cacheKey: String, persistent: Boolean, audioBytes: ByteArray): String?
expect fun playAmbientCue(cue: AmbientCue?, volume: Float)
expect fun pauseAmbientCue()
expect fun resumeAmbientCue()
expect fun stopAmbientCue()
expect fun updateNarrationForegroundService(
    enabled: Boolean,
    title: String = "NovelApp narration",
    subtitle: String = "Reading in background"
)

private data class NarrationPlan(
    val segments: List<NarrationSegment>,
    val macroStartSegmentIndexes: List<Int>,
    val macroParagraphBoundaries: List<Int>
)

private fun buildNarrationPlan(text: String): NarrationPlan {
    val paragraphs = text.toNarrationParagraphs()
    val segments = mutableListOf<NarrationSegment>()
    val macroStarts = mutableListOf(0)
    val macroParagraphs = mutableListOf(0)
    var macroIndex = 0
    var macroWordCount = 0

    paragraphs.forEachIndexed { paragraphIndex, paragraph ->
        var paragraphWordCursor = 0
        val parts = paragraph.splitIntoNarrationParts()
        for (part in parts) {
            val words = part.wordsOnly()
            if (words.isEmpty()) continue
            if (macroWordCount > 0 && macroWordCount + words.size > MACRO_WORD_TARGET) {
                macroIndex++
                macroWordCount = 0
                macroStarts.add(segments.size)
                macroParagraphs.add(paragraphIndex)
            }
            val tone = detectTone(part)
            segments.add(
                NarrationSegment(
                    index = segments.size,
                    macroIndex = macroIndex,
                    text = part,
                    paragraphIndex = paragraphIndex,
                    wordStartIndex = paragraphWordCursor,
                    wordStartInMacro = macroWordCount,
                    wordCount = words.size,
                    tone = tone,
                    ambientCue = detectAmbientCue(part, tone)
                )
            )
            paragraphWordCursor += words.size
            macroWordCount += words.size
            if (macroWordCount >= MACRO_WORD_TARGET) {
                macroIndex++
                macroWordCount = 0
                macroStarts.add(segments.size)
                macroParagraphs.add(paragraphIndex)
            }
        }
    }

    val cleanStarts = macroStarts.filter { it < segments.size }.ifEmpty { listOf(0) }
    val cleanParagraphs = macroParagraphs.take(cleanStarts.size).ifEmpty { listOf(0) }
    return NarrationPlan(segments, cleanStarts, cleanParagraphs)
}

private fun String.toNarrationParagraphs(): List<String> =
    cleanForSpeech(this)
        .split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { paragraph ->
            if (paragraph.length <= 520) {
                listOf(paragraph)
            } else {
                paragraph.splitReaderSentenceBlocks(maxChars = 420)
            }
        }

private fun String.splitReaderSentenceBlocks(maxChars: Int): List<String> {
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

private fun String.splitIntoNarrationParts(): List<String> {
    val sentenceParts = split(Regex("""(?<=[.!?。！？…])\s+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(this.trim()) }
    return sentenceParts.flatMap { sentence ->
        if (sentence.wordsOnly().size <= 55) {
            listOf(sentence)
        } else {
            sentence.wordsOnly().chunked(45).map { it.joinToString(" ") }
        }
    }
}

private fun detectTone(text: String): NarrationTone {
    val lower = text.lowercase()
    val isDialogue = lower.contains('"') || lower.contains('“') || lower.contains('”') || lower.contains("'")
    if (isDialogue) {
        val femaleHints = listOf(" she ", " her ", " woman", " girl", " mother", " sister", " queen", " lady", " madam", " princess", " whispered")
        val maleHints = listOf(" he ", " him ", " man", " boy", " father", " brother", " king", " lord", " prince", " yelled", " roared")
        val padded = " $lower "
        val femaleScore = femaleHints.count { padded.contains(it) }
        val maleScore = maleHints.count { padded.contains(it) }
        return if (femaleScore > maleScore) NarrationTone.FemaleDialogue else NarrationTone.MaleDialogue
    }
    val actionScore = actionWords.count { lower.contains(it) } + lower.count { it == '!' }
    if (actionScore >= 2) return NarrationTone.Action
    if (suspenseWords.any { lower.contains(it) }) return NarrationTone.Suspense
    if (softWords.any { lower.contains(it) }) return NarrationTone.Soft
    return NarrationTone.Neutral
}

private fun detectAmbientCue(text: String, tone: NarrationTone): AmbientCue? {
    val lower = text.lowercase()
    Regex("""\[bg:([a-z_ -]+)]""").find(lower)?.groupValues?.getOrNull(1)?.let { tag ->
        return when {
            "rain" in tag || "storm" in tag -> AmbientCue.Rain
            "battle" in tag || "action" in tag -> AmbientCue.Battle
            "suspense" in tag || "dark" in tag -> AmbientCue.Suspense
            "sad" in tag || "dramatic" in tag -> AmbientCue.Sad
            "calm" in tag || "cozy" in tag -> AmbientCue.Calm
            else -> null
        }
    }
    if (lower.contains("rain") || lower.contains("thunder") || lower.contains("storm")) return AmbientCue.Rain
    if (tone == NarrationTone.Action) return AmbientCue.Battle
    if (tone == NarrationTone.Suspense) return AmbientCue.Suspense
    if (tone == NarrationTone.Soft) return AmbientCue.Calm
    if (lower.contains("sad") || lower.contains("grief") || lower.contains("tears")) return AmbientCue.Sad
    return null
}

private fun cleanForSpeech(text: String): String =
    text.replace(Regex("""\[bg:[^]]+]"""), " ")
        .replace(Regex("""\[[A-Z][A-Z _-]{2,}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.wordsOnly(): List<String> =
    Regex("""[\p{L}\p{N}']+[.,!?;:…]?""").findAll(this).map { it.value }.toList()

private fun wordTimingWeight(word: String): Float {
    val punctuation = when {
        word.endsWith(".") || word.endsWith("?") || word.endsWith("!") -> 0.75f
        word.endsWith(",") || word.endsWith(";") || word.endsWith(":") -> 0.35f
        word.endsWith("…") -> 0.9f
        else -> 0f
    }
    return 0.85f + (word.length.coerceAtMost(14) * 0.08f) + punctuation
}

private fun combineWavSegments(results: List<KokoroSynthesisResult>): ByteArray? {
    val audioResults = results.filter { it.audioBytes.size > 44 && it.sampleRate > 0 }
    if (audioResults.isEmpty()) return null
    val sampleRate = audioResults.first().sampleRate
    val pcmSize = audioResults.sumOf { (it.audioBytes.size - 44).coerceAtLeast(0) }
    if (pcmSize <= 0) return null
    val pcm = ByteArray(pcmSize)
    var offset = 0
    audioResults.forEach { result ->
        val source = result.audioBytes
        val length = (source.size - 44).coerceAtLeast(0)
        if (length > 0) {
            source.copyInto(pcm, destinationOffset = offset, startIndex = 44, endIndex = source.size)
            offset += length
        }
    }
    return pcm16MonoToWav(pcm, sampleRate)
}

private fun pcm16MonoToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
    val byteRate = sampleRate * 2
    val totalDataLen = pcmBytes.size + 36
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

private val actionWords = listOf(
    "attack", "attacked", "battle", "burst", "charged", "clash", "clashed", "crash",
    "exploded", "fight", "fist", "flame", "kicked", "kill", "killed", "lightning",
    "punch", "roared", "rush", "rushed", "shot", "shouted", "slammed", "slash",
    "sword", "thunder", "trembled"
)

private val suspenseWords = listOf(
    "afraid", "blood", "cold", "dark", "dread", "fear", "horror", "mystery",
    "quiet", "secret", "shadow", "silent", "suspicious", "terror", "trembling",
    "whisper", "whispered"
)

private val softWords = listOf(
    "beautiful", "calm", "comfort", "gentle", "heart", "kiss", "love", "peace",
    "smile", "soft", "tender", "warm"
)
