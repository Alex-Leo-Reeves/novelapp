package com.alexleoreeves.novelapp.audio

import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

actual suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult =
    withContext(Dispatchers.IO) {
        runCatching { AndroidKokoroEngine.synthesize(request) }
            .getOrElse { error ->
                println("[Kokoro] Android ONNX unavailable for segment ${request.segmentIndex}: ${error.message}")
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Error,
                    message = "Kokoro setup failed: ${error.message ?: "unknown error"}"
                )
                throw error
            }
    }

actual suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest) {
    if (result.audioBytes.isNotEmpty()) {
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
            engineName = "Android on-device TTS"
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
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
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
            ?: error("Kokoro phonemizer could not safely tokenize this segment.")
        val assetDir = ensureAssets()
        val activeSession = session ?: createSession(File(assetDir, "model_quantized.onnx")).also { session = it }
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

    private fun createSession(modelFile: File): OrtSession {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            error("Kokoro model is missing from local device storage.")
        }
        val options = OrtSession.SessionOptions()
        runCatching {
            options.javaClass.methods
                .firstOrNull { it.name == "addNnapi" && it.parameterTypes.isEmpty() }
                ?.invoke(options)
        }
        return environment.createSession(modelFile.absolutePath, options)
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
        if (!modelFile.exists() || modelFile.length() == 0L) {
            KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                phase = KokoroVoiceSetupPhase.Installing,
                message = "Installing bundled Kokoro voice model."
            )
            context.assets.open("$ASSET_ROOT/model_quantized.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
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

    private fun voiceStyle(assetDir: File, voiceId: String, tokenCount: Int): FloatArray {
        val voice = voiceCache.getOrPut(voiceId) {
            val file = File(assetDir, "voices/$voiceId.bin")
            if (!file.exists() || file.length() == 0L) {
                error("Kokoro voice '$voiceId' is missing from bundled assets.")
            }
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 4) { buffer.float }
        }
        val frameCount = voice.size / 256
        val frame = tokenCount.coerceIn(0, frameCount - 1)
        return voice.copyOfRange(frame * 256, frame * 256 + 256)
    }
}
