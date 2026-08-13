package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.mediacache.DownloadManifest
import com.alexleoreeves.novelapp.data.mediacache.DownloadPhase
import com.alexleoreeves.novelapp.data.mediacache.DownloadTask
import com.alexleoreeves.novelapp.data.mediacache.MediaAccessPolicy
import com.alexleoreeves.novelapp.tv.mediacache.TvIndexedBundle
import com.alexleoreeves.novelapp.tv.mediacache.TvMediaCacheController
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.theme.Purple500

private val Accent = Color(0xFF00BFFF)

/**
 * TV Downloads screen — replaces the old "Coming Soon" placeholder.
 *
 * Shows three stacked sections on one scrollable column:
 *  1. **Active queue** — every in-flight download grouped by media (parentId),
 *     with pause/resume/cancel per task and a live progress bar.
 *  2. **On this TV** — completed internal bundles, playable offline.
 *  3. **USB drive** — bundles the indexer found on a mounted volume; only
 *     integrity-verified ones are playable (corrupt ones offer re-download
 *     affordance via a warning chip instead).
 *
 * A quota banner (5 free downloads per UTC day / Pro unlimited) sits under the
 * header so the user always knows how many media downloads are left today.
 * Local playback routes through [onPlayInternal] / [onPlayUsb], which TvApp
 * wires to the bundle decoder + player.
 */
