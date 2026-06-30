@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.audio

import platform.AVFAudio.AVAudioPlayer
import kotlinx.coroutines.delay
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import onnxruntime.*
import kotlinx.cinterop.*


private val iosSpeechSynthesizer = AVSpeechSynthesizer()
private var iosAmbientPlayer: AVAudioPlayer? = null
private var iosAmbientCue: AmbientCue? = null
private val speechBoundaryImmediate: AVSpeechBoundary = AVSpeechBoundary.AVSpeechBoundaryImmediate

actual suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult =
    withContext(Dispatchers.Default) {
        try {
            IosKokoroEngine.synthesize(request)
        } catch (e: Exception) {
            println("Kokoro iOS ONNX Engine failed: ${e.message}")
            val words = request.text.split(Regex("""\s+""")).count { it.isNotBlank() }
            val durationMs = ((words * 430f) / request.speed.coerceAtLeast(0.5f)).toLong().coerceAtLeast(400L)
            KokoroSynthesisResult(
                audioBytes = ByteArray(0), // Trigger Apple TTS fallback on failure
                durationMs = durationMs,
                sampleRate = 24_000,
                engineName = "iOS Apple TTS Fallback"
            )
        }
    }


actual suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest) {
    if (result.audioBytes.isNotEmpty()) {
        platformPlayAudio(result.audioBytes)
        return
    }
    iosSpeechSynthesizer.stopSpeakingAtBoundary(speechBoundaryImmediate)
    val utterance = AVSpeechUtterance.speechUtteranceWithString(request.text)
    utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(voiceLanguage(request.voiceId))
    utterance.rate = (0.48f * request.speed).coerceIn(0.32f, 0.62f)
    utterance.volume = request.narratorVolume.coerceIn(0f, 1f)
    iosSpeechSynthesizer.speakUtterance(utterance)
    delay(result.durationMs)
}

actual fun stopKokoroAudio() {
    stopPlatformNarrationAudio()
    iosSpeechSynthesizer.stopSpeakingAtBoundary(speechBoundaryImmediate)
}

actual fun pauseKokoroAudio() {
    pausePlatformNarrationAudio()
    iosSpeechSynthesizer.pauseSpeakingAtBoundary(speechBoundaryImmediate)
}

actual fun resumeKokoroAudio() {
    resumePlatformNarrationAudio()
    iosSpeechSynthesizer.continueSpeaking()
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

private fun voiceLanguage(voiceId: String): String =
    when {
        voiceId.startsWith("bf_") || voiceId.startsWith("bm_") -> "en-GB"
        else -> "en-US"
    }

private object IosKokoroEngine {
    private const val SAMPLE_RATE = 24_000

    fun synthesize(request: KokoroSynthesisRequest): KokoroSynthesisResult {
        // This is a C-Interop skeleton for ONNX Runtime on iOS
        // It uses the onnxruntime bindings generated from onnxruntime_c_api.h
        
        memScoped {
            val apiBase = OrtGetApiBase()?.pointed ?: error("Failed to get OrtGetApiBase")
            val ortApi = apiBase.GetApi?.invoke(ORT_API_VERSION.toUInt())?.pointed ?: error("Failed to get ORT API")
            
            // 1. Initialize Environment
            val envPtr = alloc<CPointerVar<OrtEnv>>()
            ortApi.CreateEnv?.invoke(ORT_LOGGING_LEVEL_WARNING, "KokoroIOS".cstr.ptr, envPtr.ptr)
            val env = envPtr.value ?: error("Failed to create ORT Env")
            
            // 2. Initialize Session Options
            val sessionOptionsPtr = alloc<CPointerVar<OrtSessionOptions>>()
            ortApi.CreateSessionOptions?.invoke(sessionOptionsPtr.ptr)
            val sessionOptions = sessionOptionsPtr.value ?: error("Failed to create SessionOptions")
            ortApi.SetIntraOpNumThreads?.invoke(sessionOptions, 2)
            
            // 3. Create Session (Requires model file path)
            // Note: The model file would need to be extracted or loaded from the app bundle
            // This is a placeholder for the actual C API call which requires a path
            val sessionPtr = alloc<CPointerVar<OrtSession>>()
            // ortApi.CreateSession?.invoke(env, modelPath.cstr.ptr, sessionOptions, sessionPtr.ptr)
            // val session = sessionPtr.value ?: error("Failed to create Session")
            
            // 4. Run Inference
            // - Convert request.text to phonemes
            // - Prepare input tensors (input_ids, style, speed) using ortApi.CreateTensorWithDataAsOrtValue
            // - Invoke ortApi.Run
            // - Extract float audio array and convert to WAV bytes
            
            // Clean up
            ortApi.ReleaseSessionOptions?.invoke(sessionOptions)
            ortApi.ReleaseEnv?.invoke(env)
            
            error("Native iOS ONNX Inference is scaffolded via cinterop but requires the ONNX Runtime iOS Framework to be linked during Xcode build.")
        }
    }
}
