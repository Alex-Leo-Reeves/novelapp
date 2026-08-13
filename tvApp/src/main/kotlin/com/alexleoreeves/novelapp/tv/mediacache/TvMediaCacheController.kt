package com.alexleoreeves.novelapp.tv.mediacache

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.alexleoreeves.novelapp.data.mediacache.DownloadCommand
import com.alexleoreeves.novelapp.data.mediacache.DownloadEngine
import com.alexleoreeves.novelapp.data.mediacache.DownloadTask
import com.alexleoreeves.novelapp.data.mediacache.MEDIA_BUNDLE_EXT
import com.alexleoreeves.novelapp.data.mediacache.MEDIA_CACHE_SUBDIR
import com.alexleoreeves.novelapp.data.mediacache.MediaAccessPolicy
import com.alexleoreeves.novelapp.data.mediacache.MediaDownloadRequest
import com.alexleoreeves.novelapp.data.mediacache.MediaSource
</｜｜DSML｜｜_command>
import com.alexleoreeves.novelapp.data.mediacache.MediaStreamOpener
import com.alexleoreeves.novelapp.data.mediacache.StorageTarget
import com.alexleoreeves.novelapp.data.mediacache.safeFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * TV-side façade for the offline media cache.
 *
 * Constructs the port layer (Android Keystore crypto, internal+USB storage,
 * OkHttp transport), boots the [DownloadEngine], and owns the [TvUsbVolumeMonitor]
 * + [TvMediaIndexer] lifecycle. Every mount/unmount of a USB drive triggers a
 * background re-scan so the Downloads/USB screen stays fresh without blocking
 * the main thread.
 *
 * Instantiate in the activity, call [start] in onStart and [stop] in onStop.
 */
class TvMediaCacheController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val usbMonitor = TvUsbVolumeMonitor(context.applicationContext)

    // Shared port instances — the engine, the ITFF server probe and the local
    // bundle decoder all ride the same crypto/storage/transport stack so a
    // single keystore provisioning and one OkHttp client serve the app.
    private val crypto = TvMediaCryptoProvider()
    private val storage = TvMediaStoragePort(context.applicationContext) { usbMonitor.volumes.value }

    // Eager-close the transport when the engine is created (close is not part
    // of the port interface, so keep a concrete ref for teardown).
    private val transport = TvMediaTransportPort()

    val engine: DownloadEngine by lazy {
        DownloadEngine(
            storage = storage,
            crypto = crypto,
            transport = transport,
            // Smart TVs are WiFi/Ethernet — never cellular, so the Wi-Fi gate
            // is always open unless the user explicitly disables downloads.
            isCellularActive = { false },
            nowMs = { System.currentTimeMillis() },
            scope = scope
        )
    }
    val indexer = TvMediaIndexer(scope)

    /** Local-bundle decoder — turns a completed bundle into a plaintext stream. */
    val bundleOpener: MediaStreamOpener by lazy { TvBundleMediaOpener(crypto) }

    /** Loopback HTTP server handing decrypted bundle bytes to LibVLC. */
    private val loopbackServer = TvLoopbackMediaServer()

    /** Live task map from the engine — UI collects this. */
    val tasks: StateFlow<Map<String, DownloadTask>> = engine.tasks

    /** Indexed bundles discovered on mounted USB volumes. */
    val usbIndex: StateFlow<List<TvIndexedBundle>> = indexer.index

    /** Mounted removable volumes. */
    val volumes: StateFlow<List<UsbVolume>> = usbMonitor.volumes

    fun start() {
        usbMonitor.start()
        scope.launch {
            usbMonitor.volumes.collect { mounted ->
                indexer.scanMounts(mounted)
            }
        }
        engine.start()
    }

    fun stop() {
        usbMonitor.stop()
        scope.cancel()
        transport.close()
    }

    // ── UI entry points ────────────────────────────────────────────────────

    fun enqueueInternal(
        taskId: String,
        sourceUrl: String,
        title: String,
        parentId: String,
        episodeNumber: Int,
        containerExtension: String,
        serverId: String = "",
        serverName: String = "",
        subtitleUrl: String = ""
    ) {
        engine.send(
            DownloadCommand.Enqueue(
                MediaDownloadRequest(
                    taskId = taskId,
                    sourceUrl = sourceUrl,
                    title = title,
                    parentId = parentId,
                    episodeNumber = episodeNumber,
                    containerExtension = containerExtension,
                    target = StorageTarget.INTERNAL,
                    serverId = serverId,
                    serverName = serverName,
                    subtitleUrl = subtitleUrl
                )
            )
        )
    }

    /**
     * Enqueue to a mounted USB volume. Fails fast (returns false) when the
     * volume id isn't currently mounted — the engine's runner would otherwise
     * fail with USB_UNMOUNTED mid-flight.
     */
    fun enqueueUsb(
        taskId: String,
        sourceUrl: String,
        title: String,
        parentId: String,
        episodeNumber: Int,
        containerExtension: String,
        usbVolumeId: String,
        serverId: String = "",
        serverName: String = "",
        subtitleUrl: String = ""
    ): Boolean {
        val mounted = usbMonitor.volumes.value.any { it.id == usbVolumeId }
        if (!mounted) return false
        engine.send(
            DownloadCommand.Enqueue(
                MediaDownloadRequest(
                    taskId = taskId,
                    sourceUrl = sourceUrl,
                    title = title,
                    parentId = parentId,
                    episodeNumber = episodeNumber,
                    containerExtension = containerExtension,
                    target = StorageTarget.USB,
                    usbVolumeId = usbVolumeId,
                    serverId = serverId,
                    serverName = serverName,
                    subtitleUrl = subtitleUrl
                )
            )
        )
        return true
    }

    fun pause(taskId: String) = engine.send(DownloadCommand.Pause(taskId))
    fun resume(taskId: String) = engine.send(DownloadCommand.Resume(taskId))
    fun cancel(taskId: String) = engine.send(DownloadCommand.Cancel(taskId))
    fun remove(taskId: String) = engine.send(DownloadCommand.Remove(taskId))
    fun setCellularAllowed(allowed: Boolean) = engine.send(DownloadCommand.SetCellularAllowed(allowed))

    suspend fun setSpeedLimit(bytesPerSecond: Long) = engine.setSpeedLimit(bytesPerSecond)

    // ── P5b: local playback + quota exposure ───────────────────────────────

    /**
     * Number of media downloads completed since the start of the current UTC
     * day — the value a free user's daily quota (5 per day) consumes.
     *
     * Internal volume: metadata sidecars finalised today (legacy sidecars with
     * no completion timestamp count as today so a downgrade can't replay the
     * budget). USB volume: integrity-verified bundles whose sidecar was
     * finalised today. Only *actually completed* files consume a slot — failed
     * or cancelled downloads never burn one.
     */
    fun completedDownloadsCount(): Int {
        val dayStart = MediaAccessPolicy.startOfEpochDayMs(System.currentTimeMillis())
        val internalToday = engine.completedDownloadsSince(dayStart)
        val usbToday = usbIndex.value.count {
            it.integrityOk && (it.completedAtMs <= 0L || it.completedAtMs >= dayStart)
        }
        return internalToday + usbToday
    }
