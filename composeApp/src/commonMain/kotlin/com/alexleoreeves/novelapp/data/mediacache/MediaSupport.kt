package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.delay
import kotlin.time.TimeSource

// ─────────────────────────────────────────────────────────────────────────────
//  Pure-Kotlin helpers shared by the engine, scheduler and platform impls.
// ─────────────────────────────────────────────────────────────────────────────

/** Monotonic wall-clock in milliseconds. NOT epoch — only deltas are safe. */
fun monotonicMs(): Long =
    TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds

private const val HEX_CHARS = "0123456789abcdef"

fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { b ->
        val v = b.toInt() and 0xFF
        append(HEX_CHARS[v ushr 4])
        append(HEX_CHARS[v and 0x0F])
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(length / 2) { i ->
        val hi = this[i * 2].digitToIntOrNull(16) ?: -1
        val lo = this[i * 2 + 1].digitToIntOrNull(16) ?: -1
        require(hi >= 0 && lo >= 0) { "Invalid hex character" }
        ((hi shl 4) or lo).toByte()
    }
}

/**
 * Chunk-unique IV: XOR the per-task IV seed (16 bytes) with the chunk index.
 * Keeps IVs deterministic and resumable without storing one IV per chunk.
 */
fun ivForChunk(ivSeed: ByteArray, chunkIndex: Int): ByteArray {
    require(ivSeed.size == 16) { "IV seed must be 16 bytes" }
    return ivSeed.copyOf().also { seed ->
        var index = chunkIndex
        var byte = 0
        while (index > 0 && byte < 16) {
            seed[byte] = (seed[byte].toInt() xor (index and 0xFF).toInt()).toByte()
            index = index ushr 8
            byte++
        }
    }
}

/** Reserve requirement per plan: 2× content size on USB (FAT32 headroom), +safety reserve. */
fun requiredFreeBytes(totalBytes: Long, target: StorageTarget): Long {
    val multiplier = if (target == StorageTarget.USB) 2L else 1L
    return (totalBytes * multiplier) + MEDIA_SAFETY_RESERVE_BYTES
}

fun expectedCipherSizes(totalBytes: Long): Long {
    val chunks = chunkCountFor(totalBytes)
    // Each chunk gains a 32-byte HMAC tag + 16-byte IV on disk.
    return totalBytes + (chunks * (32L + 16L))
}

/** Heuristic for "disk full" surfaced as an IOException across platforms. */
fun isStorageFullError(t: Throwable): Boolean {
    val msg = t.message?.lowercase() ?: return false
    return "no space left" in msg || "enospc" in msg || "disk full" in msg
}

/**
 * Token-bucket rate limiter shared by all chunk transfers. Not thread-safe by
 * itself — callers guard it with a Mutex.
 */
class TokenBucket(
    var maxBytesPerSecond: Long,
    private val capacityBytes: Long
) {
    private var tokens: Long = capacityBytes
    private var lastRefillMs: Long = monotonicMs()

    /** Consume [amount] bytes, suspending as needed. No-op when throttling is off. */
    suspend fun consume(amount: Long) {
        val max = maxBytesPerSecond
        if (max <= 0L || amount <= 0L) return
        refill(max)
        if (tokens >= amount) {
            tokens -= amount
            return
        }
        while (tokens < amount) {
            refill(max)
            val missing = amount - tokens
            if (missing <= 0L) break
            val msToWait = (missing * 1000L) / max
            delay(msToWait.coerceIn(1L, 250L))
            refill(max)
        }
        tokens -= amount
    }

    private fun refill(maxBytesPerSecond: Long) {
        val now = monotonicMs()
        val elapsedMs = (now - lastRefillMs).coerceAtLeast(0L)
        if (elapsedMs <= 0L) return
        val added = (elapsedMs * maxBytesPerSecond) / 1000L
        tokens = (tokens + added).coerceAtMost(capacityBytes)
        lastRefillMs = now
    }
}

/** Filesystem-safe task/file name. Shared by the manifest store and runner. */
fun safeFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "task" }
