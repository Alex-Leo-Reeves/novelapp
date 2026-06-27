package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
//  Downloads Root Screen — three type-cards: Anime / Manga / Novels
// ─────────────────────────────────────────────────────────────────────────────
enum class DownloadSection { NONE, ANIME, MANGA, NOVEL }

@Composable
fun DownloadsScreen(
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    onPlayEpisode: (localPath: String, title: String) -> Unit,
    onReadMangaChapter: (localPath: String, title: String) -> Unit,
    onReadNovelChapter: (localPath: String, title: String, sourceName: String) -> Unit
) {
    var activeSection by remember { mutableStateOf(DownloadSection.NONE) }
    var selectedItem by remember { mutableStateOf<DownloadedItem?>(null) }

    AnimatedContent(
        targetState = Pair(activeSection, selectedItem),
        transitionSpec = {
            slideInHorizontally { it } + fadeIn() togetherWith
            slideOutHorizontally { -it } + fadeOut()
        },
        label = "downloads_nav"
    ) { (section, item) ->
        when {
            // Episode / Chapter list for a specific title
            item != null -> {
                when (item.type) {
                    "ANIME" -> DownloadedEpisodesScreen(
                        item = item,
                        downloadRepo = downloadRepo,
                        currentTheme = currentTheme,
                        onPlay = onPlayEpisode,
                        onBack = { selectedItem = null }
                    )
                    "MANGA" -> DownloadedChaptersScreen(
                        item = item,
                        downloadRepo = downloadRepo,
                        currentTheme = currentTheme,
                        onRead = onReadMangaChapter,
                        onBack = { selectedItem = null }
                    )
                    else -> DownloadedNovelChaptersScreen(
                        item = item,
                        downloadRepo = downloadRepo,
                        currentTheme = currentTheme,
                        onRead = onReadNovelChapter,
                        onBack = { selectedItem = null }
                    )
                }
            }
            // Section list screen
            section != DownloadSection.NONE -> DownloadedItemsListScreen(
                section = section,
                downloadRepo = downloadRepo,
                currentTheme = currentTheme,
                onItemClick = { selectedItem = it },
                onBack = { activeSection = DownloadSection.NONE }
            )
            // Root screen with 3 type cards
            else -> DownloadsRootScreen(
                currentTheme = currentTheme,
                downloadRepo = downloadRepo,
                onSectionClick = { activeSection = it }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root: Three category cards
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DownloadsRootScreen(
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    onSectionClick: (DownloadSection) -> Unit
) {
    val animeCount = remember { downloadRepo.getAnimeItems().size }
    val mangaCount = remember { downloadRepo.getMangaItems().size }
    val novelCount = remember { downloadRepo.getNovelItems().size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Download,
                null,
                tint = currentTheme.accentColor(),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.headlineLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Your offline content library",
                    style = MaterialTheme.typography.bodySmall,
                    color = currentTheme.subTextColor()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                DownloadTypeCard(
                    icon = Icons.Default.PlayCircle,
                    title = "Anime",
                    subtitle = "$animeCount series downloaded",
                    accentColor = Color(0xFFFF5722),
                    currentTheme = currentTheme,
                    onClick = { onSectionClick(DownloadSection.ANIME) }
                )
            }
            item {
                DownloadTypeCard(
                    icon = Icons.Default.Collections,
                    title = "Manga",
                    subtitle = "$mangaCount manga downloaded",
                    accentColor = Color(0xFFE91E8C),
                    currentTheme = currentTheme,
                    onClick = { onSectionClick(DownloadSection.MANGA) }
                )
            }
            item {
                DownloadTypeCard(
                    icon = Icons.Default.AutoStories,
                    title = "Novels",
                    subtitle = "$novelCount novels downloaded · AI reader requires internet",
                    accentColor = currentTheme.accentColor(),
                    currentTheme = currentTheme,
                    onClick = { onSectionClick(DownloadSection.NOVEL) }
                )
            }

            item {
                // Storage info notice
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = currentTheme.cardColor().copy(0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = currentTheme.subTextColor(),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Downloads are stored locally on your device. Anime episodes require significant storage space.",
                            style = MaterialTheme.typography.bodySmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadTypeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = currentTheme.textColor()
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = currentTheme.subTextColor(),
                    maxLines = 2
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = currentTheme.subTextColor(),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Downloaded Items Grid (Anime / Manga / Novel)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedItemsListScreen(
    section: DownloadSection,
    downloadRepo: LocalDownloadRepository,
    currentTheme: AppTheme,
    onItemClick: (DownloadedItem) -> Unit,
    onBack: () -> Unit
) {
    val items = remember {
        when (section) {
            DownloadSection.ANIME -> downloadRepo.getAnimeItems()
            DownloadSection.MANGA -> downloadRepo.getMangaItems()
            DownloadSection.NOVEL -> downloadRepo.getNovelItems()
            else -> emptyList()
        }.sortedBy { it.title }
    }
    val sectionLabel = when (section) {
        DownloadSection.ANIME -> "Downloaded Anime"
        DownloadSection.MANGA -> "Downloaded Manga"
        DownloadSection.NOVEL -> "Downloaded Novels"
        else -> ""
    }
    val sectionColor = when (section) {
        DownloadSection.ANIME -> Color(0xFFFF5722)
        DownloadSection.MANGA -> Color(0xFFE91E8C)
        else -> currentTheme.accentColor()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
            }
            Spacer(Modifier.width(4.dp))
            Text(
                sectionLabel,
                style = MaterialTheme.typography.headlineSmall,
                color = currentTheme.textColor(),
                fontWeight = FontWeight.Bold
            )
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.DownloadDone,
                        null,
                        tint = currentTheme.subTextColor(),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "No downloads yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = currentTheme.subTextColor()
                    )
                    Text(
                        "Browse and download content to watch offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.subTextColor().copy(0.6f)
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    Card(
                        onClick = { onItemClick(item) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f)
                            ) {
                                AsyncImage(
                                    model = item.coverUrl,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color.Transparent, Color.Black.copy(0.7f)),
                                                startY = 0.6f
                                            )
                                        )
                                )
                                // Download count badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = sectionColor,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        "${item.totalItems}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                // Offline badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Black.copy(0.6f),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.OfflinePin,
                                            null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Text(
                                            "Offline",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(0.9f)
                                        )
                                    }
                                }
                            }
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = currentTheme.textColor(),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${item.totalItems} ${if (item.type == "ANIME") "episodes" else "chapters"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = currentTheme.subTextColor()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Downloaded Episodes Screen (Anime)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedEpisodesScreen(
    item: DownloadedItem,
    downloadRepo: LocalDownloadRepository,
    currentTheme: AppTheme,
    onPlay: (localPath: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val episodes = remember { downloadRepo.getEpisodesFor(item.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${episodes.size} episodes · Offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = currentTheme.subTextColor()
                )
            }
        }
        Divider(color = currentTheme.subTextColor().copy(0.1f))

        if (episodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No episodes downloaded", color = currentTheme.subTextColor())
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(episodes) { ep ->
                    Card(
                        onClick = { onPlay(ep.localFilePath, "${item.title} – EP ${ep.episodeNumber}") },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFF5722).copy(0.2f)
                            ) {
                                Text(
                                    "EP\n${ep.episodeNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF5722),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ep.episodeTitle.ifEmpty { "Episode ${ep.episodeNumber}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = currentTheme.textColor(),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Offline · ${formatFileSize(ep.fileSizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = currentTheme.subTextColor()
                                )
                            }
                            Icon(
                                Icons.Default.PlayCircle,
                                null,
                                tint = Color(0xFFFF5722),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Downloaded Chapters Screen (Manga)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedChaptersScreen(
    item: DownloadedItem,
    downloadRepo: LocalDownloadRepository,
    currentTheme: AppTheme,
    onRead: (localPath: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val chapters = remember { downloadRepo.getChaptersFor(item.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.textColor(), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${chapters.size} chapters · Offline", style = MaterialTheme.typography.labelMedium,
                    color = currentTheme.subTextColor())
            }
        }
        Divider(color = currentTheme.subTextColor().copy(0.1f))

        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chapters) { ch ->
                Card(
                    onClick = { onRead(ch.localFilePath, "${item.title} – Ch.${ch.chapterNumber}") },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE91E8C).copy(0.2f)) {
                            Text("CH\n${ch.chapterNumber}", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE91E8C), fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ch.chapterTitle.ifEmpty { "Chapter ${ch.chapterNumber}" },
                                style = MaterialTheme.typography.bodyMedium, color = currentTheme.textColor(),
                                fontWeight = FontWeight.SemiBold)
                            Text("${ch.pageCount} pages · Offline", style = MaterialTheme.typography.labelSmall,
                                color = currentTheme.subTextColor())
                        }
                        Icon(Icons.Default.MenuBook, null, tint = Color(0xFFE91E8C), modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Downloaded Novel Chapters Screen
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedNovelChaptersScreen(
    item: DownloadedItem,
    downloadRepo: LocalDownloadRepository,
    currentTheme: AppTheme,
    onRead: (localPath: String, title: String, sourceName: String) -> Unit,
    onBack: () -> Unit
) {
    val chapters = remember { downloadRepo.getChaptersFor(item.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.textColor(), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${chapters.size} chapters downloaded", style = MaterialTheme.typography.labelMedium,
                    color = currentTheme.subTextColor())
            }
        }

        // AI reader notice
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = currentTheme.accentColor().copy(0.1f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Wifi, null, tint = currentTheme.accentColor(), modifier = Modifier.size(18.dp))
                Text("AI voice narration requires an internet connection",
                    style = MaterialTheme.typography.labelMedium, color = currentTheme.accentColor())
            }
        }
        Divider(color = currentTheme.subTextColor().copy(0.1f))

        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chapters) { ch ->
                Card(
                    onClick = { onRead(ch.localFilePath, item.title, item.sourceName) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = RoundedCornerShape(8.dp), color = currentTheme.accentColor().copy(0.2f)) {
                            Text("CH\n${ch.chapterNumber}", style = MaterialTheme.typography.labelSmall,
                                color = currentTheme.accentColor(), fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ch.chapterTitle.ifEmpty { "Chapter ${ch.chapterNumber}" },
                                style = MaterialTheme.typography.bodyMedium, color = currentTheme.textColor(),
                                fontWeight = FontWeight.SemiBold)
                            Text("Saved offline", style = MaterialTheme.typography.labelSmall,
                                color = currentTheme.subTextColor())
                        }
                        Icon(Icons.Default.AutoStories, null, tint = currentTheme.accentColor(),
                            modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes == 0L -> "Unknown size"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    }
}
