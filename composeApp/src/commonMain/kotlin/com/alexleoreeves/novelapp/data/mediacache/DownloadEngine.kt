package com.alexleoreeves.novelapp.data.mediacache

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Reactive download engine. Consumes typed [DownloadCommand]s off a single
 * channel (UI thread never touches I/O), drives each task through the
 * [MediaTaskRunner] FSM, and exposes a StateFlow snapshot the UI observes.
 *
 * Boot reconciliation: WAL manifests left by a previous run are re-loaded and
 * auto-resumed, so interrupted downloads recover without user action.
 *
 * Pure commonMain + injected ports → shared verbatim by tvApp.
 */
class DownloadEngine(
    private val storage: MediaStoragePort,
    private val crypto: MediaCryptoPort,
    private val transport: MediaTransportPort,
    private val isCellularActive: () -> Boolean,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope
) {
    private val semaphore = Semaphore(MEDIA_MAX_CONCURRENT_CHUNKS)
    private val scheduler = ChunkScheduler(transport, semaphore)
    private val manifests = MediaManifestStore(storage)
    private val cleaner = MediaCacheCleaner(storage, manifests)
    private val subtitleBundler = SubtitleBundler(transport, storage, manifests)

    @Volatile
    private var cellularAllowed = false

    private val gate = MediaAdmissionGate { !isCellularActive() || cellularAllowed }

    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks

    private val commands = Channel<DownloadCommand>(Channel.UNLIMITED)
    private val jobsLock = Mutex()
    private val runningJobs = mutableMapOf<String, Job>()

    fun start() {
        scope.launch { reconcile() }
        scope.launch { for (command in commands) handle(command) }
    }

    fun send(command: DownloadCommand) {
        commands.trySend(command)
    }

    suspend fun setSpeedLimit(bytesPerSecond: Long) = scheduler.setSpeedLimit(bytesPerSecond)

    /** Completed bundles on the internal volume — playable after restart. */
    fun listCompletedBundles(): List<DownloadManifest> = manifests.listCompletedBundles()

    /**
     * Number of internally-completed downloads finalised since the start of the
     * current UTC day. Feeds the free-tier daily quota (5 per day).
     */
    fun completedDownloadsSince(sinceMs: Long): Int =
        manifests.listCompletedBundlesSince(sinceMs).size

    /** Load a completed task's metadata sidecar (bundle path, subtitle path). */
    fun loadCompletedMetadata(taskId: String): DownloadManifest? = manifests.loadMetadata(taskId)

    /** Decode an arbitrary metadata sidecar (e.g. a USB bundle path from the indexer). */
    fun decodeCompletedMetadata(path: String): DownloadManifest? = manifests.decodeMetadataFile(path)

    private suspend fun reconcile() {
        cleaner.purgeStaleInFlight(nowMs())
        manifests.listInFlight().forEach { manifest ->
            val request = requestFor(manifest)
            upsert(taskFor(request, DownloadPhase.QUEUED).copy(manifestPath = manifests.manifestPath(manifest.taskId)))
            scope.launch { runTask(request) }
        }
    }

    private suspend fun handle(command: DownloadCommand) {
        when (command) {
            is DownloadCommand.Enqueue -> {
                val existing = _tasks.value[command.request.taskId]
                if (existing != null && !existing.isTerminal) return
                upsert(taskFor(command.request, DownloadPhase.QUEUED))
                scope.launch { runTask(command.request) }
            }
            is DownloadCommand.Pause -> {
                jobsLock.withLock { runningJobs.remove(command.taskId)?.cancel() }
                _tasks.value[command.taskId]?.let { current ->
                    if (!current.isTerminal) {
                        upsert(current.copy(phase = DownloadPhase.PAUSED, updatedAtMs = nowMs()))
                    }
                }
            }
            is DownloadCommand.Resume -> {
                val current = _tasks.value[command.taskId] ?: return
                if (current.phase != DownloadPhase.PAUSED && !current.isTerminal) return
                upsert(current.copy(phase = DownloadPhase.QUEUED, failureReason = null, errorMessage = null, updatedAtMs = nowMs()))
                scope.launch { runTask(current.request) }
            }
            is DownloadCommand.Cancel -> {
                jobsLock.withLock { runningJobs.remove(command.taskId)?.cancel() }
                manifests.delete(command.taskId)
                _tasks.value[command.taskId]?.let { current ->
                    upsert(
                        current.copy(
                            phase = DownloadPhase.FAILED,
                            failureReason = DownloadFailureReason.CANCELLED,
                            updatedAtMs = nowMs()
                        )
                    )
                }
            }
            is DownloadCommand.Remove -> {
                jobsLock.withLock { runningJobs.remove(command.taskId)?.cancel() }
                // After a reboot a completed task is only discoverable through the
                // metadata sidecar — read it BEFORE wiping, so we know where the
                // bundle lives (internal vs USB) and can delete it too.
                val metadata = manifests.loadMetadata(command.taskId)
                val task = _tasks.value[command.taskId]
                val target = task?.request?.target ?: metadata?.target
                val usbVolumeId = task?.request?.usbVolumeId ?: metadata?.usbVolumeId
                val bundleWasUsb = target == StorageTarget.USB

                // WAL + metadata sidecar always live on the internal volume.
                manifests.deleteTask(command.taskId)
                subtitleBundler.deleteSubtitle(command.taskId)

                if (bundleWasUsb) {
                    // Bundle lives on the external volume. If still mounted, delete
                    // it now; if unmounted, a mounted-volume sweep reclaims orphans.
                    val volume = usbVolumeId?.let { storage.resolveVolume(StorageTarget.USB, it) }
                    if (volume != null) {
                        storage.delete("${volume.rootAbsolutePath}/$MEDIA_CACHE_SUBDIR/${safeFileName(command.taskId)}$MEDIA_BUNDLE_EXT")
                    }
                } else {
                    storage.delete(manifests.bundlePath(command.taskId))
                }
                _tasks.value = _tasks.value - command.taskId
            }
            is DownloadCommand.SetCellularAllowed -> {
                cellularAllowed = command.allowed
            }
        }
    }

    private suspend fun runTask(request: MediaDownloadRequest) {
        val job = currentCoroutineContext()[Job]
        if (job == null) {
            emitFinal(request, DownloadPhase.FAILED, DownloadFailureReason.UNKNOWN, "No job context")
            return
        }
        jobsLock.withLock { runningJobs[request.taskId] = job }

        upsert(
            _tasks.value[request.taskId]?.copy(phase = DownloadPhase.PROBING, updatedAtMs = nowMs())
                ?: taskFor(request, DownloadPhase.PROBING)
        )

        val runner = MediaTaskRunner(
            storage = storage,
            crypto = crypto,
            transport = transport,
            scheduler = scheduler,
            manifests = manifests,
            cleaner = cleaner,
            subtitleBundler = subtitleBundler,
            gate = gate,
            nowMs = nowMs,
            onProgress = { progress ->
                _tasks.value[request.taskId]?.let { current ->
                    upsert(current.copy(phase = DownloadPhase.FETCHING, progress = progress, updatedAtMs = nowMs()))
                }
            }
        )

        try {
            when (val result = runner.run(request)) {
                is TaskRunResult.Success -> {
                    emitFinal(request, DownloadPhase.COMPLETED, null, null)
                }
                is TaskRunResult.Failure -> {
                    emitFinal(request, DownloadPhase.FAILED, result.reason, result.message)
                }
            }
        } catch (e: CancellationException) {
            // Explicit pause or teardown — WAL keeps verified chunks.
            if (_tasks.value[request.taskId]?.phase != DownloadPhase.PAUSED) {
                upsert(
                    _tasks.value[request.taskId]?.copy(phase = DownloadPhase.PAUSED, updatedAtMs = nowMs())
                        ?: taskFor(request, DownloadPhase.PAUSED)
                )
            }
            throw e
        } finally {
            jobsLock.withLock { runningJobs.remove(request.taskId) }
        }
    }

    private fun emitFinal(request: MediaDownloadRequest, phase: DownloadPhase, reason: DownloadFailureReason?, message: String?) {
        val base = _tasks.value[request.taskId] ?: taskFor(request, phase)
        upsert(
            base.copy(
                phase = phase,
                failureReason = reason,
                errorMessage = message,
                updatedAtMs = nowMs()
            )
        )
    }

    private fun upsert(task: DownloadTask) {
        _tasks.value = _tasks.value + (task.request.taskId to task)
    }

    private fun taskFor(request: MediaDownloadRequest, phase: DownloadPhase): DownloadTask =
        DownloadTask(request = request, phase = phase, updatedAtMs = nowMs())

    /** Rebuild a minimal request from a persisted manifest (title is restored
     *  by the UI layer on re-enqueue; resumability is what matters here). */
    private fun requestFor(manifest: DownloadManifest): MediaDownloadRequest =
        MediaDownloadRequest(
            taskId = manifest.taskId,
            sourceUrl = manifest.sourceUrl,
            title = manifest.title.ifBlank { manifest.taskId },
            parentId = manifest.parentId.ifBlank { manifest.taskId },
            episodeNumber = manifest.episodeNumber,
            containerExtension = manifest.containerExtension,
            target = manifest.target,
            usbVolumeId = manifest.usbVolumeId,
            serverId = manifest.serverId,
            serverName = manifest.serverName,
            subtitleUrl = manifest.subtitleUrl,
            mediaType = manifest.mediaType,
            seasonNumber = manifest.seasonNumber,
            coverUrl = manifest.coverUrl,
            maxBytes = manifest.maxBytes,
            maxFraction = manifest.maxFraction,
            headersJson = manifest.headersJson
        )
}
