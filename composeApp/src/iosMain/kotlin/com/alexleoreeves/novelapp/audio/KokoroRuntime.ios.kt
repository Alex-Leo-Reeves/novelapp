@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.audio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.usePinned
import onnxruntime.NovelOrtCreateCpuMemoryInfo
import onnxruntime.NovelOrtCreateEnv
import onnxruntime.NovelOrtCreateSession
import onnxruntime.NovelOrtCreateSessionOptions
import onnxruntime.NovelOrtCreateTensor
import onnxruntime.NovelOrtGetTensorData
import onnxruntime.NovelOrtReleaseSession
import onnxruntime.NovelOrtReleaseSessionOptions
import onnxruntime.NovelOrtReleaseValue
import onnxruntime.NovelOrtRun
import onnxruntime.NovelOrtSetIntraOpNumThreads
import onnxruntime.NovelOrtSetSessionGraphOptimizationLevel
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.AVFAudio.AVAudioPlayer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSData
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.math.max


private var iosAmbientPlayer: AVAudioPlayer? = null
private var iosAmbientCue: AmbientCue? = null

actual suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult =
    withContext(Dispatchers.Default) {
        runCatching { IosKokoroEngine.synthesize(request) }
            .onFailure { error ->
                println("Kokoro iOS ONNX Engine failed: ${error.message}")
                KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
                    phase = KokoroVoiceSetupPhase.Error,
                    message = "Kokoro setup failed: ${error.message ?: "unknown error"}"
                )
            }
            .getOrThrow()
    }


actual suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest) {
    if (result.audioBytes.isNotEmpty()) {
        platformPlayAudio(result.audioBytes)
        return
    }
}

actual fun stopKokoroAudio() {
    stopPlatformNarrationAudio()
}

actual fun pauseKokoroAudio() {
    pausePlatformNarrationAudio()
}

actual fun resumeKokoroAudio() {
    resumePlatformNarrationAudio()
}

actual fun playAmbientCue(cue: AmbientCue?, volume: Float) {
    if (cue == null) {
        stopAmbientCue()
        return
    }
    if (cue == iosAmbientCue && iosAmbientPlayer != null) {
        iosAmbientPlayer?.volume = volume.coerceIn(0f, 0.7f)
        iosAmbientPlayer?.play()
        return
    }
    stopAmbientCue()
    val path = NSBundle.mainBundle.pathForResource(
        name = cue.id,
        ofType = "wav",
        inDirectory = "kokoro/ambient"
    ) ?: return
    val player = AVAudioPlayer(
        contentsOfURL = NSURL.fileURLWithPath(path),
        error = null
    )
    player.numberOfLoops = -1
    player.volume = volume.coerceIn(0f, 0.7f)
    player.prepareToPlay()
    player.play()
    iosAmbientPlayer = player
    iosAmbientCue = cue
}

actual fun pauseAmbientCue() {
    iosAmbientPlayer?.pause()
}

actual fun resumeAmbientCue() {
    iosAmbientPlayer?.play()
}

actual fun stopAmbientCue() {
    iosAmbientPlayer?.stop()
    iosAmbientPlayer = null
    iosAmbientCue = null
}

private object IosKokoroEngine {
    private const val SAMPLE_RATE = 24_000
    private const val MODEL_NAME = "model_quantized.onnx"
    private const val ASSET_ROOT = "kokoro"

    private var env: COpaquePointer? = null
    private var session: COpaquePointer? = null
    private var memoryInfo: COpaquePointer? = null
    private val voiceCache = mutableMapOf<String, FloatArray>()
    private val dictionary: Map<String, String> by lazy {
        val bytes = readBundledAsset("phonemizer/cmudict.dict")
            ?: error("Kokoro CMU phonemizer dictionary is missing from the iOS bundle.")
        KokoroEnglishPhonemizer.parseCmuDictionary(bytes.decodeToString())
    }

