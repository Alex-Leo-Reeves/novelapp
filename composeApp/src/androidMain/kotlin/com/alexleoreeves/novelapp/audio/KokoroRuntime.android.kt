package com.alexleoreeves.novelapp.audio

import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import org.json.JSONObject

actual suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult =
    withContext(Dispatchers.IO) {
        // If engine hasn't been warm-started yet, do a blocking warmup now so the
        // session is guaranteed to exist before synthesis attempts to use it.
        if (!AndroidKokoroEngine.isReadyForImmediateUse()) {
            AndroidKokoroEngine.prepareInBackground(force = true)
            AndroidKokoroEngine.awaitReady()
        }
        runCatching { AndroidKokoroEngine.synthesize(request) }
            .recoverCatching { firstError ->
                // One retry — ONNX can transiently fail (NNAPI init race, memory pressure)
                runCatching { AndroidKokoroEngine.synthesize(request) }
                    .getOrElse { retryError ->
                        val diagnostics = AndroidKokoroEngine.diagnosticsFor(request, retryError)
                        println("[Kokoro] ONNX failed after retry: $diagnostics")
                        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                            phase = KokoroVoiceSetupPhase.Fallback,
                            message = "Using Android system speech (ONNX unavailable)."
                        )
                        try {
                            AndroidSystemTtsEngine.synthesizeToFile(request)
                        } catch (ttsError: Exception) {
                            println("[Kokoro] Fallback also failed: ${ttsError.message}")
                            KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                                phase = KokoroVoiceSetupPhase.Error,
                                message = "Kokoro failed: ${retryError.message ?: "unknown error"}"
                            )
                            throw retryError
                        }
                    }
            }.getOrThrow()
    }

actual suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest) {
    if (result.engineName == AndroidSystemTtsEngine.ENGINE_NAME) {
        AndroidSystemTtsEngine.speak(request)
    } else if (result.audioBytes.isNotEmpty()) {
        platformPlayAudio(result.audioBytes)
    }
}

actual fun stopKokoroAudio() {
    stopPlatformNarrationAudio()
    AndroidSystemTtsEngine.stop()
}

actual fun pauseKokoroAudio() {
    pausePlatformNarrationAudio()
    AndroidSystemTtsEngine.stop()
}

actual fun resumeKokoroAudio() {
    resumePlatformNarrationAudio()
}

actual fun playAmbientCue(cue: AmbientCue?, volume: Float) {
    AndroidAmbientPlayer.play(cue, volume)
}

actual fun pauseAmbientCue() = AndroidAmbientPlayer.pause()
actual fun resumeAmbientCue() = AndroidAmbientPlayer.resume()
actual fun stopAmbientCue() = AndroidAmbientPlayer.stop()

private object AndroidSystemTtsEngine {
    const val ENGINE_NAME = "Android instant speech"
    private var initDeferred: CompletableDeferred<TextToSpeech>? = null
    private var tts: TextToSpeech? = null

    suspend fun estimate(request: KokoroSynthesisRequest): KokoroSynthesisResult {
        ensureReady(request)
        val words = request.text.split(Regex("""\s+""")).count { it.isNotBlank() }
        val durationMs = ((words * 360f) / request.speed.coerceAtLeast(0.5f)).toLong().coerceAtLeast(450L)
        return KokoroSynthesisResult(
            audioBytes = ByteArray(0),
            durationMs = durationMs,
            sampleRate = 0,
            engineName = ENGINE_NAME
        )
    }

