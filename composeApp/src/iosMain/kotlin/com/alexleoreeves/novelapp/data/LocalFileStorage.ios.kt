@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.data

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

private fun documentsDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: ""

private fun safePathPart(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
}

private fun novelDownloadsDir(novelId: String): String {
    val dir = "${documentsDirectory()}/downloads/novels/${safePathPart(novelId)}"
    ensureDirectory(dir)
    return dir
}

actual fun saveDownloadedText(novelId: String, chapterNumber: Int, text: String): String {
    return try {
        val filePath = "${novelDownloadsDir(novelId)}/ch_$chapterNumber.txt"
        val saved = NSString.create(string = text).writeToFile(
            path = filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )
        if (saved) filePath else ""
    } catch (e: Exception) {
        ""
    }
}

actual fun loadDownloadedText(localPath: String): String {
    return try {
        NSString.stringWithContentsOfFile(
            path = localPath,
            encoding = NSUTF8StringEncoding,
            error = null
        )?.toString() ?: "Offline chapter content not found."
    } catch (e: Exception) {
        "Failed to load offline chapter content."
    }
}

actual fun deleteDownloadedText(localPath: String) {
    runCatching {
        localPath.split(",")
            .map { it.trim().removePrefix("file://") }
            .filter { it.isNotBlank() }
            .forEach { path ->
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            }
    }
}

actual suspend fun saveDownloadedVideo(
    parentId: String,
    episodeNumber: Int,
    sourceUrl: String
): DownloadedVideoFile =
    DownloadedVideoFile(error = "Offline video downloads are only available on Android right now.")

actual fun isDownloadedLocalFileAvailable(localPath: String): Boolean {
    val path = localPath.removePrefix("file://")
    return path.isNotBlank() && NSFileManager.defaultManager.fileExistsAtPath(path)
}

actual suspend fun extractStreamFromEmbed(embedUrl: String, timeoutMs: Long): String? = null
