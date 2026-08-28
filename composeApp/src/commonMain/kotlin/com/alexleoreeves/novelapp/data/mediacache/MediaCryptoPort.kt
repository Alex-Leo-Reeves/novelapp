package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.delay
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
//  Crypto + platform I/O ports.
//
//  Pure interfaces: each platform (Android / iOS / Desktop / TV) supplies an
//  implementation. The engine only ever talks to these ports, so it stays in
//  commonMain and is reusable by tvApp without expect/actual.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Symmetric cipher used for on-disk bundle chunks.
 *
 * Format contract (identical on every platform so a bundle written by one
 * device OS can — where the key is shared — be read by another):
 *
 *   chunk on disk = [tag(32 bytes)][iv(16 bytes)][ciphertext]
 *
 *   - ciphertext = AES-256-CBC(plaintext)
 *   - iv         = XOR(IV_SEED, chunkIndex)  chunk-unique IV
 *   - tag        = HMAC-SHA256(key, iv || ciphertext)
 *
 * Encrypt-then-MAC: the authenticated tag is verified before any decrypt.
 */
interface MediaCryptoPort {
    /** AES key (32 bytes) and HMAC key (32 bytes), or null when not available. */
    val keysAvailable: Boolean

    /** Short stable fingerprint of the HMAC key, persisted in the manifest. */
    fun hmacKeyFingerprint(): String

    /** Create a fresh IV seed (16 bytes) for a new task. */
    fun generateIvSeed(): ByteArray

    /**
     * Encrypt one chunk and return its on-disk layout in [EncryptedChunk].
     * @param chunkIndex 0-based chunk number, used to derive the IV.
     */
    fun encryptChunk(plaintext: ByteArray, ivSeed: ByteArray, chunkIndex: Int): EncryptedChunk

    /**
     * Decrypt + authenticate one chunk. Throws [MediaCryptoException] when the
     * HMAC tag does not verify (corruption/tamper).
     */
    fun decryptChunk(data: ByteArray, ivSeed: ByteArray, chunkIndex: Int): ByteArray

    /** SHA-256 of a chunk's plaintext, hex-encoded. */
    fun sha256Hex(data: ByteArray): String
}

class MediaCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** On-disk layout of one encrypted chunk: [tag][iv][ciphertext]. */
data class EncryptedChunk(
    val bytes: ByteArray,
    val tag: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray
)

// ─────────────────────────────────────────────────────────────────────────────
//  Storage port
// ─────────────────────────────────────────────────────────────────────────────

/** Filesystem operations the engine needs, abstracted per platform. */
interface MediaStoragePort {
    /** Absolute path to the cache root carrying [MEDIA_CACHE_SUBDIR]. */
    fun cacheRoot(): MediaVolumePath

    /**
     * Resolve a volume for a target; for INTERNAL this is the cache root.
     * Returns null when the requested USB volume is not currently mounted.
     */
    fun resolveVolume(target: StorageTarget, usbVolumeId: String?): MediaVolumePath?

    /** Free bytes on the volume containing [path] (best effort). */
    fun freeBytes(path: String): Long

    /** Create parent directories for [path] as needed. */
    fun ensureDir(path: String)

    fun readBytes(path: String): ByteArray?
    fun writeBytesAtomically(path: String, bytes: ByteArray)

    /** Open the bundle for writing ciphertext chunks at absolute offsets. */
    fun openBundleForWrite(path: String): MediaBundleWriter

    /** Delete a file or directory recursively. */
    fun delete(path: String)

    fun exists(path: String): Boolean

    /** Total ciphertext bytes currently present on disk for the given task. */
    fun bundleSize(path: String): Long

    /** Absolute paths of immediate children in [dir] whose name ends with [suffix]. */
    fun listChildren(dir: String, suffix: String): List<String>

    /** Last-modified epoch millis for [path], or 0 when unavailable. */
    fun lastModified(path: String): Long
}

/** A resolved volume (internal cache dir or a mounted USB root). */
data class MediaVolumePath(
    val id: String,
    val label: String,
    val rootAbsolutePath: String
)

/** Random-access ciphertext sink for a single pre-allocated bundle. */
interface MediaBundleWriter {
    /** Write exactly [bytes] at the absolute byte offset [offset]. */
    fun write(atOffset: Long, bytes: ByteArray)

    /** Flush + fsync. Must be called before the manifest checkpoint. */
    fun sync()

    fun close()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Transport port
// ─────────────────────────────────────────────────────────────────────────────

/** Network layer the engine drives. One instance per platform HTTP client. */
interface MediaTransportPort {
    /** Issue HEAD (+ first range probe) to learn size/range support/content-type. */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): MediaProbe

    /**
     * Suspend-fetch ciphertext bytes for one byte range. Implementations must
     * return null on a transient failure so the scheduler can retry, and throw
     * only for fatal errors (e.g. HTTP 416 after resume is logically done).
     */
    suspend fun fetchRange(
        url: String,
        start: Long,
        endInclusive: Long,
        headers: Map<String, String> = emptyMap()
    ): ByteArray?

    /**
     * Fetch a complete small payload (subtitle `.srt`, sidecar file). Used by
     * [SubtitleBundler] to download the English subtitle once at enqueue time.
     *
     * Default implementation returns null so existing port implementations
     * keep compiling; platforms that support subtitle bundling override it.
     */
    suspend fun fetchFull(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = null
}

/** Whether a background chunk may currently run (network policy gate). */
fun interface MediaAdmissionGate {
    /** true → chunk admitted; false → task blocks (cellular blocked, etc.). */
    fun allow(): Boolean
}

// ─────────────────────────────────────────────────────────────────────────────
//  Retry helper (pure Kotlin, shared by scheduler)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Exponential backoff with full jitter: delay = random(0, base * 2^attempt).
 * Cancellation propagates through the suspend [onBackoff] hook.
 */
object MediaRetry {
    suspend fun awaitBackoff(attempt: Int, baseMs: Long = MEDIA_RETRY_BASE_DELAY_MS, onBackoff: suspend () -> Unit = {}) {
        if (attempt <= 0) return
        val cap = baseMs * (1L shl attempt.coerceAtMost(10))
        val delayMs = Random.nextLong(0L, cap.coerceAtLeast(1L))
        if (delayMs > 0L) delay(delayMs)
        onBackoff()
    }

    fun isTransient(t: Throwable): Boolean =
        t is MediaTransportException
}

class MediaTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Compute how many chunks a file of [totalBytes] splits into. */
fun chunkCountFor(totalBytes: Long): Int =
    if (totalBytes <= 0L) 0
    else ((totalBytes + MEDIA_CHUNK_SIZE - 1) / MEDIA_CHUNK_SIZE).toInt().coerceAtLeast(1)

/** Plaintext byte length of chunk [index] within a [totalBytes] file. */
fun chunkLengthAt(index: Int, totalBytes: Long): Long {
    val start = index.toLong() * MEDIA_CHUNK_SIZE
    if (start >= totalBytes) return 0L
    return (totalBytes - start).coerceAtMost(MEDIA_CHUNK_SIZE)
}