    suspend fun speak(request: KokoroSynthesisRequest) {
        val engine = ensureReady(request)
        withContext(Dispatchers.Main.immediate) {
            engine.language = request.locale()
            engine.setSpeechRate(request.speed.coerceIn(0.75f, 1.25f))
            engine.setPitch(request.pitch())
        }

        suspendCancellableCoroutine { cont ->
            val utteranceId = "novelapp-${UUID.randomUUID()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(doneId: String?) {
                    if (doneId == utteranceId && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Android SDK")
                override fun onError(errorId: String?) {
                    if (errorId == utteranceId && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Android speech engine could not read this segment."))
                    }
                }

                override fun onError(errorId: String?, errorCode: Int) {
                    if (errorId == utteranceId && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Android speech engine error $errorCode."))
                    }
                }
            })

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, request.narratorVolume.coerceIn(0f, 1f))
            }
            val result = engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                cont.resumeWithException(IllegalStateException("Android speech engine is unavailable."))
            }
            cont.invokeOnCancellation {
                runCatching { engine.stop() }
            }
        }
    }

    suspend fun synthesizeToFile(request: KokoroSynthesisRequest): KokoroSynthesisResult {
        val context = AppContextHolder.applicationContext
            ?: error("Android app context is unavailable for generated speech.")
        val engine = ensureReady(request)
        val outputFile = File(
            context.cacheDir,
            "narration-audio-temp/android_tts_${request.segmentIndex}_${UUID.randomUUID()}.wav"
        ).apply {
            parentFile?.mkdirs()
            delete()
        }

        withContext(Dispatchers.Main.immediate) {
            engine.language = request.locale()
            engine.setSpeechRate(request.speed.coerceIn(0.75f, 1.25f))
            engine.setPitch(request.pitch())
        }

        suspendCancellableCoroutine { cont ->
            val utteranceId = "novelapp-file-${UUID.randomUUID()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(doneId: String?) {
                    if (doneId == utteranceId && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Android SDK")
                override fun onError(errorId: String?) {
                    if (errorId == utteranceId && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Android speech engine could not create audio."))
                    }
                }

                override fun onError(errorId: String?, errorCode: Int) {
                    if (errorId == utteranceId && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Android speech engine file error $errorCode."))
                    }
                }
            })

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, request.narratorVolume.coerceIn(0f, 1f))
            }
            val result = engine.synthesizeToFile(request.text, params, outputFile, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                cont.resumeWithException(IllegalStateException("Android speech engine cannot synthesize this audio."))
            }
            cont.invokeOnCancellation {
                runCatching { engine.stop() }
                outputFile.delete()
            }
        }

        if (!outputFile.exists() || outputFile.length() <= 44L) {
            outputFile.delete()
            error("Android speech engine created an empty audio file.")
        }
        val bytes = outputFile.readBytes()
        outputFile.delete()
        val estimate = estimate(request)
        return KokoroSynthesisResult(
            audioBytes = bytes,
            durationMs = estimate.durationMs,
            sampleRate = bytes.wavSampleRateOr(24_000),
            engineName = "Android generated speech fallback"
        )
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    private suspend fun ensureReady(request: KokoroSynthesisRequest): TextToSpeech {
        tts?.let { return it }
        val context = AppContextHolder.applicationContext
            ?: error("Android app context is unavailable for text-to-speech.")

        val deferred = synchronized(this) {
            initDeferred ?: CompletableDeferred<TextToSpeech>().also { pending ->
                initDeferred = pending
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                    var created: TextToSpeech? = null
                    created = TextToSpeech(context.applicationContext) { status ->
                        val active = created
                        if (status == TextToSpeech.SUCCESS && active != null) {
                            active.language = request.locale()
                            tts = active
                            pending.complete(active)
                        } else {
                            pending.completeExceptionally(IllegalStateException("Android speech engine failed to initialize."))
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    private fun KokoroSynthesisRequest.locale(): Locale =
        if (voiceId.startsWith("bf_") || voiceId.startsWith("bm_")) Locale.UK else Locale.US

    private fun KokoroSynthesisRequest.pitch(): Float =
        when (tone) {
            NarrationTone.FemaleDialogue -> 1.08f
            NarrationTone.Action -> 0.92f
            NarrationTone.Suspense -> 0.88f
            NarrationTone.Soft -> 1.02f
            else -> 1.0f
        }
}

private fun ByteArray.wavSampleRateOr(defaultValue: Int): Int {
    if (size < 28) return defaultValue
    return ((this[24].toInt() and 0xff)
        or ((this[25].toInt() and 0xff) shl 8)
        or ((this[26].toInt() and 0xff) shl 16)
        or ((this[27].toInt() and 0xff) shl 24))
        .takeIf { it > 0 }
        ?: defaultValue
}

private object AndroidAmbientPlayer {
    private const val BASE_URL = "https://novelapp1.onrender.com/assets/audio"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: MediaPlayer? = null
    private var currentCue: AmbientCue? = null
    private var currentVolume: Float = 0.18f
    private var paused = false

    fun play(cue: AmbientCue?, volume: Float) {
        if (cue == null) {
            stop()
            return
        }
        currentVolume = volume.coerceIn(0f, 0.7f)
        if (cue == currentCue && player != null) {
            player?.setVolume(currentVolume, currentVolume)
            if (paused) resume()
            return
        }
        stop()
        currentCue = cue
        paused = false
        scope.launch {
            runCatching {
                val file = ensureTrack(cue)
                val nextPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    isLooping = true
                    setVolume(currentVolume, currentVolume)
                    prepare()
                    start()
                }
                player = nextPlayer
            }.onFailure {
                println("[Ambient] Android cue '${cue.id}' failed: ${it.message}")
                currentCue = null
            }
        }
    }

    fun pause() {
        paused = true
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume() {
        paused = false
        runCatching { player?.takeIf { !it.isPlaying }?.start() }
    }

    fun stop() {
        paused = false
        currentCue = null
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    private fun ensureTrack(cue: AmbientCue): File {
        val context = AppContextHolder.applicationContext
            ?: error("Android app context is unavailable for ambient audio.")
        val cacheFile = File(context.filesDir, "ambient/${cue.id}.wav")
        if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile
        cacheFile.parentFile?.mkdirs()

        runCatching {
            URL("$BASE_URL/${cue.id}.wav").openStream().use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile

        runCatching {
            context.assets.open("kokoro/ambient/${cue.id}.wav").use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return cacheFile
    }
}

private fun Any?.toFloatSamples(): FloatArray {
    return when (this) {
        is FloatArray -> this
        is Array<*> -> {
            val first = firstOrNull()
            when (first) {
                is FloatArray -> first
                is Array<*> -> first.firstOrNull() as? FloatArray ?: FloatArray(0)
                else -> FloatArray(0)
            }
        }
        else -> FloatArray(0)
    }
}

private fun FloatArray.toWavBytes(sampleRate: Int, volume: Float): ByteArray {
    val pcm = ByteArray(size * 2)
    forEachIndexed { index, sample ->
        val scaled = (sample * volume).coerceIn(-1f, 1f)
        val value = (scaled * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[index * 2] = (value and 0xff).toByte()
        pcm[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
    }
    return pcm16ToWav(pcm, sampleRate)
}

private fun pcm16ToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
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

private object AndroidKokoroEngine {
    private const val SAMPLE_RATE = 24_000
    private const val ASSET_ROOT = "kokoro"
    private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    private const val ALLOW_NETWORK_MODEL_DOWNLOAD = false
    private const val RETRY_MAX_WORDS = 32
    private const val RETRY_MAX_CHARS = 220
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmupStarted = AtomicBoolean(false)
    private var session: OrtSession? = null
    private val voiceCache = mutableMapOf<String, FloatArray>()
    private val dictionary: Map<String, String> by lazy {
        val context = AppContextHolder.applicationContext
            ?: error("Android app context is unavailable for Kokoro phonemizer.")
        context.assets.open("$ASSET_ROOT/phonemizer/cmudict.dict").bufferedReader().use { reader ->
            KokoroEnglishPhonemizer.parseCmuDictionary(reader.readText())
        }
    }

    fun synthesize(request: KokoroSynthesisRequest): KokoroSynthesisResult {
        val phonemization = KokoroEnglishPhonemizer.phonemizeToTokens(request.text, dictionary)
            ?: return synthesizeSegmented(request)
        return synthesizeSingle(request, phonemization)
    }

    private fun synthesizeSegmented(request: KokoroSynthesisRequest): KokoroSynthesisResult {
        val subSegments = request.text.splitForKokoroRetry()
        if (subSegments.size <= 1) {
            error("Kokoro phonemizer could not safely tokenize this segment under the 510 token limit.")
        }
        val rendered = subSegments.mapIndexed { index, text ->
            val childRequest = request.copy(
                text = text,
                segmentIndex = request.segmentIndex * 100 + index
            )
            val childPhonemization = KokoroEnglishPhonemizer.phonemizeToTokens(text, dictionary)
                ?: error("Kokoro phonemizer rejected sub-segment ${index + 1} of ${subSegments.size}.")
            synthesizeSingle(childRequest, childPhonemization)
        }
        val wav = combineKokoroWavSegments(rendered)
            ?: error("Kokoro could not join split audio segments.")
        println(
            "[Kokoro] Android ONNX joined segment=${request.segmentIndex} " +
                "subSegments=${subSegments.size} wavBytes=${wav.size}"
        )
        return KokoroSynthesisResult(
            audioBytes = wav,
            durationMs = rendered.sumOf { it.durationMs }.coerceAtLeast(300L),
            sampleRate = SAMPLE_RATE,
            engineName = "Kokoro ONNX Android"
        )
    }

    private fun synthesizeSingle(
        request: KokoroSynthesisRequest,
        phonemization: KokoroPhonemization
    ): KokoroSynthesisResult {
        val assetDir = ensureAssets()
        val activeSession = synchronized(this) {
            session ?: createSession(File(assetDir, "model_quantized.onnx")).also {
                session = it
            }
        }
        val paddedTokens = LongArray(phonemization.tokenIds.size + 2)
        paddedTokens[0] = 0L
        phonemization.tokenIds.forEachIndexed { index, token -> paddedTokens[index + 1] = token.toLong() }
        paddedTokens[paddedTokens.lastIndex] = 0L
        val style = voiceStyle(assetDir, request.voiceId, phonemization.tokenIds.size)

        OnnxTensor.createTensor(environment, arrayOf(paddedTokens)).use { inputIds ->
            OnnxTensor.createTensor(environment, arrayOf(style)).use { styleTensor ->
                OnnxTensor.createTensor(environment, floatArrayOf(request.speed)).use { speedTensor ->
                    activeSession.run(
                        mapOf(
                            "input_ids" to inputIds,
                            "style" to styleTensor,
                            "speed" to speedTensor
                        )
                    ).use { result ->
                        val samples = result[0].value.toFloatSamples()
                        if (samples.isEmpty()) error("Kokoro returned an empty waveform.")
                        val wav = samples.toWavBytes(SAMPLE_RATE, request.narratorVolume)
                        val durationMs = max(300L, samples.size * 1000L / SAMPLE_RATE)
                        return KokoroSynthesisResult(
                            audioBytes = wav,
                            durationMs = durationMs,
                            sampleRate = SAMPLE_RATE,
                            engineName = "Kokoro ONNX Android"
                        )
                    }
                }
            }
        }
    }

    fun isReadyForImmediateUse(): Boolean =
        session != null

    @Volatile
    private var warmupCompletable: CompletableDeferred<Unit>? = null

    /** Blocking call that waits for warmup (model load + session create) to finish. */
    suspend fun awaitReady() {
        warmupCompletable?.await()
    }

    fun prepareInBackground(force: Boolean = false) {
        val completable = CompletableDeferred<Unit>()
        synchronized(this) { warmupCompletable = completable }
        if (!force && !warmupStarted.compareAndSet(false, true)) {
            synchronized(this) { warmupCompletable = null }
            completable.complete(Unit)
            return
        }
        warmupScope.launch {
            runCatching {
                val assetDir = ensureAssets()
                if (session == null) {
                    session = createSession(File(assetDir, "model_quantized.onnx"))
                }
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Ready,
                    message = "Kokoro voice is ready on this device."
                )
                completable.complete(Unit)
            }.onFailure { error ->
                warmupStarted.set(false)
                completable.complete(Unit) // Unblock awaitReady anyway so it falls through to first-synthesis attempt
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Error,
                    message = "Kokoro warm-up failed: ${error.message ?: "unknown error"}"
                )
                println("[Kokoro] Android warm-up failed: $error")
            }
        }
    }

    private fun createSession(modelFile: File): OrtSession {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            error("Kokoro model is missing from local device storage.")
        }
        println("[Kokoro] Creating ONNX session modelPath=${modelFile.absolutePath} modelBytes=${modelFile.length()}")
        val nnapiOptions = sessionOptions(enableNnapi = true)
        return runCatching {
            environment.createSession(modelFile.absolutePath, nnapiOptions)
        }.getOrElse { nnapiError ->
            println("[Kokoro] NNAPI session failed: ${nnapiError.message}; retrying CPU session.")
            val cpuOptions = sessionOptions(enableNnapi = false)
            runCatching {
                environment.createSession(modelFile.absolutePath, cpuOptions)
            }.getOrElse { cpuError ->
                error(
                    "Kokoro ONNX session failed. modelPath=${modelFile.absolutePath} " +
                        "modelBytes=${modelFile.length()} nnapi=${nnapiError.message} cpu=${cpuError.message}"
                )
            }
        }
    }

    /**
     * Detects if the app is running inside a Waydroid (or similar LXC Android container)
     * environment. Waydroid leaves distinct fingerprints in Build properties.
     *
     * Used to apply a safe thread configuration that avoids the division-by-zero FPE crash
     * triggered when ONNX tries to dynamically scale thread pools using container CPU info
     * that may report 0 online cores.
     */
    private fun isRunningInWaydroid(): Boolean {
        val board = android.os.Build.BOARD?.lowercase() ?: ""
        val brand = android.os.Build.BRAND?.lowercase() ?: ""
        val device = android.os.Build.DEVICE?.lowercase() ?: ""
        val fingerprint = android.os.Build.FINGERPRINT?.lowercase() ?: ""
        val hardware = android.os.Build.HARDWARE?.lowercase() ?: ""
        val manufacturer = android.os.Build.MANUFACTURER?.lowercase() ?: ""
        val model = android.os.Build.MODEL?.lowercase() ?: ""
        val product = android.os.Build.PRODUCT?.lowercase() ?: ""

        val waydroidMarkers = listOf("waydroid", "lineage_waydroid", "x86_64")
        return waydroidMarkers.any { marker ->
            board.contains(marker) ||
            brand.contains(marker) ||
            device.contains(marker) ||
            fingerprint.contains(marker) ||
            hardware.contains(marker) ||
            manufacturer.contains(marker) ||
            model.contains(marker) ||
            product.contains(marker)
        } || (
            // Secondary heuristic: Waydroid often runs x86_64 on an ARM host
            // while reporting a suspicious hardware/board combination
            android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") } &&
            manufacturer.isBlank()
        )
    }

    private fun sessionOptions(enableNnapi: Boolean): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()

        if (isRunningInWaydroid()) {
            // ── Waydroid / LXC container safe mode ────────────────────────
            // ONNX reads 0 online cores from /sys inside the container and
            // divides its thread pool by that number → FPE_INTDIV crash.
            // Force exactly 1 thread and BASIC_OPT to bypass this entirely.
            println("[Kokoro] Waydroid environment detected — applying safe 1-thread BASIC_OPT session config.")
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        } else {
            // ── Normal physical Android device ─────────────────────────────
            // Let ONNX auto-scale threads to available CPU cores (0 = auto).
            options.setIntraOpNumThreads(0)
            options.setInterOpNumThreads(0)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        if (enableNnapi && !isRunningInWaydroid()) {
            runCatching {
                options.javaClass.methods
                    .firstOrNull { it.name == "addNnapi" && it.parameterTypes.isEmpty() }
                    ?.invoke(options)
            }.onFailure {
                println("[Kokoro] NNAPI option unavailable: ${it.message}")
            }
        }
        return options
    }

    private fun ensureAssets(): File {
        val context = AppContextHolder.applicationContext
            ?: error("Android app context is unavailable for Kokoro assets.")
        val targetDir = File(context.filesDir, "kokoro-v1").apply { mkdirs() }
        val modelFile = File(targetDir, "model_quantized.onnx")
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Checking,
            message = "Checking Kokoro voice model on this device."
        )
        if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
            copyBundledModel(modelFile)
        }
        if (ALLOW_NETWORK_MODEL_DOWNLOAD && (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES)) {
            val manifest = fetchManifest()
            if (!modelFile.exists() || modelFile.length() != manifest.sizeBytes || !modelFile.matchesSha256(manifest.sha256)) {
                downloadModel(targetDir, manifest, modelFile)
            }
        }
        if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
            error("Bundled Kokoro model is missing or incomplete.")
        }
        val required = listOf(
            "voices/af_heart.bin",
            "voices/af_sarah.bin",
            "voices/af_bella.bin",
            "voices/af_sky.bin",
            "voices/am_adam.bin",
            "voices/am_michael.bin",
            "voices/am_fenrir.bin",
            "voices/bf_emma.bin",
            "voices/bm_george.bin"
        )
        for (name in required) {
            val target = File(targetDir, name)
            if (target.exists() && target.length() > 0L) continue
            target.parentFile?.mkdirs()
            context.assets.open("$ASSET_ROOT/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Ready,
            message = "Kokoro voice is ready on this device."
        )
        return targetDir
    }

    private fun copyBundledModel(modelFile: File) {
        val context = AppContextHolder.applicationContext
            ?: error("Android app context is unavailable for Kokoro assets.")
        runCatching {
            KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                phase = KokoroVoiceSetupPhase.Installing,
                message = "Installing bundled Kokoro voice model."
            )
            modelFile.parentFile?.mkdirs()
            val tmpFile = File(modelFile.parentFile, "${modelFile.name}.asset")
            if (tmpFile.exists()) tmpFile.delete()
            context.assets.open("$ASSET_ROOT/model_quantized.onnx").use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmpFile.length() < MIN_MODEL_BYTES) {
                tmpFile.delete()
                error("bundled Kokoro model is incomplete")
            }
            if (modelFile.exists()) modelFile.delete()
            if (!tmpFile.renameTo(modelFile)) {
                tmpFile.delete()
                error("bundled Kokoro model could not be installed")
            }
        }
    }

    private fun fetchManifest(): KokoroModelManifest {
        val connection = (URL(AppReleaseConfig.KOKORO_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("model manifest returned HTTP ${connection.responseCode}")
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val model = json.getJSONObject("model")
            KokoroModelManifest(
                version = json.optString("version", "kokoro-82m-v1"),
                url = model.getString("url"),
                sizeBytes = model.getLong("sizeBytes"),
                sha256 = model.getString("sha256").lowercase()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadModel(targetDir: File, manifest: KokoroModelManifest, modelFile: File) {
        modelFile.parentFile?.mkdirs()
        val tempFile = File(targetDir, "${manifest.version}-${modelFile.name}.download")
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < 3) {
            attempt++
            runCatching {
                installCompleteTempModelIfValid(tempFile, modelFile, manifest)?.let { return }
                if (tempFile.exists() && tempFile.length() > manifest.sizeBytes) {
                    tempFile.delete()
                }
                val existingBytes = tempFile.takeIf { it.exists() && it.length() in 1 until manifest.sizeBytes }?.length() ?: 0L
                val connection = (URL(manifest.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    instanceFollowRedirects = true
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }
                try {
                    val responseCode = connection.responseCode
                    if (responseCode == 416) {
                        installCompleteTempModelIfValid(tempFile, modelFile, manifest)?.let { return }
                    }
                    val appending = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
                    if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                        error("model download returned HTTP $responseCode")
                    }
                    if (existingBytes > 0L && !appending) {
                        tempFile.delete()
                    }
                    val startingBytes = if (appending) existingBytes else 0L
                    KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                        phase = KokoroVoiceSetupPhase.Downloading,
                        downloadedBytes = startingBytes,
                        totalBytes = manifest.sizeBytes,
                        message = "Downloading Kokoro voice model. This only happens once."
                    )
                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile, appending).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            var downloaded = startingBytes
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                                    phase = KokoroVoiceSetupPhase.Downloading,
                                    downloadedBytes = downloaded.coerceAtMost(manifest.sizeBytes),
                                    totalBytes = manifest.sizeBytes,
                                    message = "Downloading Kokoro voice model. This only happens once."
                                )
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                if (tempFile.length() != manifest.sizeBytes) {
                    error("Downloaded Kokoro model is ${tempFile.length()} bytes, expected ${manifest.sizeBytes}.")
                }
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Installing,
                    downloadedBytes = tempFile.length(),
                    totalBytes = manifest.sizeBytes,
                    message = "Verifying Kokoro voice model."
                )
                if (!tempFile.matchesSha256(manifest.sha256)) {
                    tempFile.delete()
                    error("Downloaded Kokoro model checksum did not match.")
                }
                if (modelFile.exists()) modelFile.delete()
                if (!tempFile.renameTo(modelFile)) {
                    tempFile.copyTo(modelFile, overwrite = true)
                    tempFile.delete()
                }
                return
            }.onFailure { error ->
                lastError = error
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Error,
                    downloadedBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L,
                    totalBytes = manifest.sizeBytes,
                    message = "Kokoro download attempt $attempt failed: ${error.message ?: "unknown error"}"
                )
                Thread.sleep(700L * attempt)
            }
        }
        error("Kokoro model download failed: ${lastError?.message ?: "unknown error"}")
    }

    private fun installCompleteTempModelIfValid(
        tempFile: File,
        modelFile: File,
        manifest: KokoroModelManifest
    ): Unit? {
        if (!tempFile.exists() || tempFile.length() != manifest.sizeBytes) return null
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Installing,
            downloadedBytes = tempFile.length(),
            totalBytes = manifest.sizeBytes,
            message = "Verifying completed Kokoro voice model."
        )
        if (!tempFile.matchesSha256(manifest.sha256)) {
            tempFile.delete()
            return null
        }
        if (modelFile.exists()) modelFile.delete()
        if (!tempFile.renameTo(modelFile)) {
            tempFile.copyTo(modelFile, overwrite = true)
            tempFile.delete()
        }
        return Unit
    }

    private fun File.matchesSha256(expected: String): Boolean =
        expected == "skip" || runCatching { sha256() == expected.lowercase() }.getOrDefault(false)

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var voiceCacheLogged = false

    private fun voiceStyle(assetDir: File, voiceId: String, tokenCount: Int): FloatArray {
        val voice = voiceCache.getOrPut(voiceId) {
            val file = File(assetDir, "voices/$voiceId.bin")
            if (!file.exists() || file.length() == 0L) {
                error("Kokoro voice '$voiceId' is missing from bundled assets.")
            }
            val bytes = file.readBytes()
            if (bytes.size % 4 != 0) {
                error("Kokoro voice '$voiceId' file size ${bytes.size} is not aligned to 4 bytes.")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4) { buffer.float }
            if (floats.size % 256 != 0) {
                error("Kokoro voice '$voiceId' has ${floats.size} floats, not divisible by 256 (frameSize).")
            }
            if (!voiceCacheLogged) {
                println("[Kokoro] Loaded voice '$voiceId': ${floats.size} floats, ${floats.size / 256} frames")
                voiceCacheLogged = true
            }
            floats
        }
        val frameCount = voice.size / 256
        val frame = tokenCount.coerceIn(0, frameCount - 1)
        return voice.copyOfRange(frame * 256, frame * 256 + 256)
    }

    fun diagnosticsFor(request: KokoroSynthesisRequest, error: Throwable): String {
        val context = AppContextHolder.applicationContext
        val assetDir = context?.filesDir?.let { File(it, "kokoro-v1") }
        val modelFile = assetDir?.let { File(it, "model_quantized.onnx") }
        val voiceFile = assetDir?.let { File(it, "voices/${request.voiceId}.bin") }
        return "modelPath=${modelFile?.absolutePath ?: "unavailable"} " +
            "modelBytes=${modelFile?.takeIf { it.exists() }?.length() ?: 0L} " +
            "voice=${request.voiceId} " +
            "voiceBytes=${voiceFile?.takeIf { it.exists() }?.length() ?: 0L} " +
            "sessionReady=${session != null} " +
            "error=${error.javaClass.simpleName}:${error.message ?: "unknown"}"
    }
}

