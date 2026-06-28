package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlin.coroutines.resume

actual suspend fun platformPlayAudio(audioBytes: ByteArray) {
    runCatching {
        val audioInput = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioBytes))
        val clip = AudioSystem.getClip()

        suspendCancellableCoroutine { continuation ->
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP || event.type == LineEvent.Type.CLOSE) {
                    clip.close()
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }

            continuation.invokeOnCancellation {
                clip.stop()
                clip.close()
            }

            clip.open(audioInput)
            clip.start()
        }
    }.onFailure {
        println("[Audio] Desktop playback unavailable: ${it.message}")
    }
}
