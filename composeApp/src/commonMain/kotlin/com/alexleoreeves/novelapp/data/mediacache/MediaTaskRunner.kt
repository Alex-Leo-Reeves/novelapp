package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Outcome of driving a single download to completion or failure. */
sealed interface TaskRunResult {
    data class Success(val bundlePath: String, val manifest: DownloadManifest) : TaskRunResult
    data class Failure(val reason: DownloadFailureReason, val message: String?) : TaskRunResult
}

/**
 * Executes one download: probe → reserve → fetch/encrypt/write → checkpoint →
 * finalize. Every successful chunk is durably checkpointed into the WAL
 * manifest before the next fetch, so a crash/pause loses at most the single
 * in-flight chunk (re-fetched on resume).
 *
 * USB-target bundles are written onto the USB volume; the WAL stays internal,
 * so a task survives unmount and can resume (or be cleaned) later.
 *
 * On finalize the completed manifest is persisted as a `.metadata.json`
 * sidecar (IV seed, chunk layout, title) and the WAL is dropped. The sidecar
 * is what the USB indexer and the playback layer read to verify/decrypt a
 * finished bundle after a restart — we never lose the secret material needed
 * to open a completed download.
 *
 * Pure commonMain — the [nowMs] clock is injected so tvApp can share this
 * file without depending on the app's platform package.
 */