private fun String.splitForKokoroRetry(): List<String> {
    val clean = replace(Regex("""https?://\S+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (clean.isBlank()) return emptyList()
    return clean.split(Regex("""(?<=[,.;:!?。！？…])\s+"""))
        .flatMap { clause -> clause.splitByWordAndCharLimits(32, 220) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun String.splitByWordAndCharLimits(maxWords: Int, maxChars: Int): List<String> {
    val words = split(Regex("""\s+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return chunked(maxChars).filter { it.isNotBlank() }

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentWords = 0

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            chunks += current.toString().trim()
            current.clear()
            currentWords = 0
        }
    }

    for (word in words) {
        val wouldExceedWords = currentWords >= maxWords
        val wouldExceedChars = current.isNotEmpty() && current.length + 1 + word.length > maxChars
        if (wouldExceedWords || wouldExceedChars) flushCurrent()

        if (word.length > maxChars) {
            flushCurrent()
            chunks.addAll(word.chunked(maxChars))
            continue
        }

        if (current.isNotEmpty()) current.append(' ')
        current.append(word)
        currentWords += 1
    }
    flushCurrent()
    return chunks
}

actual fun warmupKokoroEngine() {
    AndroidKokoroEngine.prepareInBackground()
}

private fun combineKokoroWavSegments(results: List<KokoroSynthesisResult>): ByteArray? {
    val usable = results.filter { it.audioBytes.size > 44 && it.sampleRate > 0 }
    if (usable.isEmpty()) return null
    val sampleRate = usable.first().sampleRate
    val pcmSize = usable.sumOf { (it.audioBytes.size - 44).coerceAtLeast(0) }
    if (pcmSize <= 0) return null
    val pcm = ByteArray(pcmSize)
    var offset = 0
    usable.forEach { result ->
        val length = (result.audioBytes.size - 44).coerceAtLeast(0)
        if (length > 0) {
            result.audioBytes.copyInto(
                destination = pcm,
                destinationOffset = offset,
                startIndex = 44,
                endIndex = result.audioBytes.size
            )
            offset += length
        }
    }
    return pcm16ToWav(pcm, sampleRate)
}

private data class KokoroModelManifest(
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)
