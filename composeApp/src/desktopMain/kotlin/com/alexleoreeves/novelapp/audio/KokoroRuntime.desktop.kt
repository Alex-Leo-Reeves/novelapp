package com.alexleoreeves.novelapp.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URL
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.max
import kotlin.math.log10

actual suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult =
    withContext(Dispatchers.IO) {
        runCatching { DesktopKokoroEngine.synthesize(request) }
            .getOrElse { error ->
                val diagnostics = "voice=${request.voiceId} error=${error.javaClass.simpleName}:${error.message ?: "unknown"}"
                println("[Kokoro] Desktop ONNX synthesis failed: $diagnostics")
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Error,
                    message = "Kokoro ONNX failed: ${error.message ?: "unknown error"}"
                )
                throw error
            }
    }

actual suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest) {
    if (result.audioBytes.isNotEmpty()) {
        platformPlayAudio(result.audioBytes)
    }
}

actual fun stopKokoroAudio() = stopPlatformNarrationAudio()
actual fun pauseKokoroAudio() = pausePlatformNarrationAudio()
actual fun resumeKokoroAudio() = resumePlatformNarrationAudio()
actual fun playAmbientCue(cue: AmbientCue?, volume: Float) = DesktopAmbientPlayer.play(cue, volume)
actual fun pauseAmbientCue() = DesktopAmbientPlayer.pause()
actual fun resumeAmbientCue() = DesktopAmbientPlayer.resume()
actual fun stopAmbientCue() = DesktopAmbientPlayer.stop()

private object DesktopAmbientPlayer {
    private const val BASE_URL = "https://novelapp1.onrender.com/assets/audio"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clip: Clip? = null
    private var currentCue: AmbientCue? = null
    private var currentVolume: Float = 0.18f
    private var pausedFrame = 0

    fun play(cue: AmbientCue?, volume: Float) {
        if (cue == null) {
            stop()
            return
        }
        currentVolume = volume.coerceIn(0f, 0.7f)
        if (cue == currentCue && clip != null) {
            applyVolume(clip, currentVolume)
            if (pausedFrame > 0) resume()
            return
        }
        stop()
        currentCue = cue
        scope.launch {
            runCatching {
                val file = ensureTrack(cue)
                val audioInput = AudioSystem.getAudioInputStream(file)
                val nextClip = AudioSystem.getClip().apply {
                    open(audioInput)
                    applyVolume(this, currentVolume)
                    loop(Clip.LOOP_CONTINUOUSLY)
                    start()
                }
                clip = nextClip
            }.onFailure {
                println("[Ambient] Desktop cue '${cue.id}' failed: ${it.message}")
                currentCue = null
            }
        }
    }

    fun pause() {
        val active = clip ?: return
        pausedFrame = active.framePosition
        active.stop()
    }

    fun resume() {
        val active = clip ?: return
        if (pausedFrame > 0) {
            active.framePosition = pausedFrame
            pausedFrame = 0
        }
        active.loop(Clip.LOOP_CONTINUOUSLY)
        active.start()
    }

    fun stop() {
        runCatching {
            clip?.stop()
            clip?.close()
        }
        clip = null
        currentCue = null
        pausedFrame = 0
    }

    private fun ensureTrack(cue: AmbientCue): File {
        val target = File(System.getProperty("user.home"), ".novelapp/ambient/${cue.id}.wav")
        if (target.exists() && target.length() > 0L) return target
        target.parentFile?.mkdirs()

        runCatching {
            URL("$BASE_URL/${cue.id}.wav").openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (target.exists() && target.length() > 0L) return target

        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("kokoro/ambient/${cue.id}.wav")
            ?: File("kokoro-assets/kokoro/ambient/${cue.id}.wav").takeIf { it.exists() }?.inputStream()
            ?: error("Bundled ambient cue '${cue.id}' is missing.")
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    private fun applyVolume(targetClip: Clip?, volume: Float) {
        val gain = targetClip?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }
            ?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
        val safeVolume = volume.coerceIn(0.001f, 1f)
        val db = (20f * log10(safeVolume)).coerceIn(gain.minimum, gain.maximum)
        gain.value = db
    }
}

private object DesktopKokoroEngine {
    private const val SAMPLE_RATE = 24_000
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val voiceCache = mutableMapOf<String, FloatArray>()
    private val dictionary: Map<String, String> by lazy {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("kokoro/phonemizer/cmudict.dict")
            ?: File("kokoro-assets/kokoro/phonemizer/cmudict.dict").takeIf { it.exists() }?.inputStream()
            ?: error("Kokoro CMU phonemizer dictionary is missing from desktop resources.")
        stream.bufferedReader().use { reader ->
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
                        val wav = samples.toWavBytes(SAMPLE_RATE, request.narratorVolume)
                        val durationMs = max(300L, samples.size * 1000L / SAMPLE_RATE)
                        return KokoroSynthesisResult(
                            audioBytes = wav,
                            durationMs = durationMs,
                            sampleRate = SAMPLE_RATE,
                            engineName = "Kokoro ONNX Desktop"
                        )
                    }
                }
            }
        }
    }

    private fun createSession(modelFile: File): OrtSession {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            error("Kokoro model is missing. Run scripts/download-kokoro-assets.sh so model_quantized.onnx is bundled.")
        }
        return environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    private fun ensureAssets(): File {
        val targetDir = File(System.getProperty("user.home"), ".novelapp/kokoro-v1").apply { mkdirs() }
        val required = listOf(
            "model_quantized.onnx",
            "tokenizer.json",
            "tokenizer_config.json",
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
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("kokoro/$name")
                ?: File("kokoro-assets/kokoro/$name").takeIf { it.exists() }?.inputStream()
                ?: error("Kokoro asset '$name' is missing from desktop resources.")
            stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        }
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
