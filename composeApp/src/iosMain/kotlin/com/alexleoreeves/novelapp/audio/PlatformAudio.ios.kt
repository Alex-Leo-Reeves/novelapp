package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.delay

/**
 * iOS implementation placeholder.
 * Full implementation would use AVAudioPlayer / AVFoundation via Kotlin/Native interop.
 * This stub prevents compile errors in this phase.
 */
actual suspend fun platformPlayAudio(base64AudioData: String) {
    // TODO: Implement using AVFoundation
    // val audioData = NSData.create(base64Encoded = base64AudioData)
    // val player = AVAudioPlayer(data = audioData)
    // player.play()
    // delay until done
    delay(100)
    println("[Audio] iOS audio stub — implement AVAudioPlayer here")
}