</｜｜DSML｜｜_command>

    /** Completed bundles currently stored on the internal volume. */
    fun listCompletedInternal(): List<com.alexleoreeves.novelapp.data.mediacache.DownloadManifest> =
        engine.listCompletedBundles()

    /** Decode a USB metadata sidecar discovered by the indexer. */
    fun decodeUsbMetadata(bundle: TvIndexedBundle): com.alexleoreeves.novelapp.data.mediacache.DownloadManifest? =
        engine.decodeCompletedMetadata(bundle.metadataFile.absolutePath)

    /**
     * Delete a USB bundle + its metadata sidecar from the mounted volume.
     * The engine only tracks internal volumes; USB files are indexed read-only.
     */
    fun removeUsbBundle(bundle: TvIndexedBundle) {
        val volume = usbMonitor.volumes.value.firstOrNull { it.id == bundle.volumeId } ?: return
        val dir = "${volume.root.absolutePath}/$MEDIA_CACHE_SUBDIR"
        storage.delete("$dir/${bundle.bundleFile.name}")
        storage.delete("$dir/${bundle.metadataFile.name}")
        scope.launch { indexer.scanMounts(usbMonitor.volumes.value) }
    }

    /** Resolve a [MediaSource] for an internal completed download, or null. */
    fun internalSourceFor(taskId: String): MediaSource? {
        val manifest = engine.loadCompletedMetadata(taskId) ?: return null
        val bundleDir = "${storage.cacheRoot().rootAbsolutePath}/$MEDIA_CACHE_SUBDIR"
        val bundlePath = "$bundleDir/${safeFileName(taskId)}$MEDIA_BUNDLE_EXT"
        return if (storage.exists(bundlePath)) {
            MediaSource.CachedBundle(taskId, bundlePath, manifest)
        } else null
    }

    /** Resolve a [MediaSource] for an integrity-verified USB bundle, or null. */
    fun usbSourceFor(bundle: TvIndexedBundle): MediaSource? {
        val manifest = decodeUsbMetadata(bundle) ?: return null
        if (!bundle.integrityOk) return null
        return MediaSource.UsbBundle(
            taskId = bundle.taskId,
            volumeId = bundle.volumeId,
            bundlePath = bundle.bundleFile.absolutePath,
            manifest = manifest
        )
    }

    /**
     * Open a completed bundle and expose it as a playable local URL for the
     * LibVLC player. The decrypted stream is served over loopback with full
     * Range support so VLC can seek by standard HTTP requests.
     *
     * @return "http://127.0.0.1:<port>/stream.mp4" or null when the bundle
     *         cannot be opened / decryption fails.
     */
    suspend fun playableUrlFor(source: MediaSource): String? {
        val stream = bundleOpener.open(source) ?: return null
        return loopbackServer.start(stream, stream.contentType)
    }

    /** Tear down loopback playback and release the decrypted stream. */
    fun stopPlayback() {
        loopbackServer.stop()
    }

    // ── Storage access (Android 11+ All-Files) ─────────────────────────────

    val hasUsbWriteAccess: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Legacy broad WRITE_EXTERNAL_STORAGE is granted at install on <30.
            true
        }

    /** Settings intent for "All files access" — launch from the Downloads screen. */
    fun allFilesAccessIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
}
