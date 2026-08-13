package com.alexleoreeves.novelapp.data.mediacache

/**
 * Policy-driven cache eviction. Runs off the main thread (engine dispatcher).
 *
 * Three levers, in priority order:
 *  1. Hard free-space floor — keeps [MEDIA_SAFETY_RESERVE_BYTES] free.
 *  2. LRU — evicts least-recently-used completed bundles.
 *  3. Stale in-flight manifests — purges abandoned WAL files older than
 *     [STALE_INFLIGHT_TTL_MS] (crash leftovers the engine never reconciled).
 *
 * Never deletes a task the engine has marked busy; the caller passes
 * [protectedTaskIds] (active downloads) and this policy skips them.
 */
class MediaCacheCleaner(
    private val storage: MediaStoragePort,
    private val manifests: MediaManifestStore
) {
    private val root: MediaVolumePath
        get() = storage.cacheRoot()

    /** Delete completed bundles until at least [requiredBytes] is available. */
    fun reclaimSpace(
        requiredBytes: Long,
        protectedTaskIds: Set<String> = emptySet()
    ): Long {
        val targetFree = requiredBytes + MEDIA_SAFETY_RESERVE_BYTES
        var freed = 0L
        if (storage.freeBytes(root.rootAbsolutePath) >= targetFree) return freed

        val candidates = manifests.listCompleted()
            .filter { path ->
                val taskId = path.substringAfterLast("/").removeSuffix(MEDIA_BUNDLE_EXT)
                taskId !in protectedTaskIds
            }
            .sortedBy { storage.lastModified(it) }    // LRU first

        for (bundle in candidates) {
            if (storage.freeBytes(root.rootAbsolutePath) >= targetFree) break
            val size = storage.bundleSize(bundle)
            storage.delete(bundle)
            freed += size
        }
        return freed
    }

    /**
     * Purge WAL manifests that are abandoned (no engine task references them)
     * and older than the TTL. Returns bytes freed.
     */
    fun purgeStaleInFlight(
        nowMs: Long,
        protectedTaskIds: Set<String> = emptySet(),
        ttlMs: Long = STALE_INFLIGHT_TTL_MS
    ): Long {
        var freed = 0L
        manifests.listInFlightPaths().forEach { path ->
            val taskId = path.substringAfterLast("/").removeSuffix(MEDIA_MANIFEST_EXT)
            if (taskId in protectedTaskIds) return@forEach
            val modified = storage.lastModified(path)
            if (modified > 0L && nowMs - modified > ttlMs) {
                freed += storage.bundleSize(manifests.bundlePath(taskId))
                storage.delete(path)
                storage.delete(manifests.bundlePath(taskId))
            }
        }
        return freed
    }

    /** Age-out TTL for abandoned in-flight manifests. */
    private companion object {
        const val STALE_INFLIGHT_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L // 7 days
    }
}
