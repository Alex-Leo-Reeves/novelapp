package com.alexleoreeves.novelapp.tv.mediacache

import com.alexleoreeves.novelapp.data.mediacache.DownloadManifest
import com.alexleoreeves.novelapp.data.mediacache.MEDIA_BUNDLE_EXT
import com.alexleoreeves.novelapp.data.mediacache.MEDIA_CACHE_SUBDIR
import com.alexleoreeves.novelapp.data.mediacache.MEDIA_METADATA_EXT
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A verified bundle discovered during a USB sweep. */
data class TvIndexedBundle(
    val taskId: String,
    val title: String,
    val parentId: String,
    val episodeNumber: Int,
    val sourceUrl: String,
    val totalBytes: Long,
    val bundleBytesOnDisk: Long,
    val containerExtension: String,
    val volumeId: String,
    val bundleFile: File,
    val metadataFile: File,
    val integrityOk: Boolean,
    val completedAtMs: Long,
    val indexedAtMs: Long,
    val mediaType: String = "",
    val seasonNumber: Int = 0,
    val coverUrl: String = ""
)

/**
 * Background media scanner for Smart TV dual-target storage.
 *
 * Runs entirely off the main thread ([Dispatchers.IO]) so plugging in a large
 * USB drive never blocks the UI. For every mounted volume it walks the
 * mediacache directory, pairs `<taskId>.metadata.json` completion sidecars with
 * their `<taskId>.mediabundle` files and verifies integrity by comparing the
 * on-disk ciphertext length against the sum of the chunk plan's encrypted
 * lengths (a cheap, reliable corruption check that needs no decryption).
 *
 * Results are published as a [StateFlow] the Downloads/USB screens collect.
 * Corrupt-scan semantics: a bundle whose sidecar reads but whose size mismatches
 * is surfaced with [TvIndexedBundle.integrityOk] = false so the UI can offer
 * re-download, not silently hidden.
 */
class TvMediaIndexer(private val scope: CoroutineScope) {

    private val _index = MutableStateFlow<List<TvIndexedBundle>>(emptyList())
    val index: StateFlow<List<TvIndexedBundle>> = _index

    private val scanning = AtomicBoolean(false)

    /** Re-scan all currently mounted volumes. Coalesces concurrent sweeps. */
    fun scanMounts(volumes: List<UsbVolume>) {
        if (volumes.isEmpty()) {
            _index.value = emptyList()
            return
        }
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val stamped = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    volumes.flatMap { volume -> scanVolume(volume, stamped) }
                }
                _index.value = result
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun scanVolume(volume: UsbVolume, stamped: Long): List<TvIndexedBundle> {
        val dir = File(volume.root, MEDIA_CACHE_SUBDIR)
        if (!dir.isDirectory) return emptyList()
        val sidecars = dir.listFiles { file ->
            file.isFile && file.name.endsWith(MEDIA_METADATA_EXT)
        } ?: return emptyList()

        return sidecars.mapNotNull { sidecar ->
            val taskId = sidecar.name.removeSuffix(MEDIA_METADATA_EXT)
            val manifest = decodeMetadata(sidecar) ?: return@mapNotNull null
            val bundle = File(dir, "${taskId}$MEDIA_BUNDLE_EXT")
            val expectedCipherBytes = manifest.chunks.sumOf { it.encryptedLength }
            val actualBytes = bundle.length()

            TvIndexedBundle(
                taskId = taskId,
                title = manifest.title.ifBlank { taskId },
                parentId = manifest.parentId,
                episodeNumber = manifest.episodeNumber,
                sourceUrl = manifest.sourceUrl,
                totalBytes = manifest.totalBytes,
                bundleBytesOnDisk = actualBytes,
                containerExtension = manifest.containerExtension,
                volumeId = volume.id,
                bundleFile = bundle,
                metadataFile = sidecar,
                integrityOk = bundle.exists() && actualBytes == expectedCipherBytes,
                completedAtMs = manifest.completedAtMs,
                indexedAtMs = stamped,
                mediaType = manifest.mediaType,
                seasonNumber = manifest.seasonNumber,
                coverUrl = manifest.coverUrl
            )
        }.sortedBy { it.title.lowercase() }
    }

    private fun decodeMetadata(file: File): DownloadManifest? = try {
        metadataJson.decodeFromString<DownloadManifest>(file.readText())
    } catch (e: Exception) {
        null
    }

    private companion object {
        val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
