package com.alexleoreeves.novelapp.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
//  Public Downloads directory helper — uses MediaStore on API ≥ 29 for
//  maximum compatibility with user-facing file managers.
//  Falls back to DIRECTORY_DOWNLOADS on older APIs.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns a File pointing to the public NovelApp directory under Downloads.
 * On API < 29 this is DIRECTORY_DOWNLOADS/NovelApp/
 * On API ≥ 29 we still use a File for novel text (since it's just text),
 * but video downloads go through MediaStore.
 */
private fun getPublicNovelDir(novelId: String): File {
    return try {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (downloadsDir != null) {
            val dir = File(downloadsDir, "NovelApp/novels/$novelId")
            dir.mkdirs()
            dir
        } else {
            // Fallback to internal
            val ctx = AppContextHolder.applicationContext ?: return File("/tmp")
            val dir = File(ctx.filesDir, "downloads/novels/$novelId")
            dir.mkdirs()
            dir
        }
    } catch (e: Exception) {
        // Fallback to internal
        val ctx = AppContextHolder.applicationContext ?: return File("/tmp")
        val dir = File(ctx.filesDir, "downloads/novels/$novelId")
        dir.mkdirs()
        dir
    }
}

/**
 * Returns internal-storage path for video downloads (these are large .mp4/.m3u8
 * files). Also saves a copy to public Downloads/NovelApp/ for user access.
 */
private fun getInternalVideoDir(parentId: String, episodeNumber: Int): File {
    val ctx = AppContextHolder.applicationContext ?: return File("/tmp")
    val dir = File(ctx.filesDir, "downloads/videos/${parentId.safePathPart()}/ep_$episodeNumber")
    dir.mkdirs()
    return dir
}

/**
 * Saves a downloaded video to the Android MediaStore (public Downloads) so the
 * user can find it in their file manager. Returns the content:// URI.
 */
private fun saveVideoToMediaStore(
    context: Context,
    displayName: String,
    mimeType: String,
    dataSource: File
): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        // Pre-Q: file is already at DIRECTORY_DOWNLOADS/NovelApp/ if we copied it there
        return null
    }

    val contentValues = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/NovelApp")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { output ->
            dataSource.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(it, contentValues, null, null)
    }

    return uri
}

actual fun saveDownloadedText(novelId: String, chapterNumber: Int, text: String): String {
    return try {
        val file = File(getPublicNovelDir(novelId), "ch_$chapterNumber.txt")
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

        // 1. Download to internal storage (works everywhere, fast path)
        val internalDir = getInternalVideoDir(parentId, episodeNumber)
        internalDir.deleteRecursively()
        internalDir.mkdirs()

        val mediaTitle = "${parentId.safePathPart()}_EP${episodeNumber}"

        if (sourceUrl.isHlsLikeUrl()) {
            val saved = saveHlsDownload(sourceUrl, internalDir)
            // Copy to public MediaStore as a playlist marker
            val ctx = AppContextHolder.applicationContext
            if (ctx != null && saved.success) {
                saveVideoToMediaStore(ctx, "$mediaTitle.m3u8", "application/vnd.apple.mpegurl", File(internalDir, "playlist.m3u8"))
            }
            saved
        } else {
            val extension = sourceUrl.substringBefore("?")
                .substringBefore("#")
                .substringAfterLast(".", "mp4")
                .takeIf { it.length in 2..5 }
                ?: "mp4"
            val file = File(internalDir, "episode.$extension")
            val size = downloadToFile(sourceUrl, file)

            // Copy to public MediaStore for user access
            val ctx = AppContextHolder.applicationContext
            if (ctx != null) {
                val mimeType = when (extension.lowercase()) {
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    "mkv" -> "video/x-matroska"
                    "avi" -> "video/x-msvideo"
                    "mov" -> "video/quicktime"
                    else -> "video/mp4"
                }
                saveVideoToMediaStore(ctx, "$mediaTitle.$extension", mimeType, file)
            }

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
    return com.alexleoreeves.novelapp.ui.extractStreamFromEmbed(ctx, embedUrl, timeoutMs)?.url
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
