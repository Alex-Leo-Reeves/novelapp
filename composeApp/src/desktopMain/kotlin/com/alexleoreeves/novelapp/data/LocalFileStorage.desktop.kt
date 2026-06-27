package com.alexleoreeves.novelapp.data

import java.io.File

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
        val file = File(localPath)
        if (file.exists()) file.delete()
    } catch (e: Exception) {
        // ignore
    }
}
