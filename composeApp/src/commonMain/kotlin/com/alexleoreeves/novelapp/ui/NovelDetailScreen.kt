package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novel: UnifiedSearchResult,
    currentTheme: AppTheme,
    isFavorite: Boolean,
    downloadRepo: LocalDownloadRepository,
    onToggleFavorite: (UnifiedSearchResult) -> Unit,
    onChapterSelected: (Chapter) -> Unit,
    onBack: () -> Unit,
    requireAuth: (() -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var isLoadingChapters by remember { mutableStateOf(true) }
    var downloadingChapters by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var refreshTrigger by remember { mutableStateOf(0) } // force recomposition when downloads change
    var chapterQuery by remember { mutableStateOf("") }
    var newestFirst by remember { mutableStateOf(false) }
    var selectedChapterChunk by remember { mutableStateOf(0) }

    val repository = remember {
        NovelSearchRepository(
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }

    LaunchedEffect(novel.detailPageUrl) {
        isLoadingChapters = true
        chapters = if (novel.isManga) {
            repository.fetchMangaChapters(novel.detailPageUrl, novel.sourceName).map {
                Chapter(
                    title = it.title,
                    url = it.url,
                    chapterNumber = it.chapterNumber
                )
            }
        } else {
            repository.fetchChapters(novel.detailPageUrl, novel.sourceName)
        }
        isLoadingChapters = false
    }

    val orderedChapters = remember(chapters, newestFirst) {
        if (newestFirst) chapters.asReversed() else chapters
    }
    val filteredChapters = remember(orderedChapters, chapterQuery) {
        val query = chapterQuery.trim().lowercase()
        if (query.isBlank()) {
            orderedChapters
        } else {
            orderedChapters.filter { chapter ->
                chapter.title.lowercase().contains(query) ||
                    chapter.chapterNumber.toString().contains(query)
            }
        }
    }
    val chapterChunks = remember(filteredChapters, chapterQuery) {
        if (chapterQuery.isBlank()) filteredChapters.chunked(100) else listOf(filteredChapters)
    }
    val activeChunkIndex = selectedChapterChunk.coerceIn(0, (chapterChunks.size - 1).coerceAtLeast(0))
    val visibleChapters = chapterChunks.getOrElse(activeChunkIndex) { emptyList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Hero Image + back button
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    AsyncImage(
                        model = novel.coverUrl,
                        contentDescription = novel.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Dark gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(0.3f), Color.Black.copy(0.9f))
                                )
                            )
                    )
                    // Back button
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(top = 40.dp, start = 8.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack, "Back",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Favorite button
                    IconButton(
                        onClick = { requireAuth { onToggleFavorite(novel) } },
                        modifier = Modifier
                            .padding(top = 40.dp, end = 8.dp)
                            .align(Alignment.TopEnd)
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favorite",
                            tint = if (isFavorite) Color(0xFFE91E8C) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Title overlay at bottom
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            novel.title,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        if (novel.author.isNotEmpty()) {
                            Text(
                                "by ${novel.author}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(0.7f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (novel.genre.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = currentTheme.accentColor()
                                ) {
                                    Text(
                                        novel.genre,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = Color.White.copy(0.2f)
                            ) {
                                Text(
                                    novel.sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Synopsis
            if (novel.synopsis.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "Synopsis",
                            style = MaterialTheme.typography.headlineMedium,
                            color = currentTheme.textColor(),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            novel.synopsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = currentTheme.subTextColor()
                        )
                    }
                    Divider(color = currentTheme.accentColor().copy(0.2f))
                }
            }

            // Chapter list header
            item {
                Padding(horizontal = 16.dp, vertical = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Chapters",
                                style = MaterialTheme.typography.headlineMedium,
                                color = currentTheme.textColor(),
                                fontWeight = FontWeight.Bold
                            )
                            if (!isLoadingChapters) {
                                Text(
                                    "${chapters.size} chapters",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = currentTheme.subTextColor()
                                )
                            }
                        }
                        if (!isLoadingChapters && chapters.isNotEmpty()) {
                            OutlinedTextField(
                                value = chapterQuery,
                                onValueChange = {
                                    chapterQuery = it
                                    selectedChapterChunk = 0
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (chapterQuery.isNotBlank()) {
                                        IconButton(onClick = { chapterQuery = ""; selectedChapterChunk = 0 }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear")
                                        }
                                    }
                                },
                                label = { Text("Search or jump by chapter number") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = currentTheme.accentColor(),
                                    focusedTextColor = currentTheme.textColor(),
                                    unfocusedTextColor = currentTheme.textColor(),
                                    cursorColor = currentTheme.accentColor()
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = {
                                        newestFirst = !newestFirst
                                        selectedChapterChunk = 0
                                    },
                                    label = { Text(if (newestFirst) "Newest first" else "Oldest first") },
                                    leadingIcon = {
                                        Icon(
                                            if (newestFirst) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                                AssistChip(
                                    onClick = {
                                        selectedChapterChunk = if (newestFirst) (chapterChunks.size - 1).coerceAtLeast(0) else 0
                                    },
                                    label = { Text("First") }
                                )
                                AssistChip(
                                    onClick = {
                                        selectedChapterChunk = if (newestFirst) 0 else (chapterChunks.size - 1).coerceAtLeast(0)
                                    },
                                    label = { Text("Latest") }
                                )
                            }
                            if (chapterChunks.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    chapterChunks.forEachIndexed { index, chunk ->
                                        val first = chunk.firstOrNull()?.chapterNumber ?: 0
                                        val last = chunk.lastOrNull()?.chapterNumber ?: first
                                        FilterChip(
                                            selected = activeChunkIndex == index,
                                            onClick = { selectedChapterChunk = index },
                                            label = { Text("$first-$last") },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = currentTheme.accentColor(),
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                            if (chapterQuery.isNotBlank()) {
                                Text(
                                    "${filteredChapters.size} matching chapters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = currentTheme.subTextColor()
                                )
                            } else if (chapterChunks.size > 1) {
                                Text(
                                    "Showing ${visibleChapters.size} chapters in this range",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = currentTheme.subTextColor()
                                )
                            }
                        }
                    }
                }
            }

            if (!isLoadingChapters && chapters.isNotEmpty() && visibleChapters.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No chapters match that search.",
                            color = currentTheme.subTextColor(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (isLoadingChapters) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = currentTheme.accentColor())
                    }
                }
            } else if (chapters.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No chapters available from this source.",
                            color = currentTheme.subTextColor(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (visibleChapters.isNotEmpty()) {
                items(visibleChapters) { chapter ->
                    // Read actual downloaded state
                    val isDownloaded = remember(refreshTrigger, chapter.chapterNumber) {
                        downloadRepo.isChapterDownloaded(novel.id, chapter.chapterNumber)
                    }
                    val isDownloading = chapter.chapterNumber in downloadingChapters

                    ChapterListItem(
                        chapter = chapter,
                        currentTheme = currentTheme,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        onClick = { requireAuth { onChapterSelected(chapter) } },
                        onDownloadClick = {
                            requireAuth {
                                if (isDownloaded) {
                                    // Delete chapter local data
                                    val savedCh = downloadRepo.getChaptersFor(novel.id).find { it.chapterNumber == chapter.chapterNumber }
                                    savedCh?.localFilePath?.let { deleteDownloadedText(it) }

                                    downloadRepo.deleteChapter(novel.id, chapter.chapterNumber)
                                    if (downloadRepo.getChaptersFor(novel.id).isEmpty()) {
                                        downloadRepo.deleteItem(novel.id)
                                    }
                                    refreshTrigger++
                                } else {
                                    downloadingChapters = downloadingChapters + chapter.chapterNumber
                                    scope.launch {
                                        try {
                                            downloadRepo.addItem(
                                                DownloadedItem(
                                                    id = novel.id,
                                                    title = novel.title,
                                                    coverUrl = novel.coverUrl,
                                                    type = if (novel.isManga) "MANGA" else "NOVEL",
                                                    sourceName = novel.sourceName
                                                )
                                            )
                                            if (novel.isManga) {
                                                val pages = repository.fetchMangaPages(chapter.url, novel.sourceName)
                                                if (pages.isNotEmpty()) {
                                                    val localPages = cacheMangaChapterPages(
                                                        chapterKey = "${novel.sourceName}:${novel.id}:${chapter.chapterNumber}:${chapter.url}",
                                                        pageUrls = pages,
                                                        persistent = true,
                                                        onProgress = { _, _ -> }
                                                    )
                                                    val offlinePages = localPages.filter { isDownloadedLocalFileAvailable(it) }
                                                    if (offlinePages.size == pages.size) {
                                                        downloadRepo.addChapter(
                                                            DownloadedChapter(
                                                                parentId = novel.id,
                                                                chapterNumber = chapter.chapterNumber,
                                                                chapterTitle = chapter.title,
                                                                localFilePath = offlinePages.joinToString(","),
                                                                pageCount = offlinePages.size
                                                            )
                                                        )
                                                    } else if (downloadRepo.getChaptersFor(novel.id).isEmpty()) {
                                                        downloadRepo.deleteItem(novel.id)
                                                    }
                                                } else if (downloadRepo.getChaptersFor(novel.id).isEmpty()) {
                                                    downloadRepo.deleteItem(novel.id)
                                                }
                                            } else {
                                                val text = repository.fetchChapterText(chapter.url, novel.sourceName)
                                                if (text.isNotEmpty() && !text.startsWith("Failed")) {
                                                    val path = saveDownloadedText(novel.id, chapter.chapterNumber, text)
                                                    if (path.isNotBlank() && isDownloadedLocalFileAvailable(path)) {
                                                        downloadRepo.addChapter(
                                                            DownloadedChapter(
                                                                parentId = novel.id,
                                                                chapterNumber = chapter.chapterNumber,
                                                                chapterTitle = chapter.title,
                                                                localFilePath = path
                                                            )
                                                        )
                                                    } else if (downloadRepo.getChaptersFor(novel.id).isEmpty()) {
                                                        downloadRepo.deleteItem(novel.id)
                                                    }
                                                } else if (downloadRepo.getChaptersFor(novel.id).isEmpty()) {
                                                    downloadRepo.deleteItem(novel.id)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (downloadRepo.getChaptersFor(novel.id).isEmpty()) {
                                                downloadRepo.deleteItem(novel.id)
                                            }
                                        } finally {
                                            downloadingChapters = downloadingChapters - chapter.chapterNumber
                                            refreshTrigger++
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Padding(
    horizontal: Dp = 0.dp,
    vertical: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.padding(horizontal = horizontal, vertical = vertical)) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListItem(
    chapter: Chapter,
    currentTheme: AppTheme,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = currentTheme.accentColor().copy(0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "${chapter.chapterNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.accentColor(),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                color = currentTheme.textColor(),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Download Status Action
            IconButton(onClick = onDownloadClick, modifier = Modifier.padding(end = 4.dp)) {
                when {
                    isDownloading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = currentTheme.accentColor()
                    )
                    isDownloaded -> Icon(
                        Icons.Default.OfflinePin,
                        contentDescription = "Downloaded offline",
                        tint = Color(0xFF4CAF50)
                    )
                    else -> Icon(
                        Icons.Outlined.Download,
                        contentDescription = "Download chapter",
                        tint = currentTheme.subTextColor()
                    )
                }
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = currentTheme.subTextColor()
            )
        }
        Divider(
            color = currentTheme.cardColor(),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