    fun synthesize(request: KokoroSynthesisRequest): KokoroSynthesisResult {
        val phonemization = KokoroEnglishPhonemizer.phonemizeToTokens(request.text, dictionary)
            ?: error("Kokoro phonemizer could not safely tokenize this segment.")
        val assetDir = ensureAssets()
        val activeSession = ensureSession("$assetDir/$MODEL_NAME")
        val activeMemoryInfo = memoryInfo ?: error("Kokoro ONNX memory info was not initialized.")

        val paddedTokens = LongArray(phonemization.tokenIds.size + 2)
        paddedTokens[0] = 0L
        phonemization.tokenIds.forEachIndexed { index, token -> paddedTokens[index + 1] = token.toLong() }
        paddedTokens[paddedTokens.lastIndex] = 0L
        val style = voiceStyle(request.voiceId, phonemization.tokenIds.size)
        val speed = floatArrayOf(request.speed)

        val samples = runSession(activeSession, activeMemoryInfo, paddedTokens, style, speed)
        if (samples.isEmpty()) error("Kokoro returned an empty waveform.")
        val wav = samples.toWavBytes(SAMPLE_RATE, request.narratorVolume)
        val durationMs = max(300L, samples.size * 1000L / SAMPLE_RATE)
        return KokoroSynthesisResult(
            audioBytes = wav,
            durationMs = durationMs,
            sampleRate = SAMPLE_RATE,
            engineName = "Kokoro ONNX iOS"
        )
    }

    private fun ensureSession(modelPath: String): COpaquePointer = memScoped {
        session?.let { return it }
        fun check(message: kotlinx.cinterop.CPointer<ByteVar>?) {
            if (message != null) error(message.toKString())
        }

        val envPtr = alloc<COpaquePointerVar>()
        if (env == null) {
            check(NovelOrtCreateEnv(envPtr.ptr))
            env = envPtr.ptr[0] ?: error("ONNX Runtime did not return an environment.")
        }

        val optionsPtr = alloc<COpaquePointerVar>()
        check(NovelOrtCreateSessionOptions(optionsPtr.ptr))
        val options = optionsPtr.ptr[0] ?: error("ONNX Runtime did not return session options.")
        try {
            check(NovelOrtSetIntraOpNumThreads(options, 2))
            check(NovelOrtSetSessionGraphOptimizationLevel(options, 99))

            val activeEnv = env ?: error("ONNX Runtime environment was not initialized.")
            val sessionPtr = alloc<COpaquePointerVar>()
            check(NovelOrtCreateSession(activeEnv, modelPath, options, sessionPtr.ptr))
            session = sessionPtr.ptr[0] ?: error("ONNX Runtime did not return a session.")

            val memoryInfoPtr = alloc<COpaquePointerVar>()
            check(NovelOrtCreateCpuMemoryInfo(memoryInfoPtr.ptr))
            memoryInfo = memoryInfoPtr.ptr[0] ?: error("ONNX Runtime did not return CPU memory info.")
        } finally {
            NovelOrtReleaseSessionOptions(options)
        }

        session ?: error("Kokoro ONNX session was not initialized.")
    }

