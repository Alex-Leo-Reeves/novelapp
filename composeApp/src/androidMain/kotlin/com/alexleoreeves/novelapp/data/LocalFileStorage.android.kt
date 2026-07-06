package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private fun getNovelDownloadsDir(novelId: String): File {
    val ctx = AppContextHolder.applicationContext ?: return File("/tmp")
    // Try public Downloads directory first so users can find saved files
    val publicDir = try {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        if (downloadsDir != null) {
            val dir = File(downloadsDir, "NovelApp/novels/$novelId")
            if (dir.mkdirs() || dir.exists()) dir else null
        } else null
    } catch (e: Exception) { null }
    
    if (publicDir != null) return publicDir
    
    // Fallback to app-internal storage
    val dir = File(ctx.filesDir, "downloads/novels/$novelId")
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
        deleteLocalPath(localPath)
    } catch (e: Exception) {
        // ignore
    }
}

actual suspend fun saveDownloadedVideo(
    parentId: String,
    episodeNumber: Int,
    sourceUrl: String
): DownloadedVideoFile = withContext(Dispatchers.IO) {
    runCatching {
        if (!sourceUrl.startsWith("http", ignoreCase = true)) {
            val local = sourceUrl.toLocalFile()
            return@runCatching if (local != null && local.exists()) {
                DownloadedVideoFile(local.toURI().toString(), local.length())
            } else {
                DownloadedVideoFile(error = "Local video file was not found.")
            }
        }

        val dir = getVideoDownloadsDir(parentId, episodeNumber)
        dir.deleteRecursively()
        dir.mkdirs()

        if (sourceUrl.isHlsLikeUrl()) {
            saveHlsDownload(sourceUrl, dir)
        } else {
            val extension = sourceUrl.substringBefore("?")
                .substringBefore("#")
                .substringAfterLast(".", "mp4")
                .takeIf { it.length in 2..5 }
                ?: "mp4"
            val file = File(dir, "episode.$extension")
            val size = downloadToFile(sourceUrl, file)
            DownloadedVideoFile(file.toURI().toString(), size)
        }
    }.getOrElse { error ->
        DownloadedVideoFile(error = error.message ?: "Video download failed.")
    }
}

actual fun isDownloadedLocalFileAvailable(localPath: String): Boolean {
    val file = localPath.toLocalFile() ?: return false
    return file.exists() && file.length() > 0L
}

actual suspend fun extractStreamFromEmbed(embedUrl: String, timeoutMs: Long): String? {
    val ctx = com.alexleoreeves.novelapp.sensor.AppContextHolder.applicationContext ?: return null
    return com.alexleoreeves.novelapp.ui.extractStreamFromEmbed(ctx, embedUrl, timeoutMs)
}

private fun getVideoDownloadsDir(parentId: String, episodeNumber: Int): File {
    val ctx = AppContextHolder.applicationContext ?: return File("/tmp")
    val dir = File(ctx.filesDir, "downloads/videos/${parentId.safePathPart()}/ep_$episodeNumber")
    dir.mkdirs()
    return dir
}

private fun String.safePathPart(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "item" }

private fun String.toLocalFile(): File? =
    runCatching {
        when {
            startsWith("file://", ignoreCase = true) -> File(URI(this))
            startsWith("/") -> File(this)
            else -> null
        }
    }.getOrNull()

private fun deleteLocalPath(localPath: String) {
    localPath.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { part ->
            part.toLocalFile()?.let { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    val parent = file.parentFile
                    if (file.name.equals("playlist.m3u8", ignoreCase = true) && parent != null) {
                        parent.deleteRecursively()
                    } else {
                        file.delete()
                    }
                }
            }
        }
}

private fun String.isHlsLikeUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        Regex("""/(playlist|manifest|hls)(/|$)""").containsMatchIn(clean)
}

private fun saveHlsDownload(sourceUrl: String, dir: File): DownloadedVideoFile {
    val masterText = downloadText(sourceUrl)
    val (playlistUrl, playlistText) = selectMediaPlaylist(sourceUrl, masterText)
    val playlistFile = File(dir, "playlist.m3u8")
    var totalBytes = 0L
    var segmentIndex = 0
    var keyIndex = 0
    val rewritten = playlistText
        .lineSequence()
        .map { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) && "URI=\"" in trimmed -> {
                    val keyUri = Regex("""URI="([^"]+)"""").find(trimmed)?.groupValues?.getOrNull(1)
                    if (keyUri.isNullOrBlank()) line else {
                        val keyUrl = resolveUrl(playlistUrl, keyUri)
                        val keyFile = File(dir, "key_${keyIndex++}.bin")
                        totalBytes += downloadToFile(keyUrl, keyFile)
                        line.replace("""URI="$keyUri"""", """URI="${keyFile.name}"""")
                    }
                }
                trimmed.isBlank() || trimmed.startsWith("#") -> line
                else -> {
                    val segmentUrl = resolveUrl(playlistUrl, trimmed)
                    val extension = segmentUrl.substringBefore("?")
                        .substringBefore("#")
                        .substringAfterLast(".", "ts")
                        .takeIf { it.length in 2..5 }
                        ?: "ts"
                    val segmentFile = File(dir, "seg_${segmentIndex.toString().padStart(5, '0')}.$extension")
                    segmentIndex += 1
                    totalBytes += downloadToFile(segmentUrl, segmentFile)
                    segmentFile.name
                }
            }
        }
        .joinToString("\n")
    playlistFile.writeText(rewritten)
    return DownloadedVideoFile(playlistFile.toURI().toString(), totalBytes + playlistFile.length())
}

private fun selectMediaPlaylist(sourceUrl: String, playlistText: String): Pair<String, String> {
    if (!playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return sourceUrl to playlistText
    val lines = playlistText.lines()
    var nextUriIsVariant = false
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            nextUriIsVariant = true
        } else if (nextUriIsVariant && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
            val mediaUrl = resolveUrl(sourceUrl, trimmed)
            return mediaUrl to downloadText(mediaUrl)
        }
    }
    return sourceUrl to playlistText
}

private fun resolveUrl(baseUrl: String, value: String): String =
    URI(baseUrl).resolve(value).toString()

private fun downloadText(url: String): String =
    openConnection(url).inputStream.bufferedReader().use { it.readText() }

private fun downloadToFile(url: String, file: File): Long {
    file.parentFile?.mkdirs()
    openConnection(url).inputStream.use { input ->
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return file.length()
}

private fun openConnection(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", DOWNLOAD_USER_AGENT)
        setRequestProperty("Accept", "*/*")
    }

private const val DOWNLOAD_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
