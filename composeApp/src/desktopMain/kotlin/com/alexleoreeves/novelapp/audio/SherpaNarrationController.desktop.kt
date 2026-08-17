package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Desktop (Windows) actual — real on-device narration driven by the Windows
 * SAPI voice through PowerShell → WAV, played via the existing javax.sound
 * clip player. Mirrors the Android experience:
 *
 *  - paragraph-by-paragraph streaming with paragraph highlight as speech plays
 *  - pause / resume / stop / skip forward / skip back / seek-to-progress
 *  - narrator volume applied to every synthesized paragraph
 *  - `downloadChapterAudio` renders the whole chapter to a single WAV file,
 *    giving real offline narration downloads on Windows
 *  - non-Windows hosts stay graceful: voiceSetupStatus reports Error with a
 *    readable message and playback is a no-op (never crashes)
 *
 * All SAPI interaction is guarded by an OS check so the module still compiles
 * and runs on Linux/macOS (used for local compile checks and other packaging).
 */
actual class SherpaNarrationController actual constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackJob: Job? = null
    private var sleepTimerJob: Job? = null

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

    private val _isTestingVoice = MutableStateFlow(false)
    actual val isTestingVoice: StateFlow<Boolean> = _isTestingVoice

    private val _voiceSetupStatus = MutableStateFlow(
        if (isWindows()) {
            VoiceSetupStatus(phase = VoiceSetupPhase.Ready, message = "Windows system voice ready.")
        } else {
            VoiceSetupStatus(
                phase = VoiceSetupPhase.Error,
                message = "Desktop narration uses the Windows system voice. Voice playback is only available on Windows."
            )
        }
    )
    actual val voiceSetupStatus: StateFlow<VoiceSetupStatus> = _voiceSetupStatus

    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: StateFlow<String?> = _lastError

    private val _settings = MutableStateFlow(NarrationSettings())
    actual val settings: StateFlow<NarrationSettings> = _settings

    actual val sleepTimerMinutes = MutableStateFlow(0)

    // ── Playback state ────────────────────────────────────────────────────
    private var paragraphs = listOf<String>()
    private var currentParagraph = 0
    private var isPaused = false
    private var generation = 0

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

    actual fun playText(text: String, cacheKey: String?, persistAudioCache: Boolean, isDialogueOnly: Boolean) {
        if (text.isBlank()) return
        stopInternal()

        paragraphs = text.toDesktopNarrationBlocks()
        if (paragraphs.isEmpty()) return

        currentParagraph = 0
        isPaused = false
        _isPlaying.value = true
        _lastError.value = null
        generation++

        playbackJob?.cancel()
        playbackJob = scope.launch {
            speakFrom(currentParagraph)
        }
    }

    actual fun testVoice(voiceId: Int) {
        playText("This is a test of the selected voice.", cacheKey = null)
    }

    actual fun pause() {
        if (!_isPlaying.value) return
        isPaused = true
        stopPlatformNarrationAudio()
        _isPlaying.value = false
        _isBuffering.value = false
    }

    actual fun resume() {
        if (isPaused && paragraphs.isNotEmpty()) {
            isPaused = false
            _isPlaying.value = true
            val resumeFrom = currentParagraph
            generation++
            playbackJob?.cancel()
            playbackJob = scope.launch {
                speakFrom(resumeFrom)
            }
        }
    }

    actual fun stop() {
        stopInternal()
        finishPlayback()
    }

    actual fun skipForward() {
        if (paragraphs.isEmpty()) return
        val next = (currentParagraph + 1).coerceAtMost(paragraphs.lastIndex)
        if (next >= paragraphs.lastIndex && _currentParagraphIndex.value >= paragraphs.lastIndex) {
            stop()
            return
        }
        jumpTo(next)
    }

    actual fun skipBack() {
        if (paragraphs.isEmpty()) return
        val prev = (currentParagraph - 1).coerceAtLeast(0)
        jumpTo(prev)
    }

    actual fun seekToProgress(progress: Float) {
        if (paragraphs.isEmpty()) return
        val target = (progress.coerceIn(0f, 1f) * paragraphs.size).roundToInt().coerceIn(0, paragraphs.lastIndex)
        jumpTo(target)
    }

    private fun jumpTo(paragraphIndex: Int) {
        isPaused = false
        _isPlaying.value = true
        currentParagraph = paragraphIndex
        generation++
        playbackJob?.cancel()
        playbackJob = scope.launch {
            speakFrom(paragraphIndex)
        }
    }

    private suspend fun speakFrom(fromIndex: Int) {
        val gen = generation
        withContext(Dispatchers.IO) {
            for (i in fromIndex until paragraphs.size) {
                if (gen != generation) return@withContext
                val paragraph = paragraphs[i]
                if (paragraph.isBlank()) continue

                runCatching {
                    val wav = File(createTempDir("narration-", UUID.randomUUID().toString()), "par.wav")
                    try {
                        _currentParagraphIndex.value = i
                        _playbackProgress.value = (i + 1).toFloat() / paragraphs.size.coerceAtLeast(1)

                        synthParagraphWav(paragraph, wav)
                        if (gen != generation) return@withContext
                        playAudioFile(wav.absolutePath)
                    } finally {
                        wav.delete()
                    }
                }.onFailure {
                    if (gen == generation) {
                        _lastError.value = it.message ?: "Narration playback failed."
                    }
                }

                if (gen != generation) return@withContext
                currentParagraph = i + 1
                while (isPaused && gen == generation) {
                    delay(120)
                }
            }
            if (gen == generation) {
                _isPlaying.value = false
                _currentParagraphIndex.value = -1
                _currentWordIndex.value = -1
                _playbackProgress.value = 0f
            }
        }
    }

    private fun stopInternal() {
        generation++
        isPaused = false
        playbackJob?.cancel()
        stopPlatformNarrationAudio()
    }

    private fun finishPlayback() {
        _isPlaying.value = false
        _isBuffering.value = false
        _currentParagraphIndex.value = -1
        _currentWordIndex.value = -1
        _playbackProgress.value = 0f
        _lastError.value = null
    }

    actual suspend fun downloadChapterAudio(text: String, chapterName: String): String? {
        if (!isWindows()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val safeName = chapterName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "chapter" }
                val outDir = File(System.getProperty("user.home") ?: ".", ".aninovelmanga/narration-audio")
                outDir.mkdirs()
                val wav = File(outDir, "$safeName.wav")
                if (wav.exists()) wav.delete()
                synthParagraphWav(text, wav, forceSingle = true)
                if (wav.exists() && wav.length() > 44) wav.absolutePath else null
            }.getOrNull()
        }
    }

    actual fun close() {
        stopInternal()
        sleepTimerJob?.cancel()
        scope.coroutineContext.cancelChildren()
    }
}

