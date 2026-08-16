@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

private fun documentsDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: ""

private fun safePathPart(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "item" }

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

private fun videoDownloadsDir(parentId: String, episodeNumber: Int): String {
    val dir = "${documentsDirectory()}/downloads/videos/${safePathPart(parentId)}/ep_$episodeNumber"
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
                val fileName = path.substringAfterLast("/", path)
                if (fileName.equals("playlist.m3u8", ignoreCase = true)) {
                    // HLS download: wipe the whole episode directory so segments,
                    // decryption keys, and any bundled .srt are removed too.
                    val parent = path.substringBeforeLast("/", path)
                    if (parent.isNotBlank() && parent != path) {
                        NSFileManager.defaultManager.removeItemAtPath(parent, error = null)
                    } else {
                        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                    }
                } else {
                    // Single-file download: remove the media file plus any bundled subtitle.
                    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                    val subtitlePath = path.substringBeforeLast(".") + ".srt"
                    if (subtitlePath != path) {
                        NSFileManager.defaultManager.removeItemAtPath(subtitlePath, error = null)
                    }
                }
            }
    }
}

actual suspend fun saveDownloadedVideo(
    parentId: String,
    episodeNumber: Int,
    sourceUrl: String
): DownloadedVideoFile = withContext(Dispatchers.Default) {
    runCatching {
        if (!sourceUrl.startsWith("http", ignoreCase = true)) {
            return@runCatching DownloadedVideoFile(error = "Local video file was not found.")
        }

        val dir = videoDownloadsDir(parentId, episodeNumber)
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
        ensureDirectory(dir)

        val client = HttpClient(Darwin) { expectSuccess = false }
        try {
            if (sourceUrl.isIosHlsLikeUrl()) {
                saveIosHlsDownload(client, sourceUrl, dir)
            } else {
                val extension = sourceUrl.substringBefore("?")
                    .substringBefore("#")
                    .substringAfterLast(".", "mp4")
                    .takeIf { it.length in 2..5 }
                    ?: "mp4"
                val filePath = "$dir/episode.$extension"
                downloadIosToFile(client, sourceUrl, filePath)
                DownloadedVideoFile(filePath, iosFileSize(filePath))
            }
        } finally {
            client.close()
        }
    }.getOrElse { error ->
        DownloadedVideoFile(error = error.message ?: "Video download failed.")
    }
}

actual fun isDownloadedLocalFileAvailable(localPath: String): Boolean {
    val path = localPath.removePrefix("file://")
    if (path.isBlank()) return false
    return NSFileManager.defaultManager.fileExistsAtPath(path) && iosFileSize(path) > 0L
}

actual suspend fun extractStreamFromEmbed(embedUrl: String, timeoutMs: Long): String? =
    withContext(Dispatchers.Default) {
        runCatching {
            val client = HttpClient(Darwin) { expectSuccess = false }
            try {
                val html = client.get(embedUrl) {
                    header("User-Agent", IOS_DOWNLOAD_USER_AGENT)
                    header("Accept", "*/*")
                    header("Accept-Language", "en-US,en;q=0.9")
                }.bodyAsText()
                EmbedStreamExtractor.findDirectStream(html)
            } finally {
                client.close()
            }
        }.getOrNull()
    }

// ── iOS download helpers ─────────────────────────────────────────────────────

private fun String.isIosHlsLikeUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        Regex("""/(playlist|manifest|hls)(/|$)""").containsMatchIn(clean)
}

private suspend fun downloadIosToFile(client: HttpClient, url: String, filePath: String) {
    val response = client.get(url) {
        header("User-Agent", IOS_DOWNLOAD_USER_AGENT)
        header("Accept", "*/*")
        header("Accept-Encoding", "identity")
    }
    val channel = response.bodyAsChannel()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read <= 0) break
        iosAppendBytes(filePath, buffer.copyOf(read))
    }
}

