package com.alexleoreeves.novelapp.data.mediacache

/**
 * Fetches and persists the English subtitle track for one download.
 *
 * Lifecycle contract:
 *  - Called exactly ONCE per task, at manifest-build time (never on resume),
 *    so the `.srt` is fetched alongside the initial chunk plan.
 *  - The subtitle lands in the flat, internal cache dir as `<taskId>.srt` and
 *    its absolute path is recorded in the WAL manifest + completion metadata
 *    sidecar ([DownloadManifest.subtitleBundlePath]). The player layer reads
 *    that path at local-playback time and attaches the file as a subtitle
 *    slave — no network re-resolution required for offline playback.
 *  - Subtitles always live on the internal volume (next to the metadata
 *    sidecar), even when the video bundle targets USB: the sidecar is internal,
 *    so keeping the subtitle on the same volume keeps path resolution uniform
 *    and crash-safe.
 *
 * Pure commonMain — network rides [MediaTransportPort.fetchFull], bytes land
 * via [MediaStoragePort.writeBytesAtomically].
 */
class SubtitleBundler(
    private val transport: MediaTransportPort,
    private val storage: MediaStoragePort,
    private val manifests: MediaManifestStore
) {
    /**
     * Download + persist the subtitle for [request]. Returns the absolute path
     * of the stored `.srt`, or "" when no subtitle URL was supplied, the fetch
     * failed, or the payload was unusable (empty / oversized).
     */
    suspend fun bundle(request: MediaDownloadRequest): String {
        val sourceUrl = request.subtitleUrl.trim()
        if (sourceUrl.isEmpty()) return ""

        val payload = try {
            transport.fetchFull(sourceUrl, parseDownloadHeaders(request.headersJson))
        } catch (e: Exception) {
            null
        } ?: return ""

        if (payload.isEmpty() || payload.size > MEDIA_SUBTITLE_MAX_BYTES) return ""

        val path = subtitlePathFor(manifests.manifestDir(), request.taskId)
        return try {
            storage.ensureDir(manifests.manifestDir())
            storage.writeBytesAtomically(path, payload)
            path
        } catch (e: Exception) {
            ""
        }
    }

    /** Best-effort removal of a task's bundled `.srt`. Caller owns bundle+meta. */
    fun deleteSubtitle(taskId: String) {
        storage.delete(subtitlePathFor(manifests.manifestDir(), taskId))
    }
}

/** Generous cap for a bundled .srt — real files are usually < 200 KB. */
const val MEDIA_SUBTITLE_MAX_BYTES: Int = 8 * 1024 * 1024

/** Absolute path of a task's bundled subtitle sidecar in the flat cache dir. */
fun subtitlePathFor(manifestDir: String, taskId: String): String =
    "$manifestDir/${safeFileName(taskId)}$MEDIA_SUBTITLE_EXT"
