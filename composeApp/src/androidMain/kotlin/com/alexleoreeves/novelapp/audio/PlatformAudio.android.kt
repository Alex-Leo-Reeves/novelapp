package com.alexleoreeves.novelapp.audio

import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android implementation: decodes base64 PCM/MP3 audio from Gemini
 * and plays it via MediaPlayer, suspending until playback completes.
 */
actual suspend fun platformPlayAudio(base64AudioData: String) {
    try {
        val audioBytes = Base64.decode(base64AudioData, Base64.DEFAULT)
        val tempFile = File.createTempFile("gemini_audio", ".mp3")
        FileOutputStream(tempFile).use { it.write(audioBytes) }

        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            try {
                player.setDataSource(tempFile.absolutePath)
                player.prepare()
                player.setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                    cont.resume(Unit)
                }
                player.setOnErrorListener { _, _, _ ->
                    player.release()
                    tempFile.delete()
                    cont.resumeWithException(Exception("MediaPlayer error"))
                    true
                }
                player.start()
                cont.invokeOnCancellation {
                    player.stop()
                    player.release()
                    tempFile.delete()
                }
            } catch (e: Exception) {
                player.release()
                tempFile.delete()
                cont.resumeWithException(e)
            }
        }
    } catch (e: Exception) {
        println("[Audio] Android playback error: ${e.message}")
    }
}
