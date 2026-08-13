package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
//  Media Cache — core models, task FSM and persisted manifest.
//
//  This package lives under `data/` so tvApp (which shares
//  composeApp/.../novelapp/data as a source dir) reuses it. Therefore it MUST
//  stay pure Kotlin — no `expect`/`actual`, no platform imports. All platform
//  behaviour (crypto, I/O, network) enters through injected ports.
// ─────────────────────────────────────────────────────────────────────────────

/** Fixed logical chunk size in bytes (4 MiB). */
const val MEDIA_CHUNK_SIZE: Long = 4L * 1024 * 1024

/** Maximum simultaneous chunk transfers across the whole engine. */
const val MEDIA_MAX_CONCURRENT_CHUNKS: Int = 3

/** Max automatic retries per chunk before the task fails. */
const val MEDIA_MAX_CHUNK_RETRIES: Int = 5

/** Initial backoff before first retry (ms). Doubles with jitter on each retry. */
const val MEDIA_RETRY_BASE_DELAY_MS: Long = 1_000L

/** Safety reserve: engine refuses to start a download unless this much free space
 *  remains after the file is allocated (2 × content size, see [reserveRequirement]). */
const val MEDIA_SAFETY_RESERVE_BYTES: Long = 256L * 1024 * 1024

/** Where download bundles + manifests live, relative to the platform cache root. */
const val MEDIA_CACHE_SUBDIR = "mediacache"

/** Extension of an encrypted, finalized media bundle. */
const val MEDIA_BUNDLE_EXT = ".mediabundle"

/** Extension of the WAL manifest for an in-flight task. */
const val MEDIA_MANIFEST_EXT = ".downloadstate"

/** Extension of the completion metadata sidecar (read at playback / indexing). */
const val MEDIA_METADATA_EXT = ".metadata.json"

/** Extension of the bundled English subtitle track stored beside a bundle. */
const val MEDIA_SUBTITLE_EXT = ".srt"

// ─────────────────────────────────────────────────────────────────────────────
//  Requests & targets
// ─────────────────────────────────────────────────────────────────────────────

/** Which volume a download targets. USB is only meaningful on Smart TV builds. */
enum class StorageTarget {
    INTERNAL,
    USB
}

data class MediaDownloadRequest(
    val taskId: String,
    val sourceUrl: String,
    val title: String,
    val parentId: String,             // content id (anilist_12345 etc.)
    val episodeNumber: Int = 0,
    val containerExtension: String = "mp4",
    val target: StorageTarget = StorageTarget.INTERNAL,
    val usbVolumeId: String? = null,  // required when target == USB
    val priority: Int = 0,            // lower = more urgent
    val serverId: String = "",        // selected server key (anime_server_3, stream_server_5…)
    val serverName: String = "",      // human label ("Server 3 (AniKoto)")
    val subtitleUrl: String = ""      // candidate English .srt URL bundled on download
)

/** Result of probing a source URL (HEAD + first range request). */
data class MediaProbe(
    val totalBytes: Long,
    val supportsRanges: Boolean,
    val contentType: String,
    val variants: List<QualityVariant> = emptyList()
)

/** Multi-resolution variant advertised by an adaptive source, if any. */
@Serializable
data class QualityVariant(
    val url: String,
    val label: String,       // "1080p", "720p", …
    val width: Int,
    val height: Int,
    val bitrateBps: Long
)

// ─────────────────────────────────────────────────────────────────────────────
//  Task state machine
// ─────────────────────────────────────────────────────────────────────────────

enum class DownloadPhase {
    QUEUED,
    PROBING,
    PREPARING,        // pre-allocation + reserve check
    FETCHING,
    VERIFYING,
    FINALIZING,
    COMPLETED,
    PAUSED,
    FAILED
}

enum class DownloadFailureReason {
    NETWORK,
    NO_RANGE_SUPPORT,
    STORAGE_LOW,
    STORAGE_FULL_MIDWRITE,
    USB_UNMOUNTED,
    VERIFY_FAILED,
    CANCELLED,
    UNKNOWN
}

data class DownloadProgress(
    val bytesReceived: Long = 0L,
    val chunksCompleted: Int = 0,
    val chunksTotal: Int = 0,
    val bytesPerSecond: Long = 0L
)

data class DownloadTask(
    val request: MediaDownloadRequest,
    val phase: DownloadPhase,
    val probe: MediaProbe? = null,
    val progress: DownloadProgress = DownloadProgress(),
    val failureReason: DownloadFailureReason? = null,
    val errorMessage: String? = null,
    val manifestPath: String? = null,
    val bundlePath: String? = null,
    val updatedAtMs: Long = 0L
) {
    val fraction: Float
        get() = if (progress.chunksTotal > 0) {
            (progress.chunksCompleted.toFloat() / progress.chunksTotal).coerceIn(0f, 1f)
        } else 0f

    val isTerminal: Boolean
        get() = phase == DownloadPhase.COMPLETED || phase == DownloadPhase.FAILED
}

// ─────────────────────────────────────────────────────────────────────────────
//  Persisted WAL manifest (per task)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Crash-safe state for one in-flight download. Rewritten atomically after every
 * completed chunk. On boot the engine reconciles: a manifest with only complete
 * chunks is resumed, anything else is purged.
 */
@Serializable
data class ChunkRecord(
    val index: Int,
    val startOffset: Long,       // plaintext byte offset within the source file
    val byteLength: Long,        // plaintext length (last chunk may be short)
    val encryptedLength: Long,   // on-disk length ([tag][iv][ciphertext])
    val sha256Hex: String = "",  // plaintext digest (integrity sweep)
    val verified: Boolean = false
)

@Serializable
data class DownloadManifest(
    val schemaVersion: Int = 1,
    val taskId: String,
    val sourceUrl: String,
    val totalBytes: Long,
    val containerExtension: String,
    val target: StorageTarget,
    val usbVolumeId: String? = null,
    val bundleFileName: String,
    val title: String = "",
    val parentId: String = "",
    val episodeNumber: Int = 0,
    val ivSeedHex: String = "",        // per-task IV seed (16 bytes, hex)
    val hmacKeyFingerprint: String = "",
    val chunks: List<ChunkRecord> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val serverId: String = "",         // selected server key (persisted for replay)
    val serverName: String = "",       // human label ("Server 3 (AniKoto)")
    val subtitleUrl: String = "",      // English .srt source URL, if downloaded
    val subtitleBundlePath: String = "", // absolute path of the bundled .srt sidecar
    val completedAtMs: Long = 0L       // epoch ms when the bundle finished (0 = not yet completed)
)


// ─────────────────────────────────────────────────────────────────────────────
//  Engine commands (typed so the UI can't send garbage)
// ─────────────────────────────────────────────────────────────────────────────

sealed interface DownloadCommand {
    data class Enqueue(val request: MediaDownloadRequest) : DownloadCommand
    data class Pause(val taskId: String) : DownloadCommand
    data class Resume(val taskId: String) : DownloadCommand
    data class Cancel(val taskId: String) : DownloadCommand
    data class Remove(val taskId: String) : DownloadCommand   // cancel + delete bundle
    data class SetCellularAllowed(val allowed: Boolean) : DownloadCommand
}
