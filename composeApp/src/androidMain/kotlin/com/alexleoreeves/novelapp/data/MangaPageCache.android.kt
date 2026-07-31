package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

actual fun clearTemporaryMangaPageCache() {
    AppContextHolder.applicationContext?.cacheDir
        ?.resolve("manga-page-temp")
        ?.deleteRecursively()
}

actual suspend fun cacheMangaChapterPages(
    chapterKey: String,
    pageUrls: List<String>,
    persistent: Boolean,
    onProgress: (completed: Int, total: Int) -> Unit
): List<String> = withContext(Dispatchers.IO) {
    val context = AppContextHolder.applicationContext ?: return@withContext pageUrls
    val root = if (persistent) {
        File(context.filesDir, "manga-pages/${chapterKey.sha256()}")
    } else {
        File(context.cacheDir, "manga-page-temp/${chapterKey.sha256()}")
    }
    root.mkdirs()
    val total = pageUrls.size
    val finalUrls = mutableListOf<String>()
    pageUrls.forEachIndexed { index, url ->
        onProgress(index, total)
        val ext = url.extensionOrDefault()
        val cached = File(root, "page_${index.toString().padStart(4, '0')}.$ext")
        
        if (!cached.exists() || cached.length() == 0L) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", url.substringBeforeLast("/", missingDelimiterValue = url))
                }
                try {
                    connection.inputStream.use { input ->
                        cached.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                cached.delete()
            }
        }
        
        // After ensuring the file is downloaded, check its dimensions
        if (cached.length() > 0L) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(cached.absolutePath, options)
            
            // If height is greater than max texture size (using 4096 to be safe), slice it
            val MAX_HEIGHT = 4096
            if (options.outHeight > MAX_HEIGHT) {
                runCatching {
                    val decoder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        android.graphics.BitmapRegionDecoder.newInstance(cached.absolutePath)
                    } else {
                        @Suppress("DEPRECATION")
                        android.graphics.BitmapRegionDecoder.newInstance(cached.absolutePath, false)
                    }
                    
                    if (decoder != null) {
                        val width = options.outWidth
                        val height = options.outHeight
                        val slices = kotlin.math.ceil(height.toDouble() / MAX_HEIGHT).toInt()
                        
                        val sliceFiles = mutableListOf<File>()
                        for (i in 0 until slices) {
                            val top = i * MAX_HEIGHT
                            val bottom = minOf((i + 1) * MAX_HEIGHT, height)
                            val rect = android.graphics.Rect(0, top, width, bottom)
                            
                            val sliceBitmap = decoder.decodeRegion(rect, android.graphics.BitmapFactory.Options())
                            val sliceFile = File(root, "page_${index.toString().padStart(4, '0')}_part$i.jpg")
                            sliceFile.outputStream().use { out ->
                                sliceBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            sliceBitmap.recycle()
                            sliceFiles.add(sliceFile)
                        }
                        decoder.recycle()
                        
                        // Delete original to save space and use slices instead
                        cached.delete()
                        finalUrls.addAll(sliceFiles.map { "file://" + it.absolutePath })
                    } else {
                        finalUrls.add("file://" + cached.absolutePath)
                    }
                }.onFailure {
                    // Fallback to original if slicing fails
                    finalUrls.add("file://" + cached.absolutePath)
                }
            } else {
                finalUrls.add("file://" + cached.absolutePath)
            }
        } else {
            finalUrls.add(url)
        }
        onProgress(index + 1, total)
    }
    finalUrls
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
