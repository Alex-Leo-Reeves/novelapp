package com.alexleoreeves.novelapp.tv.mediacache

import com.alexleoreeves.novelapp.data.mediacache.BundleDecryptingStream
import com.alexleoreeves.novelapp.data.mediacache.DownloadManifest
import com.alexleoreeves.novelapp.data.mediacache.MediaCryptoPort
import com.alexleoreeves.novelapp.data.mediacache.MediaSource
import com.alexleoreeves.novelapp.data.mediacache.MediaStreamOpener
import com.alexleoreeves.novelapp.data.mediacache.RandomAccessSource
import com.alexleoreeves.novelapp.data.mediacache.SeekableMediaStream
import java.io.RandomAccessFile

/**
 * Opens a fully-downloaded, encrypted bundle as a seekable PLAINTEXT stream.
 *
 * This is the offline (local-bundle) playback path: the player has no network
 * URL, only a [MediaSource.CachedBundle] (internal storage) or
 * [MediaSource.UsbBundle] (indexed USB volume). Both resolve to an absolute
 * bundle path; the opener wraps the file in a [RandomAccessFile]-backed
 * [RandomAccessSource], then a [BundleDecryptingStream] performs the
 * chunk-offset mapping + AES/HMAC decrypt on every read.
 *
 * [MediaSource.Network] returns null — network streams stay on the normal
 * player path; this opener only serves completed bundles.
 */
class TvBundleMediaOpener(
    private val crypto: MediaCryptoPort
) : MediaStreamOpener {

    override suspend fun open(source: MediaSource): SeekableMediaStream? = when (source) {
        is MediaSource.CachedBundle -> openBundle(source.bundlePath, source.manifest)
        is MediaSource.UsbBundle -> openBundle(source.bundlePath, source.manifest)
        is MediaSource.Network -> null
    }

    private fun openBundle(bundlePath: String, manifest: DownloadManifest): SeekableMediaStream? {
        if (bundlePath.isBlank() || manifest.chunks.isEmpty()) return null
        val file = java.io.File(bundlePath)
        if (!file.isFile || file.length() <= 0L) return null
        // Cheap pre-check: on-disk ciphertext must cover the chunk plan. The
        // HMAC still protects against corruption within that range.
        val expectedCipherBytes = manifest.chunks.sumOf { it.encryptedLength }
        if (file.length() < expectedCipherBytes) return null
        return try {
            BundleDecryptingStream(RandomAccessFileSource(file), crypto, manifest)
        } catch (e: Exception) {
            null
        }
    }

    /** RandomAccessFile adapter for the ciphertext source. */
    private class RandomAccessFileSource(file: java.io.File) : RandomAccessSource {
        private val raf = RandomAccessFile(file, "r")

        override val size: Long get() = raf.length()

        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
            if (offset >= raf.length() || length <= 0) return 0
            raf.seek(offset)
            return raf.read(dst, dstOffset, length)
        }

        override fun close() {
            raf.close()
        }
    }
}
