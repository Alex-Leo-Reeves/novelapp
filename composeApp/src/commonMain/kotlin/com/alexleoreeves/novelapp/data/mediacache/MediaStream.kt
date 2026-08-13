package com.alexleoreeves.novelapp.data.mediacache

/**
 * Unified media source. The player layer never cares whether bytes come from
 * an encrypted internal bundle, a mounted USB bundle, or the network — it asks
 * the [MediaStreamOpener] for a [SeekableMediaStream] and reads plaintext.
 */
sealed interface MediaSource {
    data class CachedBundle(
        val taskId: String,
        val bundlePath: String,
        val manifest: DownloadManifest
    ) : MediaSource

    data class UsbBundle(
        val taskId: String,
        val volumeId: String,
        val bundlePath: String,
        val manifest: DownloadManifest
    ) : MediaSource

    data class Network(val url: String, val contentType: String = "video/mp4") : MediaSource
}

/** Random-access plaintext stream. */
interface SeekableMediaStream {
    val totalBytes: Long
    val contentType: String

    /** Fill up to [dst.size] bytes starting at [offset]; returns bytes read (0 = EOF). */
    suspend fun readAt(offset: Long, dst: ByteArray): Int

    fun close()
}

/** Resolves a [MediaSource] to a readable stream. Platform impl per target. */
fun interface MediaStreamOpener {
    suspend fun open(source: MediaSource): SeekableMediaStream?
}

/**
 * Random-access ciphertext reader over a file FD. Implemented per platform;
 * keeps decryption math in commonMain.
 */
interface RandomAccessSource {
    val size: Long
    fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int
    fun close()
}
