package com.alexleoreeves.novelapp.audio

import platform.AVFAudio.AVAudioPlayer
import kotlinx.coroutines.delay
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSBundle
import platform.Foundation.NSURL

private val iosSpeechSynthesizer = AVSpeechSynthesizer()
private var iosAmbientPlayer: AVAudioPlayer? = null
private var iosAmbientCue: AmbientCue? = null

actual suspend fun synthesizeKokoroSpeech(request: KokoroSynthesisRequest): KokoroSynthesisResult {
    val words = request.text.split(Regex("""\s+""")).count { it.isNotBlank() }
    val durationMs = ((words * 430L) / request.speed.coerceAtLeast(0.5f)).coerceAtLeast(400L)
    return KokoroSynthesisResult(
        audioBytes = ByteArray(0),
        durationMs = durationMs,
        sampleRate = 24_000,
        engineName = "iOS on-device speech"
    )
}

actual suspend fun playKokoroAudio(result: KokoroSynthesisResult, request: KokoroSynthesisRequest) {
    iosSpeechSynthesizer.stopSpeakingAtBoundary(0u)
    val utterance = AVSpeechUtterance.speechUtteranceWithString(request.text)
    utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(voiceLanguage(request.voiceId))
    utterance.rate = (0.48f * request.speed).coerceIn(0.32f, 0.62f)
    utterance.volume = request.narratorVolume.coerceIn(0f, 1f)
    iosSpeechSynthesizer.speakUtterance(utterance)
    delay(result.durationMs)
}

actual fun stopKokoroAudio() {
    iosSpeechSynthesizer.stopSpeakingAtBoundary(0u)
}

actual fun pauseKokoroAudio() {
    iosSpeechSynthesizer.pauseSpeakingAtBoundary(0u)
}

actual fun resumeKokoroAudio() {
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