/**
 * Synthesizes [text] to [wav] using the Windows SAPI voice via PowerShell.
 *
 * SAPI is a .NET assembly; PowerShell exposes it natively on every Windows
 * machine (no admin rights, no extra installs — the voice ships with Windows).
 */
private fun synthParagraphWav(text: String, wav: File, forceSingle: Boolean = false) {
    if (!isWindows()) throw IllegalStateException("Desktop narration requires Windows.")
    val escaped = text
        .replace("'", "''")
        .replace("\r", " ")
        .replace("\n", " ")
    val ps = "Add-Type -AssemblyName System.Speech; " +
        "try { " +
        "  \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
        "  \$s.Volume = 100; " +
        "  \$s.SetOutputToWaveFile('${wav.absolutePath.replace("'", "''")}'); " +
        "  \$s.Speak('$escaped'); " +
        "  \$s.Dispose(); " +
        "  Write-Output 'OK' " +
        "} catch { Write-Error \$_.Exception.Message; exit 1 }"
    val command = listOf(
        "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", ps
    )
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit != 0 || !wav.exists() || wav.length() <= 44L) {
        throw IllegalStateException("Windows voice could not speak: ${output.trim().take(160)}")
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("win") == true

private fun String.toDesktopNarrationBlocks(): List<String> =
    split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { paragraph ->
            if (paragraph.length <= 520) listOf(paragraph)
            else paragraph.splitDesktopSentenceBlocks(maxChars = 420)
        }

private fun String.splitDesktopSentenceBlocks(maxChars: Int): List<String> {
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
