@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCacheDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile

actual fun clearTemporaryMangaPageCache() {
    NSFileManager.defaultManager.removeItemAtPath(mangaRoot(persistent = false, chapterKey = ""), error = null)
}

actual suspend fun cacheMangaChapterPages(
    chapterKey: String,
    pageUrls: List<String>,
    persistent: Boolean,
    onProgress: (completed: Int, total: Int) -> Unit
): List<String> = withContext(Dispatchers.Default) {
    val root = mangaRoot(persistent, chapterKey)
    ensureDirectory(root)
    val total = pageUrls.size
    pageUrls.mapIndexed { index, url ->
        onProgress(index, total)
        val path = "$root/page_${index.toString().padStart(4, '0')}.${url.extensionOrDefault()}"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            runCatching {
                val data = NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) }
                data?.writeToFile(path, atomically = true)
            }
        }
        onProgress(index + 1, total)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) "file://$path" else url
    }
}

private fun mangaRoot(persistent: Boolean, chapterKey: String): String {
    val base = if (persistent) documentsDirectory() else cachesDirectory()
    val folder = if (persistent) "manga-pages" else "manga-page-temp"
    val key = chapterKey.takeIf { it.isNotBlank() }?.hashCode()?.toUInt()?.toString(16).orEmpty()
    return "$base/$folder/$key"
}

private fun documentsDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: ""

private fun cachesDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSCacheDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: ""

private fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
}

private fun String.extensionOrDefault(): String {
    val ext = substringBefore("?").substringAfterLast(".", "")
        .lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") }
    return ext ?: "jpg"
}
