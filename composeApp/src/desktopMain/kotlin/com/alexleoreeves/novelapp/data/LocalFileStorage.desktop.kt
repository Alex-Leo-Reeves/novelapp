package com.alexleoreeves.novelapp.data

import java.io.File
import java.net.URI

private fun getNovelDownloadsDir(novelId: String): File {
    val dir = File(System.getProperty("user.home") ?: ".", ".aninovelmanga/downloads/novels/$novelId")
    dir.mkdirs()
    return dir
}

actual fun saveDownloadedText(novelId: String, chapterNumber: Int, text: String): String {
    return try {
        val file = File(getNovelDownloadsDir(novelId), "ch_$chapterNumber.txt")
        file.writeText(text)
        file.absolutePath
    } catch (e: Exception) {
        ""
    }
}

actual fun loadDownloadedText(localPath: String): String {
    return try {
        val file = File(localPath)
        if (file.exists()) file.readText() else "Offline chapter content not found."
    } catch (e: Exception) {
        "Failed to load offline chapter content."
    }
}

actual fun deleteDownloadedText(localPath: String) {
    try {
        localPath.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { path ->
                path.toLocalFile()?.let { file ->
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
    } catch (e: Exception) {
        // ignore
    }
}

actual suspend fun saveDownloadedVideo(
    parentId: String,
    episodeNumber: Int,
    sourceUrl: String
): DownloadedVideoFile =
    DownloadedVideoFile(error = "Offline video downloads are only available on Android right now.")

actual fun isDownloadedLocalFileAvailable(localPath: String): Boolean {
    return try {
        val file = localPath.toLocalFile() ?: return false
        file.exists() && file.length() > 0L
    } catch (e: Exception) {
        false
    }
}

private fun String.toLocalFile(): File? =
    runCatching {
        if (startsWith("file://", ignoreCase = true)) File(URI(this)) else File(this)
    }.getOrNull()
