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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.mediacache.DownloadManifest
import com.alexleoreeves.novelapp.data.mediacache.DownloadPhase
import com.alexleoreeves.novelapp.data.mediacache.DownloadTask
import com.alexleoreeves.novelapp.data.mediacache.MediaAccessPolicy
import com.alexleoreeves.novelapp.tv.mediacache.TvIndexedBundle
import com.alexleoreeves.novelapp.tv.mediacache.TvMediaCacheController
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.theme.Purple500

private val Accent = Color(0xFF00BFFF)
private val Green = Color(0xFF06D6A0)
private val Red = Color(0xFFFF6B6B)

// ── Breadcrumb navigation model ─────────────────────────────────────────────

/**
 * Represents the current drill-down position in the hierarchical downloads
 * tree. Each level narrows the view until the user sees individual episodes.
 */
private sealed interface DownloadBreadcrumb {
    data object Root : DownloadBreadcrumb
    data class MediaType(val type: String) : DownloadBreadcrumb
    data class Title(val type: String, val parentId: String) : DownloadBreadcrumb
    data class Season(val type: String, val parentId: String, val season: Int) : DownloadBreadcrumb
}

// ── Flattened items for LazyColumn ──────────────────────────────────────────

private sealed interface DownloadItem {
    data class CategoryCard(val mediaType: String, val titleCount: Int, val episodeCount: Int) : DownloadItem
    data class TitleCard(val parentId: String, val title: String, val episodeCount: Int, val coverUrl: String) : DownloadItem
    data class SeasonCard(val season: Int, val episodeCount: Int) : DownloadItem
    data class EpisodeTask(val task: DownloadTask) : DownloadItem
    data class EpisodeManifest(val manifest: DownloadManifest, val isInternal: Boolean = true) : DownloadItem
    data class EpisodeUsb(val bundle: TvIndexedBundle) : DownloadItem
    data class SectionHeader(val label: String) : DownloadItem
}

/**
 * TV Downloads screen — hierarchical breadcrumb layout.
 *
 * Drill-down flow:
 *   Root → Media Type (Anime, Movies, Donghua, Manga, Novels, Comics)
 *        → Title (e.g. "Re:Zero")
 *        → Season (e.g. "Season 1") — only shown when seasonNumber > 0
 *        → Episodes (individual downloads with play/delete)
 *
 * Active downloads are always shown at root level as a separate section.
 */