class MediaTaskRunner(
    private val storage: MediaStoragePort,
    private val crypto: MediaCryptoPort,
    private val transport: MediaTransportPort,
    private val scheduler: ChunkScheduler,
    private val manifests: MediaManifestStore,
    private val cleaner: MediaCacheCleaner,
    private val subtitleBundler: SubtitleBundler,
    private val gate: MediaAdmissionGate,
    private val nowMs: () -> Long,
    private val onProgress: (DownloadProgress) -> Unit
) {
    private val tagAndIvBytes = 32L + 16L

    suspend fun run(request: MediaDownloadRequest): TaskRunResult {
        if (!crypto.keysAvailable) {
            return TaskRunResult.Failure(DownloadFailureReason.UNKNOWN, "Secure key storage unavailable")
        }
        val volume = storage.resolveVolume(request.target, request.usbVolumeId)
            ?: return TaskRunResult.Failure(DownloadFailureReason.USB_UNMOUNTED, "Requested volume is not mounted")
        val bundlePath = bundlePathFor(request, volume)

        val probe = try {
            transport.probe(request.sourceUrl)
        } catch (e: Exception) {
            return TaskRunResult.Failure(DownloadFailureReason.NETWORK, e.message)
        }
        if (!probe.supportsRanges) {
            return TaskRunResult.Failure(DownloadFailureReason.NO_RANGE_SUPPORT, "Source does not support ranged downloads")
        }

        // Reserve free space — USB (FAT32/exFAT) needs 2× for allocation headroom.
        val need = requiredFreeBytes(probe.totalBytes, request.target)
        val bundleDir = bundlePath.substringBeforeLast("/")
        if (storage.freeBytes(bundleDir) < need) {
            cleaner.reclaimSpace(need, protectedTaskIds = setOf(request.taskId))
            if (storage.freeBytes(bundleDir) < need) {
                return TaskRunResult.Failure(DownloadFailureReason.STORAGE_LOW, "Insufficient storage ($need bytes required)")
            }
        }

        // Fresh plan, or resume from a prior WAL.
        val existing = manifests.load(request.taskId)
        if (existing != null && existing.target == StorageTarget.USB && existing.chunks.any { it.verified }) {
            // Verified bytes live at the OLD path — if that path isn't reachable
            // anymore (drive swapped), fail loudly instead of mixing volumes.
            if (!storage.exists(bundlePath)) {
                return TaskRunResult.Failure(DownloadFailureReason.USB_UNMOUNTED, "USB volume changed since download started")
            }
        }
        // Fetch + persist the English subtitle exactly once, at fresh-task time.
        // Resumed tasks keep the path already recorded in their WAL.
        val subtitlePath = if (existing == null) subtitleBundler.bundle(request) else ""
        val manifest = existing ?: buildManifest(request, probe, subtitlePath)
        manifests.save(manifest)

        val ivSeed = manifest.ivSeedHex.hexToBytes()
        val totalChunks = manifest.chunks.size
        if (totalChunks == 0) {
            finalize(manifest)
            return TaskRunResult.Success(bundlePath, manifest)
        }

        val writer = storage.openBundleForWrite(bundlePath)
        return try {
            var completed = manifest.chunks.count { it.verified }
            emitProgress(manifest, completed)
            val mutableChunks = manifest.chunks.toMutableList()

            for (chunk in manifest.chunks) {
                currentCoroutineContext().ensureActive()
                if (chunk.verified) continue

                val bytes = scheduler.fetchChunk(request, chunk, gate)
                    ?: return TaskRunResult.Failure(
                        DownloadFailureReason.NETWORK,
                        "Chunk ${chunk.index} failed after $MEDIA_MAX_CHUNK_RETRIES attempts"
                    )

                val encrypted = crypto.encryptChunk(bytes, ivSeed, chunk.index)
                writer.write(diskOffsetOf(mutableChunks, chunk.index), encrypted.bytes)

                mutableChunks[chunk.index] = chunk.copy(
                    verified = true,
                    sha256Hex = crypto.sha256Hex(bytes),
                    encryptedLength = encrypted.bytes.size.toLong()
                )
                writer.sync()
                manifests.save(manifest.copy(chunks = mutableChunks.toList(), updatedAtMs = nowMs()))

                completed++
                emitProgress(manifest, completed)
            }

            writer.close()
            val completedManifest = manifest.copy(chunks = mutableChunks.toList(), updatedAtMs = nowMs())
            finalize(completedManifest)
            TaskRunResult.Success(bundlePath, completedManifest)
        } catch (e: Exception) {
            writer.close()
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (isStorageFullError(e)) {
                TaskRunResult.Failure(DownloadFailureReason.STORAGE_FULL_MIDWRITE, e.message)
            } else {
                TaskRunResult.Failure(DownloadFailureReason.UNKNOWN, e.message)
            }
        }
    }

    private fun emitProgress(manifest: DownloadManifest, completed: Int) {
        val total = manifest.chunks.size
        val bytes = manifest.chunks.take(completed).sumOf { it.byteLength }
        onProgress(
            DownloadProgress(
                bytesReceived = bytes,
                chunksCompleted = completed,
                chunksTotal = total
            )
        )
    }

    private fun buildManifest(request: MediaDownloadRequest, probe: MediaProbe, subtitleBundlePath: String): DownloadManifest {
        // Free-tier 20% cap: absolute maxBytes takes priority, then maxFraction.
        val effectiveBytes = when {
            request.maxBytes in 1L until probe.totalBytes -> request.maxBytes
            request.maxFraction in 0.01f..0.99f -> (probe.totalBytes * request.maxFraction).toLong().coerceAtLeast(MEDIA_CHUNK_SIZE)
            else -> probe.totalBytes
        }
        val chunkCount = chunkCountFor(effectiveBytes)
        val chunks = List(chunkCount) { index ->
            val byteLen = chunkLengthAt(index, effectiveBytes)
            ChunkRecord(
                index = index,
                startOffset = index.toLong() * MEDIA_CHUNK_SIZE,
                byteLength = byteLen,
                encryptedLength = tagAndIvBytes + paddedCipherLen(byteLen)
            )
        }
        return DownloadManifest(
            taskId = request.taskId,
            sourceUrl = request.sourceUrl,
            totalBytes = effectiveBytes,
            containerExtension = request.containerExtension,
            target = request.target,
            usbVolumeId = request.usbVolumeId,
            bundleFileName = bundlePathFor(request, storage.resolveVolume(request.target, request.usbVolumeId)!!)
                .substringAfterLast("/"),
            title = request.title,
            parentId = request.parentId,
            episodeNumber = request.episodeNumber,
            ivSeedHex = crypto.generateIvSeed().toHex(),
            hmacKeyFingerprint = crypto.hmacKeyFingerprint(),
            chunks = chunks,
            createdAtMs = nowMs(),
            updatedAtMs = nowMs(),
            serverId = request.serverId,
            serverName = request.serverName,
            subtitleUrl = request.subtitleUrl,
            subtitleBundlePath = subtitleBundlePath,
            mediaType = request.mediaType,
            seasonNumber = request.seasonNumber,
            coverUrl = request.coverUrl,
            maxBytes = request.maxBytes
        )
    }

    /**
     * Persist the completion metadata sidecar (IV seed, chunk layout, title,
     * parent id) then drop the WAL. The sidecar is what the USB indexer and
     * the playback layer read to verify/decrypt a finished bundle after restart.
     *
     * The completion timestamp is stamped here, once, so daily-quota and
     * recency queries can filter on the actual finalize moment rather than the
     * last chunk checkpoint.
     */
    private fun finalize(completedManifest: DownloadManifest) {
        val stamped = completedManifest.copy(completedAtMs = nowMs())
        manifests.saveMetadata(stamped)
        manifests.delete(stamped.taskId)
    }

    private fun bundlePathFor(request: MediaDownloadRequest, volume: MediaVolumePath): String =
        if (request.target == StorageTarget.USB) {
            "${volume.rootAbsolutePath}/$MEDIA_CACHE_SUBDIR/${safeFileName(request.taskId)}$MEDIA_BUNDLE_EXT"
        } else {
            manifests.bundlePath(request.taskId)
        }

    /** Sum of previous chunks' on-disk lengths = absolute write offset. */
    private fun diskOffsetOf(chunks: List<ChunkRecord>, index: Int): Long {
        var offset = 0L
        for (i in 0 until index) offset += chunks[i].encryptedLength
        return offset
    }

    /** PKCS#7 padded ciphertext length: always adds a full block for aligned input. */
    private fun paddedCipherLen(byteLen: Long): Long =
        if (byteLen <= 0L) 16L else ((byteLen + 16L) / 16L) * 16L
}