    private fun runSession(
        activeSession: COpaquePointer,
        activeMemoryInfo: COpaquePointer,
        tokens: LongArray,
        style: FloatArray,
        speed: FloatArray
    ): FloatArray = memScoped {
        fun check(message: kotlinx.cinterop.CPointer<ByteVar>?) {
            if (message != null) error(message.toKString())
        }

        val tokenShape = longArrayOf(1L, tokens.size.toLong())
        val styleShape = longArrayOf(1L, 256L)
        val speedShape = longArrayOf(1L)
        val tokenValue = alloc<COpaquePointerVar>()
        val styleValue = alloc<COpaquePointerVar>()
        val speedValue = alloc<COpaquePointerVar>()
        val outputValue = allocArray<COpaquePointerVar>(1)

        tokens.usePinned { pinnedTokens ->
            style.usePinned { pinnedStyle ->
                speed.usePinned { pinnedSpeed ->
                    tokenShape.usePinned { pinnedTokenShape ->
                        styleShape.usePinned { pinnedStyleShape ->
                            speedShape.usePinned { pinnedSpeedShape ->
                                check(
                                    NovelOrtCreateTensor(
                                        activeMemoryInfo,
                                        pinnedTokens.addressOf(0).reinterpret<ByteVar>(),
                                        (tokens.size * sizeOf<LongVar>()).convert(),
                                        pinnedTokenShape.addressOf(0),
                                        tokenShape.size.convert(),
                                        7,
                                        tokenValue.ptr
                                    )
                                )
                                check(
                                    NovelOrtCreateTensor(
                                        activeMemoryInfo,
                                        pinnedStyle.addressOf(0).reinterpret<ByteVar>(),
                                        (style.size * sizeOf<FloatVar>()).convert(),
                                        pinnedStyleShape.addressOf(0),
                                        styleShape.size.convert(),
                                        1,
                                        styleValue.ptr
                                    )
                                )
                                check(
                                    NovelOrtCreateTensor(
                                        activeMemoryInfo,
                                        pinnedSpeed.addressOf(0).reinterpret<ByteVar>(),
                                        (speed.size * sizeOf<FloatVar>()).convert(),
                                        pinnedSpeedShape.addressOf(0),
                                        speedShape.size.convert(),
                                        1,
                                        speedValue.ptr
                                    )
                                )

                                val inputNames = allocArray<CPointerVar<ByteVar>>(3)
                                inputNames[0] = "input_ids".cstr.getPointer(this)
                                inputNames[1] = "style".cstr.getPointer(this)
                                inputNames[2] = "speed".cstr.getPointer(this)
                                val outputNames = allocArray<CPointerVar<ByteVar>>(1)
                                outputNames[0] = "waveform".cstr.getPointer(this)

                                val inputValues = allocArray<COpaquePointerVar>(3)
                                inputValues[0] = tokenValue.ptr[0]
                                inputValues[1] = styleValue.ptr[0]
                                inputValues[2] = speedValue.ptr[0]

                                check(
                                    NovelOrtRun(
                                        activeSession,
                                        inputNames,
                                        inputValues,
                                        3.convert(),
                                        outputNames,
                                        1.convert(),
                                        outputValue
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        try {
            val output = outputValue[0] ?: error("ONNX Runtime did not return a waveform tensor.")
            val elementCount = alloc<ULongVar>()
            val outputData = alloc<COpaquePointerVar>()
            check(NovelOrtGetTensorData(output, outputData.ptr, elementCount.ptr))
            val sampleCount = elementCount.ptr[0].toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (sampleCount <= 0) return FloatArray(0)
            val floatPointer = outputData.ptr[0]?.reinterpret<FloatVar>()
                ?: error("ONNX Runtime waveform tensor had no data pointer.")
            FloatArray(sampleCount) { index -> floatPointer[index] }
        } finally {
            outputValue[0]?.let { NovelOrtReleaseValue(it) }
            tokenValue.ptr[0]?.let { NovelOrtReleaseValue(it) }
            styleValue.ptr[0]?.let { NovelOrtReleaseValue(it) }
            speedValue.ptr[0]?.let { NovelOrtReleaseValue(it) }
        }
    }

    private fun ensureAssets(): String {
        val targetDir = "${documentsDirectory()}/kokoro-v1"
        ensureDirectory(targetDir)
        val modelPath = "$targetDir/$MODEL_NAME"
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Checking,
            message = "Checking Kokoro voice model on this device."
        )
        val manifest = fetchManifest()
        val existingSize = fileSize(modelPath)
        if (existingSize != manifest.sizeBytes || existingSize <= 0L) {
            downloadModel(manifest, modelPath)
            session?.let {
                NovelOrtReleaseSession(it)
                session = null
            }
        }
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Ready,
            downloadedBytes = fileSize(modelPath).coerceAtLeast(0L),
            totalBytes = manifest.sizeBytes,
            message = "Kokoro voice is ready on this device."
        )
        return targetDir
    }

    private fun fetchManifest(): KokoroModelManifest {
        val raw = downloadBytes(AppReleaseConfig.KOKORO_MANIFEST_URL).decodeToString()
        val json = Json.parseToJsonElement(raw).jsonObject
        val model = json["model"]?.jsonObject ?: error("Kokoro manifest is missing model information.")
        return KokoroModelManifest(
            version = json["version"]?.jsonPrimitive?.content ?: "kokoro-82m-v1",
            url = model["url"]?.jsonPrimitive?.content ?: error("Kokoro manifest is missing model URL."),
            sizeBytes = model["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            sha256 = model["sha256"]?.jsonPrimitive?.content?.lowercase().orEmpty()
        )
    }

    private fun downloadModel(manifest: KokoroModelManifest, modelPath: String) {
        require(manifest.sizeBytes > 0L) { "Kokoro manifest does not include a valid model size." }
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Downloading,
            downloadedBytes = 0L,
            totalBytes = manifest.sizeBytes,
            message = "Downloading Kokoro voice model. This only happens once."
        )
        val bytes = downloadBytes(manifest.url)
        if (bytes.size.toLong() != manifest.sizeBytes) {
            error("Downloaded Kokoro model is ${bytes.size} bytes, expected ${manifest.sizeBytes}.")
        }
        KokoroVoiceSetup.status.value = KokoroVoiceSetupStatus(
            phase = KokoroVoiceSetupPhase.Installing,
            downloadedBytes = bytes.size.toLong(),
            totalBytes = manifest.sizeBytes,
            message = "Installing Kokoro voice model."
        )
        writeBytes(modelPath, bytes)
    }

    private fun voiceStyle(voiceId: String, tokenCount: Int): FloatArray {
        val voice = voiceCache.getOrPut(voiceId) {
            val bytes = readBundledAsset("voices/$voiceId.bin")
                ?: error("Kokoro voice '$voiceId' is missing from the iOS bundle.")
            bytes.toLittleEndianFloatArray()
        }
        val frameCount = voice.size / 256
        val frame = tokenCount.coerceIn(0, frameCount - 1)
        return voice.copyOfRange(frame * 256, frame * 256 + 256)
    }

    private fun readBundledAsset(relativePath: String): ByteArray? {
        val directory = "$ASSET_ROOT/${relativePath.substringBeforeLast("/", missingDelimiterValue = "")}"
            .trimEnd('/')
        val fileName = relativePath.substringAfterLast("/")
        val name = fileName.substringBeforeLast(".", fileName)
        val ext = fileName.substringAfterLast(".", "")
        val path = NSBundle.mainBundle.pathForResource(
            name = name,
            ofType = ext.ifBlank { null },
            inDirectory = directory.ifBlank { ASSET_ROOT }
        ) ?: return null
        return NSData.dataWithContentsOfFile(path)?.toByteArray()
    }

    private fun downloadBytes(url: String): ByteArray {
        val nsUrl = NSURL.URLWithString(url) ?: error("Invalid URL: $url")
        val data = NSData.dataWithContentsOfURL(nsUrl)
            ?: error("Unable to download $url")
        return data.toByteArray()
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        ensureDirectory(path.substringBeforeLast("/"))
        val data = bytes.toNSData() ?: error("Unable to create NSData for $path")
        if (!data.writeToFile(path, atomically = true)) {
            error("Unable to write $path")
        }
    }

    private fun fileSize(path: String): Long =
        NSData.dataWithContentsOfFile(path)?.length?.toLong() ?: -1L
}

private data class KokoroModelManifest(
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val source = this.bytes ?: return ByteArray(0)
    val target = ByteArray(length)
    target.usePinned { pinned ->
        memcpy(pinned.addressOf(0), source, length.convert())
    }
    return target
}

private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}

private fun ByteArray.toLittleEndianFloatArray(): FloatArray {
    val result = FloatArray(size / 4)
    for (index in result.indices) {
        val offset = index * 4
        val bits = (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
        result[index] = Float.fromBits(bits)
    }
    return result
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

private fun documentsDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: ""

private fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
}
