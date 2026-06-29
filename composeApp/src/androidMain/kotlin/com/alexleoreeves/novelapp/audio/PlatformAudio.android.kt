package com.alexleoreeves.novelapp.audio

import android.media.MediaPlayer
import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android implementation: plays generated WAV audio bytes via MediaPlayer,
 * suspending until playback completes.
 */
actual suspend fun platformPlayAudio(audioBytes: ByteArray) {
    try {
        val tempFile = File.createTempFile("kokoro_audio", ".wav")
        FileOutputStream(tempFile).use { it.write(audioBytes) }
        AndroidGeneratedAudioPlayer.playFile(tempFile, deleteOnFinish = true)
    } catch (e: Exception) {
        println("[Audio] Android playback error: ${e.message}")
    }
}

actual suspend fun playKokoroAudioFile(filePath: String) {
    AndroidGeneratedAudioPlayer.playFile(File(filePath), deleteOnFinish = false)
}

internal fun stopPlatformNarrationAudio() = AndroidGeneratedAudioPlayer.stop()
internal fun pausePlatformNarrationAudio() = AndroidGeneratedAudioPlayer.pause()
internal fun resumePlatformNarrationAudio() = AndroidGeneratedAudioPlayer.resume()

actual fun clearTemporaryNarrationAudioCache() {
    AppContextHolder.applicationContext?.cacheDir
        ?.resolve("narration-audio-temp")
        ?.deleteRecursively()
}

actual fun existingNarrationAudioCachePath(cacheKey: String, persistent: Boolean): String? {
    val file = narrationAudioFile(cacheKey, persistent)
    return file.takeIf { it.exists() && it.length() > 44L }?.absolutePath
}

actual fun writeNarrationAudioCache(cacheKey: String, persistent: Boolean, audioBytes: ByteArray): String? {
    if (audioBytes.size <= 44) return null
    return runCatching {
        val file = narrationAudioFile(cacheKey, persistent)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(audioBytes)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        file.absolutePath
    }.getOrNull()
}

private object AndroidGeneratedAudioPlayer {
    private var activePlayer: MediaPlayer? = null

    suspend fun playFile(file: File, deleteOnFinish: Boolean) {
        if (!file.exists() || file.length() == 0L) return
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            stop()
            activePlayer = player
            try {
                player.setDataSource(file.absolutePath)
                player.prepare()
                player.setOnCompletionListener {
                    if (activePlayer === it) activePlayer = null
                    it.release()
                    if (deleteOnFinish) file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { current, _, _ ->
                    if (activePlayer === current) activePlayer = null
                    current.release()
                    if (deleteOnFinish) file.delete()
                    if (cont.isActive) cont.resumeWithException(Exception("MediaPlayer error"))
                    true
                }
                player.start()
                cont.invokeOnCancellation {
                    if (activePlayer === player) activePlayer = null
                    runCatching { player.stop() }
                    player.release()
                    if (deleteOnFinish) file.delete()
                }
            } catch (e: Exception) {
                if (activePlayer === player) activePlayer = null
                player.release()
                if (deleteOnFinish) file.delete()
                cont.resumeWithException(e)
            }
        }
    }

    fun stop() {
        val player = activePlayer ?: return
        activePlayer = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    fun pause() {
        runCatching { activePlayer?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume() {
        runCatching { activePlayer?.takeIf { !it.isPlaying }?.start() }
    }
}

private fun narrationAudioFile(cacheKey: String, persistent: Boolean): File {
    val context = AppContextHolder.applicationContext
        ?: error("Android app context is unavailable for narration cache.")
    val root = if (persistent) {
        File(context.filesDir, "narration-audio")
    } else {
        File(context.cacheDir, "narration-audio-temp")
    }
    return File(root, "${cacheKey.sha256()}.wav")
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(encodeToByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
