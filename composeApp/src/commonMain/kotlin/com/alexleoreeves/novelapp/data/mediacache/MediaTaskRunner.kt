package com.alexleoreeves.novelapp.data.mediacache

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

        var hlsPlan: HlsPlan? = null
        val urlLooksHls = request.sourceUrl.substringBefore("?").endsWith(".m3u8") ||
            Regex("""/(playlist|manifest|hls)(/|$)""").containsMatchIn(request.sourceUrl)

        var probe = try {
            if (urlLooksHls) {
                val plan = loadHlsPlan(request, request.sourceUrl)
                hlsPlan = plan
                plan.toProbe()
            } else {
                transport.probe(request.sourceUrl, parseDownloadHeaders(request.headersJson))
            }
        } catch (e: HlsPlanException) {
            return TaskRunResult.Failure(DownloadFailureReason.NETWORK, e.message)
        } catch (e: Exception) {
            return TaskRunResult.Failure(DownloadFailureReason.NETWORK, e.message)
        }
        // A proxy URL (e.g. CinePro /v1/proxy?data=...) can hide a manifest:
        // when the probe reports an m3u8 content type, resolve it as HLS too.
        if (hlsPlan == null && probe.contentType.contains("mpegurl", ignoreCase = true)) {
            hlsPlan = try {
                loadHlsPlan(request, request.sourceUrl)
            } catch (e: HlsPlanException) {
                return TaskRunResult.Failure(DownloadFailureReason.NETWORK, e.message)
            } catch (e: Exception) {
                return TaskRunResult.Failure(DownloadFailureReason.NETWORK, e.message)
            }
            probe = hlsPlan!!.toProbe()
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
        val manifest = existing ?: buildManifest(request, probe, subtitlePath, hlsPlan?.assets, hlsPlan?.containerExtension)
        manifests.save(manifest)

        val ivSeed = manifest.ivSeedHex.hexToBytes()
        val totalChunks = manifest.chunks.size
        if (totalChunks == 0) {
            finalize(manifest)
            return TaskRunResult.Success(bundlePath, manifest)
        }

        // One HLS AES-128 key fetch per distinct provider key, not per segment.
        val hlsKeyCache = mutableMapOf<String, ByteArray>()

        val writer = storage.openBundleForWrite(bundlePath)
        return try {
            var completed = manifest.chunks.count { it.verified }
            emitProgress(manifest, completed)
            val mutableChunks = manifest.chunks.toMutableList()

            for (chunk in manifest.chunks) {
                currentCoroutineContext().ensureActive()
                if (chunk.verified) continue

                var bytes = scheduler.fetchChunk(request, chunk, gate)
                    ?: return TaskRunResult.Failure(
                        DownloadFailureReason.NETWORK,
                        "Chunk ${chunk.index} failed after $MEDIA_MAX_CHUNK_RETRIES attempts"
                    )

                // Provider AES-128 (EXT-X-KEY): decrypt BEFORE our own layer so
                // the finished bundle holds cleartext media and plays back.
                if (chunk.hlsKeyUrl != null) {
                    val providerKey = hlsProviderKey(hlsKeyCache, chunk.hlsKeyUrl, request.headersJson)
                        ?: return TaskRunResult.Failure(DownloadFailureReason.NETWORK, "Failed to fetch HLS encryption key")
                    val segmentIv = chunk.hlsIvHex?.hexToBytes() ?: ByteArray(16)
                    bytes = crypto.aes128CbcDecrypt(providerKey, segmentIv, bytes)
                        ?: return TaskRunResult.Failure(DownloadFailureReason.UNKNOWN, "HLS segment decryption failed")
                }
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

    private fun buildManifest(
        request: MediaDownloadRequest,
        probe: MediaProbe,
        subtitleBundlePath: String,
        hlsAssets: List<HlsAsset>? = null,
        hlsContainerExtension: String? = null
    ): DownloadManifest {
        // Free-tier 20% cap: absolute maxBytes takes priority, then maxFraction.
        val effectiveBytes = when {
            request.maxBytes in 1L until probe.totalBytes -> request.maxBytes
            request.maxFraction in 0.01f..0.99f -> (probe.totalBytes * request.maxFraction).toLong().coerceAtLeast(MEDIA_CHUNK_SIZE)
            else -> probe.totalBytes
        }
        
        val chunks = if (hlsAssets != null) {
            // One chunk per playlist asset (init segment first, then media
            // segments). Byte layout is an even split of the estimated total;
            // real sizes are recorded per chunk after each write.
            val perChunkBytes = if (hlsAssets.isNotEmpty()) {
                (probe.totalBytes / hlsAssets.size).coerceAtLeast(1L)
            } else 2_000_000L
            hlsAssets.mapIndexed { index, asset ->
                ChunkRecord(
                    index = index,
                    startOffset = index.toLong() * perChunkBytes,
                    byteLength = perChunkBytes,
                    encryptedLength = tagAndIvBytes + paddedCipherLen(perChunkBytes),
                    chunkUrl = asset.url,
                    hlsKeyUrl = asset.keyUrl,
                    hlsIvHex = asset.ivHex
                )
            }
        } else {
            val chunkCount = chunkCountFor(effectiveBytes)
            List(chunkCount) { index ->
                val byteLen = chunkLengthAt(index, effectiveBytes)
                ChunkRecord(
                    index = index,
                    startOffset = index.toLong() * MEDIA_CHUNK_SIZE,
                    byteLength = byteLen,
                    encryptedLength = tagAndIvBytes + paddedCipherLen(byteLen)
                )
            }
        }
        return DownloadManifest(
            taskId = request.taskId,
            sourceUrl = request.sourceUrl,
            totalBytes = effectiveBytes,
            containerExtension = hlsContainerExtension ?: request.containerExtension,
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
            maxBytes = request.maxBytes,
            headersJson = request.headersJson
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

    // ── HLS plan loading ─────────────────────────────────────────────────────

    /**
     * Fetch + parse [playlistUrl] into a downloadable plan. Master playlists
     * resolve to their first media variant (parity with the phone's Android/
     * iOS HLS downloaders); provider AES-128 keys/IVs are attached to every
     * segment so the fetch loop can decrypt before packing into the bundle.
     */
    private suspend fun loadHlsPlan(request: MediaDownloadRequest, playlistUrl: String): HlsPlan {
        val headers = parseDownloadHeaders(request.headersJson)
        var url = playlistUrl
        var text = fetchHlsText(url, headers)
            ?: throw HlsPlanException("Failed to fetch HLS playlist")
        repeat(3) {
            when (val parsed = parseHlsPlaylist(text, url)) {
                is HlsParseResult.Unsupported -> throw HlsPlanException(parsed.reason)
                is HlsParseResult.Master -> {
                    url = parsed.variantUrl
                    text = fetchHlsText(url, headers)
                        ?: throw HlsPlanException("Failed to fetch HLS variant playlist")
                }
                is HlsParseResult.Media -> {
                    if (parsed.assets.isEmpty()) throw HlsPlanException("Empty HLS playlist")
                    val isMp4 = parsed.hasInitSegment ||
                        parsed.assets.any { it.url.substringBefore("?").endsWith(".mp4") }
                    // ~2 Mbps placeholder for progress/free-space reservation;
                    // the engine records real sizes after each chunk write.
                    val totalBytes = if (parsed.totalDurationSec > 0.0) {
                        (parsed.totalDurationSec * 250_000.0).toLong()
                            .coerceAtLeast(parsed.assets.size * 100_000L)
                    } else {
                        parsed.assets.size * 2_000_000L
                    }
                    return HlsPlan(
                        assets = parsed.assets,
                        totalBytes = totalBytes,
                        containerExtension = if (isMp4) "mp4" else "ts"
                    )
                }
            }
        }
        throw HlsPlanException("HLS playlist nesting is too deep")
    }

    private suspend fun fetchHlsText(url: String, headers: Map<String, String>): String? {
        repeat(2) { attempt ->
            val bytes = runCatching { transport.fetchFull(url, headers) }.getOrNull()
            if (bytes != null) return bytes.decodeToString()
            if (attempt == 0) delay(500L)
        }
        return null
    }

    /** Fetch (with one retry) and cache a provider HLS AES-128 key — 16 bytes. */
    private suspend fun hlsProviderKey(
        cache: MutableMap<String, ByteArray>,
        keyUrl: String,
        headersJson: String
    ): ByteArray? {
        cache[keyUrl]?.let { return it }
        val headers = parseDownloadHeaders(headersJson)
        repeat(2) { attempt ->
            val bytes = runCatching { transport.fetchFull(keyUrl, headers) }.getOrNull()
            if (bytes != null && bytes.size >= 16) {
                val key = bytes.copyOf(16)
                cache[keyUrl] = key
                return key
            }
            if (attempt == 0) delay(500L)
        }
        return null
    }
}

// ── HLS plan (playlist → downloadable assets) ─────────────────────────────────

/** Thrown when a playlist cannot become a downloadable plan. */
private class HlsPlanException(message: String) : Exception(message)

/**
 * One playlist asset (an EXT-X-MAP init segment or a media segment) plus the
 * provider AES-128 key state in effect for it. [ivHex] is the 32-char IV the
 * segment must be decrypted with (explicit IV, else derived from the media
 * sequence number) — null when the playlist is unencrypted.
 */
private data class HlsAsset(
    val url: String,
    val keyUrl: String? = null,
    val ivHex: String? = null
)

/** Resolved, size-estimated plan for one HLS download. */
private data class HlsPlan(
    val assets: List<HlsAsset>,
    val totalBytes: Long,
    val containerExtension: String
) {
    fun toProbe() = MediaProbe(
        totalBytes = totalBytes,
        supportsRanges = true, // Chunks bypass ranges via chunkUrl
        contentType = if (containerExtension == "mp4") "video/mp4" else "video/mp2t"
    )
}

private sealed interface HlsParseResult {
    /** Master playlist: pick a variant and re-parse it. */
    data class Master(val variantUrl: String) : HlsParseResult

    /** Media playlist ready for download. */
    data class Media(
        val assets: List<HlsAsset>,
        val totalDurationSec: Double,
        val hasInitSegment: Boolean
    ) : HlsParseResult

    /** Encryption we cannot decrypt client-side — fail loudly, not silently. */
    data class Unsupported(val reason: String) : HlsParseResult
}

/**
 * Parse one HLS playlist document. Handles master→variant selection,
 * EXT-X-MEDIA-SEQUENCE / EXT-X-KEY (AES-128 + IV), EXT-X-MAP init segments
 * and relative URL resolution (bare, /rooted, ./ and ../ paths).
 */
private fun parseHlsPlaylist(text: String, playlistUrl: String): HlsParseResult {
    var mediaSequence = 0L
    var currentKeyUrl: String? = null
    var currentIvHex: String? = null
    var initSegment: HlsAsset? = null
    var hasInitSegment = false
    var pendingDurationSec = 0.0
    var segmentOrdinal = 0
    var totalDurationSec = 0.0
    var masterVariantUrl: String? = null
    var sawStreamInf = false
    val assets = mutableListOf<HlsAsset>()

    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            sawStreamInf = true
            continue
        }
        if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
            mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            continue
        }
        if (line.startsWith("#EXT-X-KEY:")) {
            val attrs = line.substringAfter(':')
            val method = readHlsAttr(attrs, "METHOD").orEmpty()
            when {
                method.isEmpty() -> return HlsParseResult.Unsupported("HLS EXT-X-KEY is missing METHOD")
                method.equals("NONE", ignoreCase = true) -> {
                    currentKeyUrl = null
                    currentIvHex = null
                }
                method.equals("AES-128", ignoreCase = true) -> {
                    val uri = readHlsAttr(attrs, "URI")
                        ?: return HlsParseResult.Unsupported("HLS AES-128 key is missing a URI")
                    currentKeyUrl = resolveHlsUrl(playlistUrl, uri)
                    currentIvHex = readHlsAttr(attrs, "IV")
                        ?.removePrefix("0x")?.removePrefix("0X")?.lowercase()
                }
                else -> return HlsParseResult.Unsupported("Unsupported HLS encryption ($method)")
            }
            continue
        }
        if (line.startsWith("#EXT-X-BYTERANGE:")) {
            // Segments carved from one file by byte range cannot be fetched via
            // full-URL fetches — fail loudly instead of packing corrupt bytes.
            return HlsParseResult.Unsupported("HLS byte-range segments are not supported")
        }
        if (line.startsWith("#EXT-X-MAP:")) {
            val uri = readHlsAttr(line.substringAfter(':'), "URI")
            if (uri != null) {
                hasInitSegment = true
                initSegment = HlsAsset(
                    url = resolveHlsUrl(playlistUrl, uri),
                    keyUrl = currentKeyUrl,
                    // Same IV rule as a media segment: explicit IV if given,
                    // else the sequence number of the segment that follows.
                    ivHex = currentIvHex
                        ?: (mediaSequence + segmentOrdinal).toString(16).padStart(32, '0')
                )
            }
            continue
        }
        if (line.startsWith("#EXTINF:")) {
            pendingDurationSec = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
            continue
        }
        if (line.startsWith("#")) continue

        if (sawStreamInf) {
            if (masterVariantUrl == null) masterVariantUrl = resolveHlsUrl(playlistUrl, line)
            continue
        }

        val ivHex = currentIvHex ?: (mediaSequence + segmentOrdinal).toString(16).padStart(32, '0')
        assets.add(
            HlsAsset(
                url = resolveHlsUrl(playlistUrl, line),
                keyUrl = currentKeyUrl,
                ivHex = if (currentKeyUrl != null) ivHex else null
            )
        )
        segmentOrdinal++
        totalDurationSec += pendingDurationSec
        pendingDurationSec = 0.0
    }

    if (sawStreamInf) {
        return masterVariantUrl?.let { HlsParseResult.Master(it) }
            ?: HlsParseResult.Unsupported("HLS master playlist advertises no variants")
    }
    if (assets.isNotEmpty()) initSegment?.let { assets.add(0, it) }
    return HlsParseResult.Media(assets, totalDurationSec, hasInitSegment)
}

/** Read `KEY="value"` / `KEY=value` from a comma-separated HLS attribute list. */
private fun readHlsAttr(attrs: String, key: String): String? {
    val pattern = Regex("""(?:^|,)\s*${Regex.escape(key)}\s*=\s*("(?:[^"]*)"|[^,]*)""")
    val value = pattern.find(attrs)?.groupValues?.get(1)?.trim() ?: return null
    return value.removeSurrounding("\"").takeIf { it.isNotEmpty() }
}

/** Resolve a playlist-relative reference (bare, /rooted, ./, ../) against the playlist URL. */
private fun resolveHlsUrl(baseUrl: String, ref: String): String {
    val trimmed = ref.trim()
    if (trimmed.isEmpty()) return baseUrl
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd < 0) return trimmed
    if (trimmed.startsWith("//")) return baseUrl.substring(0, schemeEnd + 1) + trimmed
    val scheme = baseUrl.substring(0, schemeEnd)
    val afterScheme = baseUrl.substring(schemeEnd + 3)
    val hostPart = afterScheme.substringBefore("/")
    val basePath = "/" + afterScheme.substringAfter("/", "").substringBeforeLast("/")
    if (trimmed.startsWith("/")) return "$scheme://$hostPart$trimmed"
    val segments = basePath.trim('/').split('/').filter { it.isNotEmpty() }.toMutableList()
    for (piece in trimmed.split('/')) {
        when {
            piece.isEmpty() || piece == "." -> Unit
            piece == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(piece)
        }
    }
    return "$scheme://$hostPart/" + segments.joinToString("/")
}
