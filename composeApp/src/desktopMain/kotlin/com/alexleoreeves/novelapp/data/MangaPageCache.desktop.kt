package com.alexleoreeves.novelapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

actual fun clearTemporaryMangaPageCache() {
    mangaRoot(persistent = false, chapterKey = "").deleteRecursively()
}

actual suspend fun cacheMangaChapterPages(
    chapterKey: String,
    pageUrls: List<String>,
    persistent: Boolean,
    onProgress: (completed: Int, total: Int) -> Unit
): List<String> = withContext(Dispatchers.IO) {
    val root = mangaRoot(persistent, chapterKey)
    root.mkdirs()
    val total = pageUrls.size
    pageUrls.mapIndexed { index, url ->
        onProgress(index, total)
        val cached = File(root, "page_${index.toString().padStart(4, '0')}.${url.extensionOrDefault()}")
        if (!cached.exists() || cached.length() == 0L) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                try {
                    connection.inputStream.use { input ->
                        cached.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { cached.delete() }
        }
        onProgress(index + 1, total)
        cached.takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString() ?: url
    }
}

private fun mangaRoot(persistent: Boolean, chapterKey: String): File {
    val home = System.getProperty("user.home") ?: "."
    val folder = if (persistent) "manga-pages" else "manga-page-temp"
    val key = chapterKey.takeIf { it.isNotBlank() }?.sha256().orEmpty()
    return File(home, ".aninovelmanga/$folder/$key")
}

private fun String.extensionOrDefault(): String {
    val ext = substringBefore("?").substringAfterLast(".", "")
        .lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") }
    return ext ?: "jpg"
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