private suspend fun saveIosHlsDownload(client: HttpClient, sourceUrl: String, dir: String): DownloadedVideoFile {
    val masterText = iosFetchText(client, sourceUrl)
    val (playlistUrl, playlistText) = iosSelectMediaPlaylist(client, sourceUrl, masterText)
    val playlistPath = "$dir/playlist.m3u8"
    var totalBytes = 0L
    var segmentIndex = 0
    var keyIndex = 0
    val rewrittenLines = mutableListOf<String>()
    for (line in playlistText.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) && "URI=\"" in trimmed -> {
                val keyUri = Regex("""URI="([^"]+)"""").find(trimmed)?.groupValues?.getOrNull(1)
                if (keyUri.isNullOrBlank()) {
                    rewrittenLines.add(line)
                } else {
                    val keyUrl = iosResolveUrl(playlistUrl, keyUri)
                    val keyPath = "$dir/key_${keyIndex++}.bin"
                    iosDownloadBytesToFile(client, keyUrl, keyPath)
                    totalBytes += iosFileSize(keyPath)
                    rewrittenLines.add(line.replace("""URI="$keyUri"""", """URI="${keyPath.substringAfterLast("/")}""""))
                }
            }
            trimmed.isBlank() || trimmed.startsWith("#") -> {
                rewrittenLines.add(line)
            }
            else -> {
                val segmentUrl = iosResolveUrl(playlistUrl, trimmed)
                val extension = segmentUrl.substringBefore("?")
                    .substringBefore("#")
                    .substringAfterLast(".", "ts")
                    .takeIf { it.length in 2..5 }
                    ?: "ts"
                val segmentPath = "$dir/seg_${segmentIndex.toString().padStart(5, '0')}.$extension"
                segmentIndex += 1
                iosDownloadBytesToFile(client, segmentUrl, segmentPath)
                totalBytes += iosFileSize(segmentPath)
                rewrittenLines.add(segmentPath.substringAfterLast("/"))
            }
        }
    }
    val rewritten = rewrittenLines.joinToString("\n")
    iosWriteUtf8(playlistPath, rewritten)
    return DownloadedVideoFile(playlistPath, totalBytes + iosFileSize(playlistPath))
}

private suspend fun iosDownloadBytesToFile(client: HttpClient, url: String, filePath: String) {
    val bytes = client.get(url) {
        header("User-Agent", IOS_DOWNLOAD_USER_AGENT)
        header("Accept", "*/*")
        header("Accept-Encoding", "identity")
    }.bodyAsBytes()
    bytes.toNSData().writeToFile(filePath, atomically = false)
}

private suspend fun iosSelectMediaPlaylist(client: HttpClient, sourceUrl: String, playlistText: String): Pair<String, String> {
    if (!playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return sourceUrl to playlistText
    val lines = playlistText.lines()
    var nextUriIsVariant = false
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            nextUriIsVariant = true
        } else if (nextUriIsVariant && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
            val mediaUrl = iosResolveUrl(sourceUrl, trimmed)
            return mediaUrl to iosFetchText(client, mediaUrl)
        }
    }
    return sourceUrl to playlistText
}

private fun iosResolveUrl(baseUrl: String, value: String): String {
    // ObjC selector is `+[NSURL URLWithString:relativeToURL:]`. Kotlin/Native
    // binds it as the class factory `NSURL.URLWithString(value, relativeToURL = base)`;
    // there is NO one-arg instance method `URLWithString(String)` in the platform
    // bindings, so the old call was a Kotlin/Native compile error on macOS CI.
    val base = NSURL.URLWithString(baseUrl) ?: return value
    return NSURL.URLWithString(value, relativeToURL = base)?.absoluteString ?: value
}

private suspend fun iosFetchText(client: HttpClient, url: String): String =
    client.get(url) {
        header("User-Agent", IOS_DOWNLOAD_USER_AGENT)
        header("Accept", "*/*")
    }.bodyAsText()

private fun iosAppendBytes(filePath: String, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    val existing = NSData.dataWithContentsOfFile(filePath)
    if (existing != null) {
        val combined = NSMutableData.dataWithData(existing)
        combined.appendData(bytes.toNSData())
        combined.writeToFile(filePath, atomically = false)
    } else {
        bytes.toNSData().writeToFile(filePath, atomically = false)
    }
}

private fun iosWriteUtf8(filePath: String, text: String) {
    NSString.create(string = text).writeToFile(
        path = filePath,
        atomically = true,
        encoding = NSUTF8StringEncoding,
        error = null
    )
}

private fun iosFileSize(filePath: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(filePath, error = null)
        ?: return 0L
    val size = attributes["NSFileSize"] as? NSNumber ?: return 0L
    return size.longLongValue
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private const val IOS_DOWNLOAD_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
