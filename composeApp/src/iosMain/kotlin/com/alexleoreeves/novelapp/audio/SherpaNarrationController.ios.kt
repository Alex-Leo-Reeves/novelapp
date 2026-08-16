@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.audio

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSRange
import platform.Foundation.NSUserDefaults
import platform.darwin.NSObject
import kotlin.math.roundToInt

/**
 * iOS actual — real on-device narration via AVSpeechSynthesizer.
 *
 * Mirrors the Android experience:
 *  - paragraph + word-by-word highlighting through the delegate callback
 *  - pause / resume / stop / skip forward / skip back / seek-to-progress
 *  - volume + dynamic-voice mode persisted in NSUserDefaults
 *  - play/continue uses the Playback audio session category for background audio
 */
actual class SherpaNarrationController actual constructor() {

    private val prefs = NSUserDefaults.standardUserDefaults
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sleepTimerJob: Job? = null
    private var navigationJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentChunkIndex = MutableStateFlow(0)
    actual val currentChunkIndex: StateFlow<Int> = _currentChunkIndex

    private val _chunkBoundaries = MutableStateFlow(emptyList<Int>())
    actual val chunkBoundaries: StateFlow<List<Int>> = _chunkBoundaries

    private val _currentParagraphIndex = MutableStateFlow(-1)
    actual val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex

    private val _currentWordIndex = MutableStateFlow(-1)
    actual val currentWordIndex: StateFlow<Int> = _currentWordIndex

    private val _playbackProgress = MutableStateFlow(0f)
    actual val playbackProgress: StateFlow<Float> = _playbackProgress

    private val _isBuffering = MutableStateFlow(false)
    actual val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _voiceSetupStatus = MutableStateFlow(
        VoiceSetupStatus(phase = VoiceSetupPhase.Ready, message = "iOS system voice ready.")
    )
    actual val voiceSetupStatus: StateFlow<VoiceSetupStatus> = _voiceSetupStatus

    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: StateFlow<String?> = _lastError

    private val _settings = MutableStateFlow(
        NarrationSettings(
            narratorVolume = prefs.floatForKey(KEY_VOLUME).takeIf { it > 0f } ?: 1f,
            ambienceEnabled = prefs.boolForKey(KEY_AMBIENCE_ENABLED),
            voiceMode = if (prefs.boolForKey(KEY_DYNAMIC_VOICES)) VoiceMode.Dynamic else VoiceMode.NarratorOnly,
            backgroundPlaybackEnabled = prefs.boolForKey(KEY_BACKGROUND_PLAYBACK)
        )
    )
    actual val settings: StateFlow<NarrationSettings> = _settings

    actual val sleepTimerMinutes = MutableStateFlow(0)

    // ── Playback state ────────────────────────────────────────────────────
    private var paragraphs = listOf<String>()
    private var currentParagraphToSpeak = 0
    private var totalParagraphCount = 0
    private var finishedUtteranceCount = 0

    private val delegate = NarrationDelegate(
        onRange = { paragraphIndex, wordIndex ->
            _currentParagraphIndex.value = paragraphIndex
            _currentWordIndex.value = wordIndex
            _playbackProgress.value = if (totalParagraphCount > 0) {
                ((paragraphIndex + 1).toFloat() / totalParagraphCount.toFloat()).coerceIn(0f, 1f)
            } else 0f
        },
        onDidFinishUtterance = {
            finishedUtteranceCount++
            val finishedAll = finishedUtteranceCount >= totalParagraphCount
            if (finishedAll && totalParagraphCount > 0) {
                finishPlayback()
            }
        },
        onDidCancel = {
            // Cancelled by stop/skip — don't finish; the caller resets state.
        }
    )

    init {
        synthesizer.setDelegate(delegate)
        activateAudioSession()
    }

    private fun activateAudioSession() {
        val session = AVAudioSession.sharedInstance()
        try {
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (_: Throwable) {
        }
    }

    actual fun updateSettings(transform: (NarrationSettings) -> NarrationSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.setFloat(updated.narratorVolume, forKey = KEY_VOLUME)
        prefs.setBool(updated.ambienceEnabled, forKey = KEY_AMBIENCE_ENABLED)
        prefs.setBool(updated.voiceMode == VoiceMode.Dynamic, forKey = KEY_DYNAMIC_VOICES)
        prefs.setBool(updated.backgroundPlaybackEnabled, forKey = KEY_BACKGROUND_PLAYBACK)
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

    actual fun playText(text: String, cacheKey: String?, persistAudioCache: Boolean, isDialogueOnly: Boolean) {
        if (text.isBlank()) return
        stop()

        paragraphs = text.toIosNarrationBlocks()
        if (paragraphs.isEmpty()) return

        _isBuffering.value = true
        _lastError.value = null
        currentParagraphToSpeak = 0
        totalParagraphCount = paragraphs.size
        finishedUtteranceCount = 0

        scope.launch {
            // Small delay so the UI renders the buffering state.
            delay(80)
            _isBuffering.value = false
            scheduleUtterances(fromIndex = 0)
            _isPlaying.value = true
        }
    }

    private fun scheduleUtterances(fromIndex: Int) {
        if (fromIndex >= paragraphs.size) return
        val volume = _settings.value.narratorVolume.coerceIn(0f, 1f)
        for (i in fromIndex until paragraphs.size) {
            val paragraph = paragraphs[i]
            if (paragraph.isBlank()) continue
            currentParagraphToSpeak = i
            val utterance = AVSpeechUtterance.speechUtteranceWithString(paragraph)
            utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage("en-US")
            utterance.rate = 0.48f
            utterance.pitchMultiplier = 1.0f
            utterance.volume = volume
            synthesizer.speakUtterance(utterance)
        }
    }

    actual fun testVoice(voiceId: Int) {
        val utterance = AVSpeechUtterance.speechUtteranceWithString("This is a test of the selected voice.")
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage("en-US")
        utterance.rate = 0.48f
        utterance.volume = _settings.value.narratorVolume.coerceIn(0f, 1f)
        synthesizer.speakUtterance(utterance)
    }

    actual fun pause() {
        if (synthesizer.isSpeaking()) {
            synthesizer.pauseSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        _isPlaying.value = false
        _isBuffering.value = false
    }

    actual fun resume() {
        if (synthesizer.isPaused()) {
            synthesizer.continueSpeaking()
            _isPlaying.value = true
        } else if (!_isPlaying.value && paragraphs.isNotEmpty()) {
            navigationJob?.cancel()
            navigationJob = scope.launch {
                _isBuffering.value = true
                delay(120)
                _isBuffering.value = false
                synthesizedFromIndex(currentParagraphToSpeak)
                _isPlaying.value = true
            }
        }
    }

    actual fun stop() {
        if (synthesizer.isSpeaking() || synthesizer.isPaused()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        // Cancel/stop does NOT fire didFinishSpeechUtterance, so without this
        // reset the delegate's active index drifts after every skip/seek/stop,
        // corrupting paragraph highlighting for the next play.
        delegate.resetUtteranceIndex()
        navigationJob?.cancel()
        finishPlayback()
    }

    private fun finishPlayback() {
        _isPlaying.value = false
        _isBuffering.value = false
        _currentParagraphIndex.value = -1
        _currentWordIndex.value = -1
        _playbackProgress.value = 0f
        _lastError.value = null
        finishedUtteranceCount = 0
    }

    actual fun skipForward() {
        val next = _currentParagraphIndex.value + 1
        if (next >= paragraphs.size || paragraphs.isEmpty()) {
            stop()
            return
        }
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryWord)
        _isBuffering.value = true
        navigationJob?.cancel()
        navigationJob = scope.launch {
            delay(60)
            _isBuffering.value = false
            scheduleUtterances(fromIndex = next)
            _isPlaying.value = true
        }
    }

    actual fun skipBack() {
        if (paragraphs.isEmpty()) return
        val prev = (_currentParagraphIndex.value - 1).coerceAtLeast(0)
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryWord)
        _isBuffering.value = true
        navigationJob?.cancel()
        navigationJob = scope.launch {
            delay(60)
            _isBuffering.value = false
            scheduleUtterances(fromIndex = prev)
            _isPlaying.value = true
        }
    }

    actual fun seekToProgress(progress: Float) {
        if (paragraphs.isEmpty()) return
        val target = (progress.coerceIn(0f, 1f) * paragraphs.size).roundToInt().coerceIn(0, paragraphs.lastIndex)
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryWord)
        _isBuffering.value = true
        navigationJob?.cancel()
        navigationJob = scope.launch {
            delay(60)
            _isBuffering.value = false
            scheduleUtterances(fromIndex = target)
            _isPlaying.value = true
        }
    }

    private fun synthesizedFromIndex(index: Int) {
        scheduleUtterances(fromIndex = index)
    }

    actual suspend fun downloadChapterAudio(text: String, chapterName: String): String? {
        // Offline audio export via AVSpeechSynthesizer write-to-file is not
        // exposed publicly on iOS; narrative playback works fully in-app.
        return null
    }

    actual fun close() {
        stop()
        synthesizer.setDelegate(null)
        navigationJob?.cancel()
        scope.coroutineContext.cancelChildren()
    }
}

private class NarrationDelegate(
    private val onRange: (paragraphIndex: Int, wordIndex: Int) -> Unit,
    private val onDidFinishUtterance: () -> Unit,
    private val onDidCancel: () -> Unit
) : NSObject(), AVSpeechSynthesizerDelegateProtocol {

    private var activeUtteranceIndex = 0

    /** Reset the internal utterance counter (stop/cancel path). */
    fun resetUtteranceIndex() {
        activeUtteranceIndex = 0
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didStartSpeechUtterance: AVSpeechUtterance
    ) {
        // Nothing to advance here — didFinishSpeechUtterance drives the index.
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
        // The synthesizer queues utterances; after one finishes the next
        // becomes active, so advance the active index to map paragraphs.
        activeUtteranceIndex++
        onDidFinishUtterance()
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
        onDidCancel()
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        willSpeakRangeOfSpeechString: CValue<NSRange>,
        utterance: AVSpeechUtterance
    ) {
        // willSpeakRange fires for the currently-speaking utterance. The
        // active index advances in didFinish, which fires just before the
        // next utterance begins, so this maps to the right paragraph.
        val paragraphIndex = activeUtteranceIndex

        val text = utterance.speechString ?: return
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        var charStart = 0
        var charLength = 0
        willSpeakRangeOfSpeechString.useContents {
            charStart = location.toInt().coerceAtLeast(0)
            charLength = length.toInt().coerceAtLeast(0)
        }
        val charEnd = (charStart + charLength).coerceAtMost(text.length)

        var wordIndex = 0
        var charSeen = 0
        for ((i, word) in words.withIndex()) {
            val wordStart = text.indexOf(word, startIndex = charSeen)
            if (wordStart < 0) {
                charSeen += word.length + 1
                continue
            }
            if (charStart < wordStart + word.length && charEnd > wordStart) {
                wordIndex = i
                break
            }
            charSeen = wordStart + word.length + 1
        }

        onRange(paragraphIndex, wordIndex)
    }
}

/**
 * Mirrors Android's toNarrationBlocks so paragraph indices map 1:1 with the
 * ReaderScreen LazyColumn items.
 */
private fun String.toIosNarrationBlocks(): List<String> =
    split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { paragraph ->
            if (paragraph.length <= 520) {
                listOf(paragraph)
            } else {
                paragraph.splitIosSentenceBlocks(maxChars = 420)
            }
        }

private fun String.splitIosSentenceBlocks(maxChars: Int): List<String> {
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

private const val KEY_VOLUME = "ios_narration_volume"
private const val KEY_AMBIENCE_ENABLED = "ios_narration_ambience_enabled"
private const val KEY_DYNAMIC_VOICES = "ios_narration_dynamic_voices"
private const val KEY_BACKGROUND_PLAYBACK = "ios_narration_background_playback"
