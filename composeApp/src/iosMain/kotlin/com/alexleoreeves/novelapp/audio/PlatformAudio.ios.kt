@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.audio

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile

private var activeNarrationPlayer: AVAudioPlayer? = null

suspend fun platformPlayAudio(audioBytes: ByteArray) {
    val data = audioBytes.toNSData() ?: return
    val player = AVAudioPlayer(data = data, error = null)
    activeNarrationPlayer?.stop()
    activeNarrationPlayer = player
    player.prepareToPlay()
    player.play()
    delay((player.duration * 1000.0).toLong().coerceAtLeast(100L))
    if (activeNarrationPlayer === player) {
        activeNarrationPlayer = null
    }
}

suspend fun playAudioFile(filePath: String) {
    val player = AVAudioPlayer(
        contentsOfURL = NSURL.fileURLWithPath(filePath),
        error = null
    )
    activeNarrationPlayer?.stop()
    activeNarrationPlayer = player
    player.prepareToPlay()
    player.play()
    delay((player.duration * 1000.0).toLong().coerceAtLeast(100L))
    if (activeNarrationPlayer === player) {
        activeNarrationPlayer = null
    }
}

internal fun stopPlatformNarrationAudio() {
    activeNarrationPlayer?.stop()
    activeNarrationPlayer = null
}

internal fun pausePlatformNarrationAudio() {
    activeNarrationPlayer?.pause()
}

internal fun resumePlatformNarrationAudio() {
    activeNarrationPlayer?.play()
}

fun clearTemporaryNarrationAudioCache() {
    NSFileManager.defaultManager.removeItemAtPath(narrationRoot(persistent = false), error = null)
}

fun existingNarrationAudioCachePath(cacheKey: String, persistent: Boolean): String? {
    val path = narrationAudioPath(cacheKey, persistent)
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
}

fun writeNarrationAudioCache(cacheKey: String, persistent: Boolean, audioBytes: ByteArray): String? {
    if (audioBytes.size <= 44) return null
    val path = narrationAudioPath(cacheKey, persistent)
    ensureDirectory(path.substringBeforeLast("/"))
    val data = audioBytes.toNSData() ?: return null
    return if (data.writeToFile(path, atomically = true)) path else null
}

private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}

private fun narrationAudioPath(cacheKey: String, persistent: Boolean): String =
    "${narrationRoot(persistent)}/${cacheKey.hashCode().toUInt().toString(16)}.wav"

private fun narrationRoot(persistent: Boolean): String {
    val folder = if (persistent) "narration-audio" else "narration-audio-temp"
    val root = "${documentsDirectory()}/$folder"
    ensureDirectory(root)
    return root
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
