package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * Network-only transfer core. Owns: bounded global concurrency (semaphore),
 * token-bucket throttling, admission gating (network policy) and per-chunk
 * retries with exponential backoff + jitter. It has NO bundle/crypto/manifest
 * knowledge — the engine drives those and calls [fetchChunk] per pending chunk.
 */
class ChunkScheduler(
    private val transport: MediaTransportPort,
    private val semaphore: Semaphore
) {
    private val tokenLock = Mutex()
    private val bucket = TokenBucket(maxBytesPerSecond = 0L, capacityBytes = 8L * 1024 * 1024)

    private val _active = MutableStateFlow(0)
    val activeChunkCount: StateFlow<Int> = _active

    /** 0 disables throttling. */
    suspend fun setSpeedLimit(bytesPerSecond: Long) {
        tokenLock.withLock { bucket.maxBytesPerSecond = bytesPerSecond.coerceAtLeast(0L) }
    }

    /**
     * Fetch exactly one chunk's byte range, retrying on transient failures.
     * Blocks on the admission [gate] while it denies (e.g. cellular blocked).
     *
     * @return the range bytes, or null when retries are exhausted.
     */
    suspend fun fetchChunk(
        request: MediaDownloadRequest,
        chunk: ChunkRecord,
        gate: MediaAdmissionGate
    ): ByteArray? {
        var attempt = 0
        while (true) {
            awaitAdmission(gate)
            val outcome = semaphore.withPermit { fetchOnce(request, chunk) }
            when (outcome) {
                is FetchOutcome.Data -> return outcome.bytes
                FetchOutcome.Retry -> {
                    attempt++
                    if (attempt >= MEDIA_MAX_CHUNK_RETRIES) return null
                    val backoffMs = backoffMs(attempt)
                    currentCoroutineContext().ensureActive()
                    delay(backoffMs)
                }
            }
        }
    }

    private suspend fun awaitAdmission(gate: MediaAdmissionGate) {
        while (!gate.allow()) {
            currentCoroutineContext().ensureActive()
            delay(1_000L)
        }
    }

    private suspend fun fetchOnce(request: MediaDownloadRequest, chunk: ChunkRecord): FetchOutcome {
        try {
            _active.value += 1
            val end = chunk.startOffset + chunk.byteLength - 1L
            val data = transport.fetchRange(
                request.sourceUrl,
                chunk.startOffset,
                end,
                parseDownloadHeaders(request.headersJson)
            )
            if (data == null) return FetchOutcome.Retry
            tokenLock.withLock { bucket.consume(data.size.toLong()) }
            return FetchOutcome.Data(data)
        } finally {
            _active.value -= 1
        }
    }

    /** Exponential backoff with jitter: random(0.5x, 1.0x) of base * 2^attempt. */
    private fun backoffMs(attempt: Int): Long {
        val base = MEDIA_RETRY_BASE_DELAY_MS * (1L shl attempt.coerceAtMost(6))
        return Random.nextLong(base / 2, base + 1)
    }

    private sealed interface FetchOutcome {
        data class Data(val bytes: ByteArray) : FetchOutcome
        object Retry : FetchOutcome
    }
}