</｜｜DSML｜｜_command>
@Composable
fun TvDownloadsScreen(
    account: SavedUserAccount? = null,
    mediaCache: TvMediaCacheController? = null,
    onPlayInternal: (taskId: String) -> Unit = {},
    onPlayUsb: (TvIndexedBundle) -> Unit = {},
    onRemoveUsb: (TvIndexedBundle) -> Unit = {}
) {
    if (mediaCache == null) {
        PlaceholderDownloads()
        return
    }

    val tasks by mediaCache.tasks.collectAsState()
    val usbIndex by mediaCache.usbIndex.collectAsState()
    val completedCount = mediaCache.completedDownloadsCount()
    val completed = remember(completedCount) { mediaCache.listCompletedInternal() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Download, null, tint = Accent, modifier = Modifier.size(32.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
        }
        Text(
            "Your offline content library",
            color = Color.White.copy(0.55f),
            style = MaterialTheme.typography.bodyLarge
        )

        // Quota banner
        QuotaBanner(account = account, used = completedCount)

        Spacer(Modifier.height(4.dp))

        if (tasks.isEmpty() && completed.isEmpty() && usbIndex.isEmpty()) {
            EmptyDownloadsState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (tasks.isNotEmpty()) {
                    item { SectionLabel("Active queue", Icons.Default.Downloading) }
                    tasks.values
                        .groupBy { it.request.parentId.ifBlank { it.request.title } }
                        .forEach { (mediaTitle, mediaTasks) ->
                            item { MediaGroupHeader(mediaTitle) }
                            items(mediaTasks, key = { it.request.taskId }) { task ->
                                ActiveTaskRow(
                                    task = task,
                                    onPause = { mediaCache.pause(task.request.taskId) },
                                    onResume = { mediaCache.resume(task.request.taskId) },
                                    onCancel = { mediaCache.cancel(task.request.taskId) }
                                )
                            }
                        }
                }

                if (completed.isNotEmpty()) {
                    item { SectionLabel("On this TV", Icons.Default.OfflinePin) }
                    completed
                        .groupBy { it.parentId.ifBlank { it.title } }
                        .forEach { (mediaTitle, manifests) ->
                            item { MediaGroupHeader(mediaTitle) }
                            items(manifests, key = { it.taskId }) { manifest ->
                                CompletedRow(
                                    manifest = manifest,
                                    onPlay = { onPlayInternal(manifest.taskId) },
                                    onRemove = { mediaCache.remove(manifest.taskId) }
                                )
                            }
                        }
                }

                if (usbIndex.isNotEmpty()) {
                    item { SectionLabel("USB drive", Icons.Default.Usb) }
                    usbIndex
                        .groupBy { it.parentId.ifBlank { it.title } }
                        .forEach { (mediaTitle, bundles) ->
                            item { MediaGroupHeader(mediaTitle) }
                            items(bundles, key = { it.taskId }) { bundle ->
                                UsbBundleRow(
                                    bundle = bundle,
                                    onPlay = { if (bundle.integrityOk) onPlayUsb(bundle) },
                                    onRemove = { onRemoveUsb(bundle) }
                                )
                            }
                        }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ── Section scaffolding ─────────────────────────────────────────────────────

@Composable
private fun SectionLabel(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
    }
}

@Composable
private fun MediaGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
    else -> String.format("%.2f GB", bytes / (1024f * 1024f * 1024f))
}

private fun phaseLabel(phase: DownloadPhase): String = when (phase) {
    DownloadPhase.QUEUED -> "Queued"
    DownloadPhase.PROBING -> "Probing"
    DownloadPhase.PREPARING -> "Preparing"
    DownloadPhase.FETCHING -> "Downloading"
    DownloadPhase.VERIFYING -> "Verifying"
    DownloadPhase.FINALIZING -> "Finalizing"
    DownloadPhase.COMPLETED -> "Complete"
    DownloadPhase.PAUSED -> "Paused"
    DownloadPhase.FAILED -> "Failed"
}

private fun episodeLabel(task: DownloadTask): String {
    val ep = task.request.episodeNumber
    return if (ep > 0) "E${ep} • ${task.request.serverName.ifBlank { task.request.title }}"
    else task.request.serverName.ifBlank { task.request.title }
}

private fun episodeLabel(manifest: DownloadManifest): String {
    return if (manifest.episodeNumber > 0) "E${manifest.episodeNumber}" else manifest.title
}

// ── Quota banner ────────────────────────────────────────────────────────────

@Composable
private fun QuotaBanner(account: SavedUserAccount?, used: Int) {
    val remaining = MediaAccessPolicy.remainingDownloads("anime", account, used)
    val premium = MediaAccessPolicy.isPremiumActive(account)
    val message = MediaAccessPolicy.quotaMessage(remaining, premium)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (remaining > 0 || premium) Accent.copy(alpha = 0.12f) else Color(0xFFFF6B6B).copy(alpha = 0.14f),
        border = BorderStroke(1.dp, (if (remaining > 0 || premium) Accent else Color(0xFFFF6B6B)).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (premium) Icons.Default.Verified else if (remaining > 0) Icons.Default.Info else Icons.Default.Warning,
                null,
                tint = if (remaining > 0 || premium) Accent else Color(0xFFFF6B6B),
                modifier = Modifier.size(20.dp)
            )
            Text(
                message,
                color = Color.White.copy(0.85f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Active queue rows ───────────────────────────────────────────────────────

@Composable
private fun ActiveTaskRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (focused) Color(0xFF14141E) else Color(0xFF0C0C14),
        border = if (focused) BorderStroke(2.dp, Purple500) else BorderStroke(1.dp, Color.White.copy(0.06f)),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    episodeLabel(task),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = phaseColor(task.phase).copy(alpha = 0.18f)
                ) {
                    Text(
                        phaseLabel(task.phase),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = phaseColor(task.phase),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (task.phase == DownloadPhase.PAUSED || task.phase == DownloadPhase.FAILED) {
                    SmallAction("Resume", Accent, onResume)
                } else if (!task.isTerminal) {
                    SmallAction("Pause", Color.White, onPause)
                }
                SmallAction("Cancel", Color(0xFFFF6B6B), onCancel)
            }
            if (!task.isTerminal) {
                LinearProgressIndicator(
                    progress = { task.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Accent,
                    trackColor = Color.White.copy(0.1f)
                )
                Text(
                    "${formatBytes(task.progress.bytesReceived)} / ${formatBytes(task.probe?.totalBytes ?: 0L)} • ${task.progress.chunksCompleted}/${task.progress.chunksTotal} chunks",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(0.45f)
                )
            }
            task.errorMessage?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF6B6B).copy(0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun phaseColor(phase: DownloadPhase): Color = when (phase) {
    DownloadPhase.FAILED -> Color(0xFFFF6B6B)
    DownloadPhase.PAUSED -> Color(0xFFF5A623)
    DownloadPhase.COMPLETED -> Color(0xFF06D6A0)
    else -> Accent
}

@Composable
private fun SmallAction(label: String, tint: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = if (focused) 0.3f else 0.14f),
        border = if (focused) BorderStroke(2.dp, tint) else null,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ── Completed / USB rows ────────────────────────────────────────────────────

@Composable
private fun CompletedRow(
    manifest: DownloadManifest,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (focused) Color(0xFF14141E) else Color(0xFF0C0C14),
        border = if (focused) BorderStroke(2.dp, Purple500) else BorderStroke(1.dp, Color.White.copy(0.06f)),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.OfflinePin, null, tint = Color(0xFF06D6A0), modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    episodeLabel(manifest),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatBytes(manifest.totalBytes)} • offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(0.45f)
                )
            }
            SmallAction("Play", Color(0xFF06D6A0), onPlay)
            SmallAction("Remove", Color(0xFFFF6B6B), onRemove)
        }
    }
}

@Composable
private fun UsbBundleRow(
    bundle: TvIndexedBundle,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (focused) Color(0xFF14141E) else Color(0xFF0C0C14),
        border = if (focused) BorderStroke(2.dp, Purple500) else BorderStroke(1.dp, Color.White.copy(0.06f)),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (bundle.integrityOk) Icons.Default.Usb else Icons.Default.Warning,
                null,
                tint = if (bundle.integrityOk) Color(0xFF06D6A0) else Color(0xFFFF6B6B),
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    episodeLabel(
                        DownloadManifest(
                            taskId = bundle.taskId,
                            sourceUrl = bundle.sourceUrl,
                            totalBytes = bundle.totalBytes,
                            containerExtension = bundle.containerExtension,
                            target = com.alexleoreeves.novelapp.data.mediacache.StorageTarget.USB,
                            bundleFileName = bundle.bundleFile.name,
                            title = bundle.title,
                            parentId = bundle.parentId,
                            episodeNumber = bundle.episodeNumber
                        )
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (bundle.integrityOk) "${formatBytes(bundle.bundleBytesOnDisk)} • verified"
                    else "${formatBytes(bundle.bundleBytesOnDisk)} • corrupt — re-download",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bundle.integrityOk) Color.White.copy(0.45f) else Color(0xFFFF6B6B).copy(0.9f)
                )
            }
            if (bundle.integrityOk) SmallAction("Play", Color(0xFF06D6A0), onPlay)
            SmallAction("Remove", Color(0xFFFF6B6B), onRemove)
        }
    }
}

// ── Empty / placeholder states ──────────────────────────────────────────────

@Composable
private fun EmptyDownloadsState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.CloudDownload, null, tint = Color.White.copy(0.15f), modifier = Modifier.size(88.dp))
            Text("Nothing downloaded yet", color = Color.White.copy(0.55f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Open any title, pick a server and download episodes\nfor offline viewing on this TV.",
                color = Color.White.copy(0.35f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 460.dp)
            )
        }
    }
}

@Composable
private fun PlaceholderDownloads() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Download, null, tint = Accent, modifier = Modifier.size(32.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text("Your offline content library", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.CloudDownload, null, tint = Color.White.copy(0.15f), modifier = Modifier.size(96.dp))
                Text("Downloads Unavailable", color = Color.White.copy(0.5f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "The media cache is not initialised on this build.",
                    color = Color.White.copy(0.3f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
