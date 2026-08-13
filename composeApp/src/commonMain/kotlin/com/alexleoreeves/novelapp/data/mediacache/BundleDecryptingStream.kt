package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Random-access plaintext reader over an encrypted bundle — the commonMain
 * half of local (offline) playback.
 *
 * Each platform supplies a [RandomAccessSource] over its ciphertext bundle
 * file. This stream maps a requested plaintext offset to the owning
 * [ChunkRecord], seeks the source to the chunk's absolute disk offset, reads
 * the chunk's full ciphertext and decrypts it through the injected
 * [MediaCryptoPort] (which authenticates the HMAC tag before decrypting).
 *
 * Reads never span chunk boundaries: if the caller's buffer would cross a
 * chunk edge the stream returns only the bytes available inside the current
 * chunk and the player re-reads at the next offset — the [SeekableMediaStream]
 * contract allows partial fills. A truncated bundle or a failed HMAC check
 * throws [MediaCryptoException] so the player can surface "corrupt bundle —
 * delete and re-download" instead of silently ending the stream early.
 *
 * Pure commonMain: all platform I/O comes through [RandomAccessSource].
 */
class BundleDecryptingStream(
    private val source: RandomAccessSource,
    private val crypto: MediaCryptoPort,
    private val manifest: DownloadManifest
) : SeekableMediaStream {

    private val ivSeed: ByteArray = manifest.ivSeedHex.hexToBytes()
    private val chunks: List<ChunkRecord> = manifest.chunks.sortedBy { it.index }

    /** Absolute disk offset of each chunk's first ciphertext byte. */
    private val diskOffsets: LongArray = run {
        var acc = 0L
        LongArray(chunks.size) { i ->
            val start = acc
            acc += chunks[i].encryptedLength
            start
        }
    }

    private var closed = false

    override val totalBytes: Long get() = manifest.totalBytes

    override val contentType: String
        get() = when (manifest.containerExtension.lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "m3u8" -> "application/vnd.apple.mpegurl"
            else -> "video/${manifest.containerExtension.lowercase()}"
        }

    override suspend fun readAt(offset: Long, dst: ByteArray): Int {
        if (closed || offset < 0L || offset >= manifest.totalBytes) return 0
        val chunkIndex = (offset / MEDIA_CHUNK_SIZE).toInt()
        if (chunkIndex < 0 || chunkIndex >= chunks.size) return 0
        val chunk = chunks[chunkIndex]

        currentCoroutineContext().ensureActive()
        val encrypted = readChunkCiphertext(chunkIndex, chunk)
            ?: throw MediaCryptoException("Bundle truncated at chunk ${chunk.index} — file is shorter than the manifest chunk plan")

        val plaintext = try {
            crypto.decryptChunk(encrypted, ivSeed, chunk.index)
        } catch (e: MediaCryptoException) {
            throw e
        } catch (e: Exception) {
            throw MediaCryptoException("Bundle chunk ${chunk.index} failed to decrypt", e)
        }

        val withinChunk = (offset - chunk.startOffset).toInt()
        val available = plaintext.size - withinChunk
        if (available <= 0) return 0
        val toCopy = available.coerceAtMost(dst.size)
        plaintext.copyInto(dst, 0, withinChunk, withinChunk + toCopy)
        return toCopy
    }

    override fun close() {
        if (closed) return
        closed = true
        source.close()
    }

    /** Read the chunk's full on-disk ciphertext, looping until satisfied. */
    private fun readChunkCiphertext(chunkIndex: Int, chunk: ChunkRecord): ByteArray? {
        val diskOffset = diskOffsets[chunkIndex]
        val length = chunk.encryptedLength.toInt()
        if (length <= 0) return null
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = source.readAt(diskOffset + read, bytes, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) bytes else null
    }
}
