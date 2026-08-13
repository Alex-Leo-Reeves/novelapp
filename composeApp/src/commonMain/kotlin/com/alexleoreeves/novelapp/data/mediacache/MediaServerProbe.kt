package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.system.getTimeMillis

// ─────────────────────────────────────────────────────────────────────────────
//  Media Server Selection & Health Check
//
//  Before ANY download is enqueued, the UI presents a server-selection modal
//  driven by this probe layer. Each candidate URL is pre-flighted:
//   1. reachable            (probe() returns without throwing)
//   2. range-capable        (supportsRanges — the engine REQUIRES ranged GETs)
//   3. non-zero size        (totalBytes > 0 — rejects empty/dead streams)
//   4. latency-ranked       (probe elapsed ms — fastest healthy server wins)
//
//  Dead/failed candidates are surfaced with [MediaServerProbeStatus] so the
//  dialog can offer instant fallback to the next-healthy server. The winning
//  result is passed into the download request so the engine records exactly
//  which server produced the bundle.
//
//  Pure commonMain: probing rides the injected [MediaTransportPort], the same
//  one the chunk scheduler uses, so no platform-specific network code lives
//  here.
// ─────────────────────────────────────────────────────────────────────────────

enum class MediaServerProbeStatus {
    HEALTHY,       // reachable, range-capable, non-zero size
    UNREACHABLE,   // network error / timeout during probe
    NO_RANGE,      // reachable but does not support byte ranges
    EMPTY,         // reachable but zero/unknown size
    UNKNOWN        // not yet probed (UI placeholder state)
}

/** Health verdict for one candidate server. */
data class MediaServerProbeResult(
    val serverId: String,
    val serverName: String,
    val url: String,
    val status: MediaServerProbeStatus,
    val totalBytes: Long = 0L,
    val probeLatencyMs: Long = 0L,
    val contentType: String = ""
) {
    /** Whether this candidate may be safely handed to the download queue. */
    val isHealthy: Boolean get() = status == MediaServerProbeStatus.HEALTHY
}

/**
 * Probes a set of candidate server URLs and returns their health verdicts
 * sorted best-first (healthy + fastest latency first, dead last).
 */
class MediaServerProbe(
    private val transport: MediaTransportPort
) {
    /**
     * Probe all [candidates] concurrently. Returns results best-first:
     * healthy + fastest latency first, dead candidates last.
     */
    suspend fun probeServers(candidates: List<MediaServerCandidate>): List<MediaServerProbeResult> {
        if (candidates.isEmpty()) return emptyList()
        return coroutineScope {
            candidates.map { candidate ->
                async { probeOne(candidate) }
            }.awaitAll().withIndex().sortedWith(
                compareByDescending<IndexedValue<MediaServerProbeResult>> { it.value.isHealthy }
                    .thenBy { if (it.value.isHealthy) it.value.probeLatencyMs else Long.MAX_VALUE }
                    .thenBy { it.index }
            ).map { it.value }
        }
    }

    /** Probe a single candidate. Blocks until the transport's timeout settles. */
    suspend fun probeOne(candidate: MediaServerCandidate): MediaServerProbeResult {
        if (candidate.url.isBlank()) {
            return MediaServerProbeResult(
                serverId = candidate.serverId,
                serverName = candidate.serverName,
                url = candidate.url,
                status = MediaServerProbeStatus.EMPTY
            )
        }
        val started = getTimeMillis()
        return try {
            val probe = transport.probe(candidate.url)
            val latency = (getTimeMillis() - started).coerceAtLeast(0L)
            when {
                !probe.supportsRanges -> MediaServerProbeResult(
                    candidate.serverId, candidate.serverName, candidate.url,
                    MediaServerProbeStatus.NO_RANGE, probe.totalBytes, latency, probe.contentType
                )
                probe.totalBytes <= 0L -> MediaServerProbeResult(
                    candidate.serverId, candidate.serverName, candidate.url,
                    MediaServerProbeStatus.EMPTY, probe.totalBytes, latency, probe.contentType
                )
                else -> MediaServerProbeResult(
                    candidate.serverId, candidate.serverName, candidate.url,
                    MediaServerProbeStatus.HEALTHY, probe.totalBytes, latency, probe.contentType
                )
            }
        } catch (e: Exception) {
            MediaServerProbeResult(
                candidate.serverId, candidate.serverName, candidate.url,
                MediaServerProbeStatus.UNREACHABLE
            )
        }
    }

    /** Pick the first healthy candidate, or null when none passes the gate. */
    suspend fun pickHealthy(candidates: List<MediaServerCandidate>): MediaServerProbeResult? =
        probeServers(candidates).firstOrNull { it.isHealthy }
}

/** One selectable server candidate for a download. */
data class MediaServerCandidate(
    val serverId: String,       // "stream_server_3", "anime_server_5", "donghua_server_2"
    val serverName: String,     // "Server 3 (AniKoto)"
    val url: String             // resolved direct/embed stream URL to pre-flight
)
