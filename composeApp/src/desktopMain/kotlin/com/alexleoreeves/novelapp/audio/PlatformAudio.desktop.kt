package com.alexleoreeves.novelapp.audio

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlin.coroutines.resume

suspend fun platformPlayAudio(audioBytes: ByteArray) {
    runCatching {
        DesktopGeneratedAudioPlayer.playBytes(audioBytes)
    }.onFailure {
        println("[Audio] Desktop playback unavailable: ${it.message}")
    }
}

suspend fun playAudioFile(filePath: String) {
    DesktopGeneratedAudioPlayer.playFile(File(filePath))
}

internal fun stopPlatformNarrationAudio() = DesktopGeneratedAudioPlayer.stop()
internal fun pausePlatformNarrationAudio() = DesktopGeneratedAudioPlayer.pause()
internal fun resumePlatformNarrationAudio() = DesktopGeneratedAudioPlayer.resume()

fun clearTemporaryNarrationAudioCache() {
    narrationRoot(persistent = false).deleteRecursively()
}

fun existingNarrationAudioCachePath(cacheKey: String, persistent: Boolean): String? {
    val file = narrationAudioFile(cacheKey, persistent)
    return file.takeIf { it.exists() && it.length() > 44L }?.absolutePath
}

fun writeNarrationAudioCache(cacheKey: String, persistent: Boolean, audioBytes: ByteArray): String? {
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

private object DesktopGeneratedAudioPlayer {
    private var activeClip: Clip? = null

    suspend fun playBytes(audioBytes: ByteArray) {
        val input = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioBytes))
        playClip(AudioSystem.getClip().apply { open(input) })
    }

    suspend fun playFile(file: File) {
        if (!file.exists() || file.length() == 0L) return
        val input = AudioSystem.getAudioInputStream(file)
        try {
            playClip(AudioSystem.getClip().apply { open(input) })
        } finally {
            input.close()
        }
    }

    private suspend fun playClip(clip: Clip) {
        suspendCancellableCoroutine { continuation ->
            stop()
            activeClip = clip
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP || event.type == LineEvent.Type.CLOSE) {
                    if (activeClip === clip) activeClip = null
                    clip.close()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            continuation.invokeOnCancellation {
                if (activeClip === clip) activeClip = null
                clip.stop()
                clip.close()
            }

            clip.start()
        }
    }

    fun stop() {
        val clip = activeClip ?: return
        activeClip = null
        runCatching { clip.stop() }
        runCatching { clip.close() }
    }

    fun pause() {
        runCatching { activeClip?.takeIf { it.isRunning }?.stop() }
    }

    fun resume() {
        runCatching { activeClip?.takeIf { !it.isRunning }?.start() }
    }
}

private fun narrationAudioFile(cacheKey: String, persistent: Boolean): File =
    File(narrationRoot(persistent), "${cacheKey.sha256()}.wav")

private fun narrationRoot(persistent: Boolean): File {
    val home = System.getProperty("user.home") ?: "."
    val folder = if (persistent) "narration-audio" else "narration-audio-temp"
    return File(home, ".aninovelmanga/$folder")
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(encodeToByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