@Composable
fun TvDownloadsScreen(
    account: SavedUserAccount? = null,
    mediaCache: TvMediaCacheController? = null,
    onPlayInternal: (taskId: String) -> Unit = {},
    onPlayUsb: (TvIndexedBundle) -> Unit = {},
    onRemoveUsb: (TvIndexedBundle) -> Unit = {},
    onGoPremium: () -> Unit = {}
) {
    if (mediaCache == null) {
        PlaceholderDownloads()
        return
    }

    val tasks by mediaCache.tasks.collectAsState()
    val usbIndex by mediaCache.usbIndex.collectAsState()
    val completedCount = mediaCache.completedDownloadsCount()
    val completed = remember(completedCount) { mediaCache.listCompletedInternal() }

    var breadcrumb by remember { mutableStateOf<DownloadBreadcrumb>(DownloadBreadcrumb.Root) }

    // Build the unified list of items for the current breadcrumb level
    val items = remember(tasks, completed, usbIndex, breadcrumb) {
        buildDownloadItems(
            tasks = tasks,
            completed = completed,
            usbBundles = usbIndex,
            breadcrumb = breadcrumb
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + breadcrumb trail
        DownloadHeader(breadcrumb = breadcrumb, onNavigate = { breadcrumb = it })

        // Quota banner
        QuotaBanner(account = account, used = completedCount, onGoPremium = onGoPremium)

        Spacer(Modifier.height(2.dp))

        if (tasks.isEmpty() && completed.isEmpty() && usbIndex.isEmpty()) {
            EmptyDownloadsState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Active downloads always visible at every level
                val activeTasks = tasks.values.filter { !it.isTerminal }
                if (activeTasks.isNotEmpty() && breadcrumb is DownloadBreadcrumb.Root) {
                    item { SectionHeader("Active downloads") }
                    items(activeTasks, key = { it.request.taskId }) { task ->
                        ActiveTaskRow(
                            task = task,
                            onPause = { mediaCache.pause(task.request.taskId) },
                            onResume = { mediaCache.resume(task.request.taskId) },
                            onCancel = {
                                mediaCache.cancel(task.request.taskId)
                            },
                            onDelete = { mediaCache.remove(task.request.taskId) }
                        )
                    }
                }

                // Failed downloads at root
                val failedTasks = tasks.values.filter { it.phase == DownloadPhase.FAILED }
                if (failedTasks.isNotEmpty() && breadcrumb is DownloadBreadcrumb.Root) {
                    item { SectionHeader("Failed") }
                    items(failedTasks, key = { it.request.taskId }) { task ->
                        ActiveTaskRow(
                            task = task,
                            onPause = {},
                            onResume = { mediaCache.resume(task.request.taskId) },
                            onCancel = { mediaCache.remove(task.request.taskId) },
                            onDelete = { mediaCache.remove(task.request.taskId) }
                        )
                    }
                }

                // Hierarchical items for the current breadcrumb level
                items.forEach { item ->
                    when (item) {
                        is DownloadItem.SectionHeader -> item(key = "header_${item.label}") {
                            SectionHeader(item.label)
                        }
                        is DownloadItem.CategoryCard -> item(key = "cat_${item.mediaType}") {
                            CategoryCardRow(
                                item = item,
                                onClick = { breadcrumb = DownloadBreadcrumb.MediaType(item.mediaType) }
                            )
                        }
                        is DownloadItem.TitleCard -> item(key = "title_${item.parentId}") {
                            TitleCardRow(
                                item = item,
                                onClick = { breadcrumb = DownloadBreadcrumb.Title(
                                    (breadcrumb as? DownloadBreadcrumb.MediaType)?.type ?: "",
                                    item.parentId
                                ) }
                            )
                        }
                        is DownloadItem.SeasonCard -> item(key = "season_${(breadcrumb as? DownloadBreadcrumb.Title)?.parentId}_${item.season}") {
                            SeasonCardRow(
                                item = item,
                                onClick = {
                                    val t = (breadcrumb as? DownloadBreadcrumb.Title)
                                    if (t != null) {
                                        breadcrumb = DownloadBreadcrumb.Season(t.type, t.parentId, item.season)
                                    }
                                }
                            )
                        }
                        is DownloadItem.EpisodeManifest -> item(key = "ep_${item.manifest.taskId}") {
                            CompletedRow(
                                manifest = item.manifest,
                                onPlay = { onPlayInternal(item.manifest.taskId) },
                                onRemove = { mediaCache.remove(item.manifest.taskId) }
                            )
                        }
                        is DownloadItem.EpisodeUsb -> item(key = "usb_${item.bundle.taskId}") {
                            UsbBundleRow(
                                bundle = item.bundle,
                                onPlay = { if (item.bundle.integrityOk) onPlayUsb(item.bundle) },
                                onRemove = { onRemoveUsb(item.bundle) }
                            )
                        }
                        is DownloadItem.EpisodeTask -> item(key = "task_${item.task.request.taskId}") {
                            ActiveTaskRow(
                                task = item.task,
                                onPause = { mediaCache.pause(item.task.request.taskId) },
                                onResume = { mediaCache.resume(item.task.request.taskId) },
                                onCancel = { mediaCache.cancel(item.task.request.taskId) },
                                onDelete = { mediaCache.remove(item.task.request.taskId) }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ── Item builder ────────────────────────────────────────────────────────────

private fun buildDownloadItems(
    tasks: Map<String, DownloadTask>,
    completed: List<DownloadManifest>,
    usbBundles: List<TvIndexedBundle>,
    breadcrumb: DownloadBreadcrumb
): List<DownloadItem> {
    val items = mutableListOf<DownloadItem>()

    // Unified manifest pool (internal completed + USB bundles → treat USB as completed too)
    val allManifests = completed.toMutableList()

    // Also add USB bundles as virtual manifests for grouping
    val usbAsManifests = usbBundles.filter { it.integrityOk }.map { bundle ->
        DownloadManifest(
            taskId = bundle.taskId,
            sourceUrl = bundle.sourceUrl,
            totalBytes = bundle.totalBytes,
            containerExtension = bundle.containerExtension,
            target = com.alexleoreeves.novelapp.data.mediacache.StorageTarget.USB,
            bundleFileName = bundle.bundleFile.name,
            title = bundle.title,
            parentId = bundle.parentId,
            episodeNumber = bundle.episodeNumber,
            mediaType = bundle.mediaType,
            seasonNumber = bundle.seasonNumber,
            coverUrl = bundle.coverUrl
        )
    }

    // Merge active non-terminal tasks into the mix for deeper levels
    val activeTasks = tasks.values.filter { !it.isTerminal }

    when (breadcrumb) {
        is DownloadBreadcrumb.Root -> {
            // Show media type categories from completed + active tasks
            val allMediaTypes = mutableSetOf<String>()
            val typeStats = mutableMapOf<String, MutablePair<Int, Int>>() // titleCount, episodeCount

            // From completed manifests
            (allManifests + usbAsManifests).forEach { m ->
                val mt = m.mediaType.ifBlank { deriveTypeFromTitle(m.title) }
                allMediaTypes.add(mt)
                val stats = typeStats.getOrPut(mt) { MutablePair(0, 0) }
            }
            // Count unique titles per type
            (allManifests + usbAsManifests).groupBy { it.mediaType.ifBlank { deriveTypeFromTitle(it.title) } }
                .forEach { (mt, manifests) ->
                    val stats = typeStats.getOrPut(mt) { MutablePair(0, 0) }
                    stats.first = manifests.map { it.parentId }.distinct().size
                    stats.second = manifests.size
                }

            // From active tasks
            activeTasks.forEach { task ->
                val mt = task.request.mediaType.ifBlank { deriveTypeFromTitle(task.request.title) }
                allMediaTypes.add(mt)
            }

            if (allMediaTypes.isNotEmpty()) {
                val order = listOf("ANIME", "DONGHUA", "MOVIE", "MOVIES", "K_DRAMA", "CARTOON", "CLASSIC", "NIGERIAN", "MANGA", "COMIC", "NOVEL")
                val sorted = allMediaTypes.sortedBy { type ->
                    val idx = order.indexOf(type.uppercase())
                    if (idx < 0) order.size else idx
                }
                sorted.forEach { mt ->
                    val stats = typeStats[mt] ?: MutablePair(0, 0)
                    items.add(DownloadItem.CategoryCard(
                        mediaType = mt,
                        titleCount = stats.first,
                        episodeCount = stats.second
                    ))
                }
            }
        }

        is DownloadBreadcrumb.MediaType -> {
            val type = breadcrumb.type
            // Filter to this media type
            val typeManifests = (allManifests + usbAsManifests).filter {
                it.mediaType.equals(type, ignoreCase = true) ||
                    (it.mediaType.isBlank() && deriveTypeFromTitle(it.title).equals(type, ignoreCase = true))
            }
            val typeActive = activeTasks.filter {
                it.request.mediaType.equals(type, ignoreCase = true) ||
                    (it.request.mediaType.isBlank() && deriveTypeFromTitle(it.request.title).equals(type, ignoreCase = true))
            }

            // Group by parentId (title)
            val titleGroups = (typeManifests.groupBy { it.parentId } +
                typeActive.groupBy { it.request.parentId }).toSortedMap()

            titleGroups.forEach { (parentId, group) ->
                val manifestList = typeManifests.filter { it.parentId == parentId }
                val title = manifestList.firstOrNull()?.title ?: typeActive.firstOrNull { it.request.parentId == parentId }?.request?.title ?: parentId
                val coverUrl = manifestList.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: ""
                val count = manifestList.size + typeActive.count { it.request.parentId == parentId }
                items.add(DownloadItem.TitleCard(
                    parentId = parentId,
                    title = title,
                    episodeCount = count,
                    coverUrl = coverUrl
                ))
            }
        }

        is DownloadBreadcrumb.Title -> {
            val parentId = breadcrumb.parentId
            val type = breadcrumb.type
            val titleManifests = (allManifests + usbAsManifests).filter { it.parentId == parentId }
            val titleActive = activeTasks.filter { it.request.parentId == parentId }
            val allTitle = titleManifests + usbAsManifests.filter { it.parentId == parentId }

            // Check if there are seasons
            val seasons = (titleManifests.map { it.seasonNumber } + titleActive.map { it.request.seasonNumber })
                .filter { it > 0 }.distinct().sorted()

            if (seasons.isNotEmpty()) {
                // Show season cards
                items.add(DownloadItem.SectionHeader("Seasons"))
                seasons.forEach { s ->
                    val epCount = titleManifests.count { it.seasonNumber == s } +
                        titleActive.count { it.request.seasonNumber == s }
                    items.add(DownloadItem.SeasonCard(season = s, episodeCount = epCount))
                }
                // Show season 0 (unsorted) separately
                val season0 = titleManifests.filter { it.seasonNumber <= 0 } + titleActive.filter { it.request.seasonNumber <= 0 }
                if (season0.isNotEmpty()) {
                    items.add(DownloadItem.SeasonCard(season = 0, episodeCount = season0.size))
                }
            } else {
                // No seasons — show episodes directly
                items.add(DownloadItem.SectionHeader("Episodes"))
                // Completed
                titleManifests.filter { it.seasonNumber <= 0 }.sortedBy { it.episodeNumber }.forEach { m ->
                    items.add(DownloadItem.EpisodeManifest(m))
                }
                // USB
                usbBundles.filter { it.parentId == parentId && it.integrityOk && it.seasonNumber <= 0 }
                    .sortedBy { it.episodeNumber }.forEach { b ->
                        items.add(DownloadItem.EpisodeUsb(b))
                    }
                // Active
                titleActive.filter { it.request.seasonNumber <= 0 }.forEach { task ->
                    items.add(DownloadItem.EpisodeTask(task))
                }
            }
        }

        is DownloadBreadcrumb.Season -> {
            val parentId = breadcrumb.parentId
            val season = breadcrumb.season
            val seasonManifests = (allManifests).filter { it.parentId == parentId && it.seasonNumber == season }
            val seasonUsb = usbBundles.filter { it.parentId == parentId && it.seasonNumber == season && it.integrityOk }
            val seasonActive = activeTasks.filter { it.request.parentId == parentId && it.request.seasonNumber == season }

            // Season 0 = unsorted / no season
            val effectiveManifests = if (season == 0) {
                (allManifests).filter { it.parentId == parentId && it.seasonNumber <= 0 }
            } else seasonManifests

            val effectiveUsb = if (season == 0) {
                usbBundles.filter { it.parentId == parentId && it.seasonNumber <= 0 && it.integrityOk }
            } else seasonUsb

            val effectiveActive = if (season == 0) {
                activeTasks.filter { it.request.parentId == parentId && it.request.seasonNumber <= 0 }
            } else seasonActive

            items.add(DownloadItem.SectionHeader(if (season > 0) "Season $season" else "Episodes"))

            effectiveManifests.sortedBy { it.episodeNumber }.forEach { m ->
                items.add(DownloadItem.EpisodeManifest(m))
            }
            effectiveUsb.sortedBy { it.episodeNumber }.forEach { b ->
                items.add(DownloadItem.EpisodeUsb(b))
            }
            effectiveActive.forEach { task ->
                items.add(DownloadItem.EpisodeTask(task))
            }
        }
    }

    return items
}

private fun deriveTypeFromTitle(title: String): String {
    val lower = title.lowercase()
    return when {
        lower.contains("donghua") -> "DONGHUA"
        lower.contains("anime") -> "ANIME"
        lower.contains("manga") -> "MANGA"
        lower.contains("novel") -> "NOVEL"
        lower.contains("comic") -> "COMIC"
        else -> "MOVIE"
    }
}

private data class MutablePair<A, B>(var first: A, var second: B)

// ── Header with breadcrumb ──────────────────────────────────────────────────

@Composable
private fun DownloadHeader(
    breadcrumb: DownloadBreadcrumb,
    onNavigate: (DownloadBreadcrumb) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (breadcrumb !is DownloadBreadcrumb.Root) {
                var backFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        onNavigate(
                            when (breadcrumb) {
                                is DownloadBreadcrumb.MediaType -> DownloadBreadcrumb.Root
                                is DownloadBreadcrumb.Title -> DownloadBreadcrumb.MediaType(breadcrumb.type)
                                is DownloadBreadcrumb.Season -> DownloadBreadcrumb.Title(breadcrumb.type, breadcrumb.parentId)
                                else -> DownloadBreadcrumb.Root
                            }
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                    border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.onFocusChanged { backFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.padding(8.dp).size(20.dp))
                }
            }
            Icon(Icons.Default.Download, null, tint = Accent, modifier = Modifier.size(32.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
        }
        // Breadcrumb trail
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var rootFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onNavigate(DownloadBreadcrumb.Root) },
                shape = RoundedCornerShape(6.dp),
                color = if (breadcrumb is DownloadBreadcrumb.Root) Accent.copy(0.2f) else Color.Transparent,
                modifier = Modifier.onFocusChanged { rootFocused = it.isFocused }
            ) {
                Text(
                    "All",
                    color = if (breadcrumb is DownloadBreadcrumb.Root) Accent else Color.White.copy(0.5f),
                    fontWeight = if (breadcrumb is DownloadBreadcrumb.Root) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            when (breadcrumb) {
                is DownloadBreadcrumb.MediaType -> {
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
                    Text(breadcrumb.type.replaceFirstChar { it.uppercase() }, color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                is DownloadBreadcrumb.Title -> {
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
                    Text(breadcrumb.type.replaceFirstChar { it.uppercase() }, color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
                    Text(breadcrumb.parentId.take(20), color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                is DownloadBreadcrumb.Season -> {
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
                    Text(breadcrumb.type.replaceFirstChar { it.uppercase() }, color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
                    Text(breadcrumb.parentId.take(20), color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
                    Text(if (breadcrumb.season > 0) "Season ${breadcrumb.season}" else "Episodes", color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                else -> {}
            }
        }
    }
}

// ── Category cards ──────────────────────────────────────────────────────────

private fun typeIcon(type: String) = when (type.uppercase()) {
    "ANIME" -> Icons.Default.Tv
    "DONGHUA" -> Icons.Default.Tv
    "MOVIE", "MOVIES" -> Icons.Default.Movie
    "K_DRAMA" -> Icons.Default.LiveTv
    "CARTOON" -> Icons.Default.Animation
    "CLASSIC" -> Icons.Default.Theaters
    "NIGERIAN" -> Icons.Default.Star
    "MANGA" -> Icons.Default.AutoStories
    "COMIC" -> Icons.Default.MenuBook
    "NOVEL" -> Icons.Default.Book
    else -> Icons.Default.Folder
}

private fun typeColor(type: String) = when (type.uppercase()) {
    "ANIME" -> Color(0xFFE91E63)
    "DONGHUA" -> Color(0xFFFF9800)
    "MOVIE", "MOVIES" -> Color(0xFF2196F3)
    "K_DRAMA" -> Color(0xFF9C27B0)
    "CARTOON" -> Color(0xFFFFEB3B)
    "CLASSIC" -> Color(0xFF795548)
    "NIGERIAN" -> Color(0xFF4CAF50)
    "MANGA" -> Color(0xFFFF5722)
    "COMIC" -> Color(0xFF00BCD4)
    "NOVEL" -> Color(0xFF607D8B)
    else -> Accent
}

@Composable
private fun CategoryCardRow(item: DownloadItem.CategoryCard, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = typeColor(item.mediaType)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (focused) color.copy(0.35f) else color.copy(0.08f),
        border = if (focused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, color.copy(0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(typeIcon(item.mediaType), null, tint = if (focused) Color.White else color, modifier = Modifier.size(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.mediaType.replaceFirstChar { it.uppercase() }.replace("_", " "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    "${item.titleCount} title${if (item.titleCount != 1) "s" else ""} • ${item.episodeCount} download${if (item.episodeCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) Color.White.copy(0.85f) else Color.White.copy(0.5f)
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = if (focused) Color.White else Color.White.copy(0.4f), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun TitleCardRow(item: DownloadItem.TitleCard, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (focused) Purple500.copy(0.35f) else Color(0xFF0C0C14),
        border = if (focused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item.coverUrl.isNotBlank()) {
                coil3.compose.AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(48.dp, 68.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp, 68.dp)
                        .background(Color.White.copy(0.05f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Folder, null, tint = Color.White.copy(0.2f))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${item.episodeCount} download${if (item.episodeCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Color.White.copy(0.85f) else Color.White.copy(0.45f)
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = if (focused) Color.White else Color.White.copy(0.4f), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SeasonCardRow(item: DownloadItem.SeasonCard, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (focused) Accent.copy(0.35f) else Color(0xFF0C0C14),
        border = if (focused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.FolderOpen, null, tint = if (focused) Color.White else Accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (item.season > 0) "Season ${item.season}" else "Unsorted",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "${item.episodeCount} episode${if (item.episodeCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Color.White.copy(0.85f) else Color.White.copy(0.45f)
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = if (focused) Color.White else Color.White.copy(0.4f), modifier = Modifier.size(20.dp))
        }
    }
}

// ── Section scaffolding ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
    }
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
    return if (manifest.episodeNumber > 0) "E${manifest.episodeNumber} — ${manifest.title.ifBlank { "" }}"
    else manifest.title
}

// ── Quota banner ────────────────────────────────────────────────────────────

@Composable
private fun QuotaBanner(account: SavedUserAccount?, used: Int, onGoPremium: () -> Unit) {
    val remaining = MediaAccessPolicy.remainingDownloads("anime", account, used)
    val premium = MediaAccessPolicy.isPremiumActive(account)
    val message = MediaAccessPolicy.quotaMessage(remaining, premium)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (remaining > 0 || premium) Accent.copy(alpha = 0.12f) else Red.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, (if (remaining > 0 || premium) Accent else Red).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (premium) Icons.Default.Verified else if (remaining > 0) Icons.Default.Info else Icons.Default.Warning,
                    null,
                    tint = if (remaining > 0 || premium) Accent else Red,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    message,
                    color = Color.White.copy(0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
            // Go Premium button — visible for non-premium users
            if (!premium) {
                var premiumFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onGoPremium,
                    shape = RoundedCornerShape(10.dp),
                    color = if (premiumFocused) Purple500 else Purple500.copy(0.18f),
                    border = if (premiumFocused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Purple500.copy(0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .onFocusChanged { premiumFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Diamond, null, tint = if (premiumFocused) Color.White else Purple500, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Go Premium — Unlimited Downloads & Watching",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

// ── Active queue rows ───────────────────────────────────────────────────────

@Composable
private fun ActiveTaskRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (focused) Color(0xFF161628) else Color(0xFF0C0C14),
        border = if (focused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
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
                // Cancel for active, delete/bin for terminal
                if (task.isTerminal) {
                    SmallAction("Delete", Red, onDelete)
                } else {
                    SmallAction("Cancel", Red, onCancel)
                }
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
                    color = Red.copy(0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun phaseColor(phase: DownloadPhase): Color = when (phase) {
    DownloadPhase.FAILED -> Red
    DownloadPhase.PAUSED -> Color(0xFFF5A623)
    DownloadPhase.COMPLETED -> Green
    else -> Accent
}

@Composable
private fun SmallAction(label: String, tint: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = if (focused) 0.5f else 0.16f),
        border = if (focused) BorderStroke(2.5.dp, Color.White) else BorderStroke(1.dp, tint.copy(0.4f)),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (focused) Color.White else tint,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
    var rowFocused by remember { mutableStateOf(false) }
    var deleteFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(12.dp),
        color = if (rowFocused) Color(0xFF1E2838) else Color(0xFF0C0C14),
        border = if (rowFocused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { rowFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.OfflinePin, null, tint = Green, modifier = Modifier.size(26.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    episodeLabel(manifest),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatBytes(manifest.totalBytes)} • offline • Press OK to Play",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rowFocused) Green else Color.White.copy(0.5f)
                )
            }
            // Direct Play indicator
            Surface(
                onClick = onPlay,
                shape = RoundedCornerShape(8.dp),
                color = Green.copy(alpha = if (rowFocused) 0.35f else 0.18f),
                border = BorderStroke(1.5.dp, Green)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Green, modifier = Modifier.size(20.dp))
                    Text("Play", color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
            // Delete button
            Surface(
                onClick = onRemove,
                shape = RoundedCornerShape(8.dp),
                color = if (deleteFocused) Red.copy(0.45f) else Red.copy(0.14f),
                border = if (deleteFocused) BorderStroke(2.5.dp, Color.White) else BorderStroke(1.dp, Red.copy(0.3f)),
                modifier = Modifier.onFocusChanged { deleteFocused = it.isFocused }
            ) {
                Icon(
                    Icons.Default.Delete,
                    "Delete",
                    tint = if (deleteFocused) Color.White else Red,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun UsbBundleRow(
    bundle: TvIndexedBundle,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    var rowFocused by remember { mutableStateOf(false) }
    var deleteFocused by remember { mutableStateOf(false) }
    val playAction = { if (bundle.integrityOk) onPlay() }

    Surface(
        onClick = playAction,
        shape = RoundedCornerShape(12.dp),
        color = if (rowFocused) Color(0xFF1E2838) else Color(0xFF0C0C14),
        border = if (rowFocused) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { rowFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (bundle.integrityOk) Icons.Default.Usb else Icons.Default.Warning,
                null,
                tint = if (bundle.integrityOk) Green else Red,
                modifier = Modifier.size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (bundle.episodeNumber > 0) "E${bundle.episodeNumber} — ${bundle.title}" else bundle.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (bundle.integrityOk) "${formatBytes(bundle.bundleBytesOnDisk)} • verified • USB • Press OK to Play"
                    else "${formatBytes(bundle.bundleBytesOnDisk)} • corrupt — re-download",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bundle.integrityOk) (if (rowFocused) Green else Color.White.copy(0.5f)) else Red.copy(0.9f)
                )
            }
            if (bundle.integrityOk) {
                Surface(
                    onClick = playAction,
                    shape = RoundedCornerShape(8.dp),
                    color = Green.copy(alpha = if (rowFocused) 0.35f else 0.18f),
                    border = BorderStroke(1.5.dp, Green)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Green, modifier = Modifier.size(20.dp))
                        Text("Play", color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Surface(
                onClick = onRemove,
                shape = RoundedCornerShape(8.dp),
                color = if (deleteFocused) Red.copy(0.45f) else Red.copy(0.14f),
                border = if (deleteFocused) BorderStroke(2.5.dp, Color.White) else BorderStroke(1.dp, Red.copy(0.3f)),
                modifier = Modifier.onFocusChanged { deleteFocused = it.isFocused }
            ) {
                Icon(
                    Icons.Default.Delete,
                    "Delete",
                    tint = if (deleteFocused) Color.White else Red,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
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
