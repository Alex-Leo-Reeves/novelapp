package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.serialization.json.Json

private val manifestJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Crash-safe WAL manifest store. Every successful chunk checkpoint rewrites the
 * manifest atomically (tmp + rename), so a kill mid-download can only lose the
 * single in-flight chunk that was never acknowledged.
 *
 * Flat layout under `<cacheRoot>/mediacache/`:
 *   <taskId>.downloadstate   in-flight WAL manifest
 *   <taskId>.mediabundle     finalised ciphertext bundle
 *   <taskId>.metadata.json   completion metadata sidecar (written on finalize)
 *
 * Keeping files flat on the same volume means cleanup, reconciliation and USB
 * moves are trivial and there are no partial-directory orphans to scan.
 */
class MediaManifestStore(
    private val storage: MediaStoragePort
) {
    private val cacheRoot: MediaVolumePath
        get() = storage.cacheRoot()

    fun manifestDir(): String = "${cacheRoot.rootAbsolutePath}/$MEDIA_CACHE_SUBDIR"

    fun manifestPath(taskId: String): String =
        "${manifestDir()}/${safeFileName(taskId)}$MEDIA_MANIFEST_EXT"

    fun bundlePath(taskId: String): String =
        "${manifestDir()}/${safeFileName(taskId)}$MEDIA_BUNDLE_EXT"

    fun metadataPath(taskId: String): String =
        "${manifestDir()}/${safeFileName(taskId)}$MEDIA_METADATA_EXT"

    /** Load + decode a manifest, or null when absent/corrupt. */
    fun load(taskId: String): DownloadManifest? =
        loadFrom(manifestPath(taskId))

    /** Atomic persistence of an in-flight WAL manifest. */
    fun save(manifest: DownloadManifest): Boolean =
        saveTo(manifestPath(manifest.taskId), manifest)

    /**
     * Drop the WAL for an in-flight task. Does NOT remove the metadata sidecar,
     * so the finalize path (saveMetadata → delete) keeps playback metadata.
     */
    fun delete(taskId: String) {
        storage.delete(manifestPath(taskId))
    }

    /** Fully remove a task: WAL + metadata sidecar. Bundles are deleted by caller. */
    fun deleteTask(taskId: String) {
        storage.delete(manifestPath(taskId))
        storage.delete(metadataPath(taskId))
    }

    /** All in-flight manifests on the volume (for boot reconciliation). */
    fun listInFlight(): List<DownloadManifest> {
        storage.ensureDir(manifestDir())
        return storage.listChildren(manifestDir(), MEDIA_MANIFEST_EXT)
            .mapNotNull { path ->
                val taskId = path.substringAfterLast("/").removeSuffix(MEDIA_MANIFEST_EXT)
                load(taskId)
            }
    }

    /** Fully finalised bundles present on the cache volume (cleanup LRU). */
    fun listCompleted(): List<String> =
        storage.listChildren(manifestDir(), MEDIA_BUNDLE_EXT)

    /**
     * Completed bundles that carry a valid metadata sidecar — these are playable
     * and verifiable after restart. The USB indexer and completed-downloads UI
     * consume this list.
     */
    fun listCompletedBundles(): List<DownloadManifest> {
        storage.ensureDir(manifestDir())
        return storage.listChildren(manifestDir(), MEDIA_METADATA_EXT)
            .mapNotNull { path ->
                val taskId = path.substringAfterLast("/").removeSuffix(MEDIA_METADATA_EXT)
                loadMetadata(taskId)
            }
    }

    /**
     * Completed bundles whose metadata sidecar was finalised on or after
     * [sinceMs] — used by the daily free-download quota (5 per UTC day).
     * Bundles that carry no completion timestamp yet (pre-quota builds) are
     * counted as today so a user can't roll back an old build and replay the
     * download budget.
     */
    fun listCompletedBundlesSince(sinceMs: Long): List<DownloadManifest> =
        listCompletedBundles().filter { it.completedAtMs <= 0L || it.completedAtMs >= sinceMs }
</｜｜DSML｜｜_command>

    /**
     * Ciphertext bundles with no WAL and no metadata sidecar — abandoned partials
     * or manual deletions. Cleanup can reclaim these unconditionally.
     */
    fun listOrphanBundles(): List<String> {
        val metaIds = storage.listChildren(manifestDir(), MEDIA_METADATA_EXT)
            .map { it.substringAfterLast("/").removeSuffix(MEDIA_METADATA_EXT) }
            .toSet()
        return storage.listChildren(manifestDir(), MEDIA_BUNDLE_EXT)
            .filter { path ->
                val taskId = path.substringAfterLast("/").removeSuffix(MEDIA_BUNDLE_EXT)
                taskId !in metaIds && !storage.exists(manifestPath(taskId))
            }
    }

    fun listInFlightPaths(): List<String> =
        storage.listChildren(manifestDir(), MEDIA_MANIFEST_EXT)

    /** Persist the completion metadata sidecar (read at playback / indexing). */
    fun saveMetadata(manifest: DownloadManifest): Boolean =
        saveTo(metadataPath(manifest.taskId), manifest)

    /** Load a completed task's metadata sidecar, or null when absent/corrupt. */
    fun loadMetadata(taskId: String): DownloadManifest? =
        loadFrom(metadataPath(taskId))

    /**
     * Decode a raw metadata sidecar from an arbitrary absolute path (e.g. a
     * USB volume discovered by the indexer). Reuses the same lenient JSON so
     * every reader agrees on defaults and unknown-field tolerance.
     */
    fun decodeMetadataFile(path: String): DownloadManifest? =
        loadFrom(path)

    /** True when a finalised bundle + metadata sidecar pair exists. */
    fun isCompleted(taskId: String): Boolean =
        storage.exists(bundlePath(taskId)) && storage.exists(metadataPath(taskId))

    private fun loadFrom(path: String): DownloadManifest? {
        storage.ensureDir(manifestDir())
        val raw = storage.readBytes(path) ?: return null
        return try {
            manifestJson.decodeFromString<DownloadManifest>(raw.decodeToString())
        } catch (e: Exception) {
            // Corrupt sidecar → surface as absent; the caller decides (purge/redownload).
            null
        }
    }

    private fun saveTo(path: String, manifest: DownloadManifest): Boolean {
        return try {
            storage.ensureDir(manifestDir())
            val bytes = manifestJson.encodeToString(DownloadManifest.serializer(), manifest)
                .encodeToByteArray()
            storage.writeBytesAtomically(path, bytes)
            true
        } catch (e: Exception) {
            false
        }
    }
}
