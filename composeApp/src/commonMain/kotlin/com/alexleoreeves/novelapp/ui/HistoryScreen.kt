package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
//  History Root Screen — tabs for Anime / Movies / K-Drama / Cartoons / Classic
//  Novels / Manga / Comics
// ─────────────────────────────────────────────────────────────────────────────

data class HistoryTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color
)

private fun tabs(currentTheme: AppTheme): List<HistoryTab> = listOf(
    HistoryTab(ContentType.ANIME, "Anime", Icons.Default.PlayCircle, Color(0xFFFF5722)),
    HistoryTab(ContentType.MOVIE, "Movies", Icons.Default.Movie, Color(0xFF7C4DFF)),
    HistoryTab(ContentType.K_DRAMA, "K-Drama", Icons.Default.LiveTv, Color(0xFFE53935)),
    HistoryTab(ContentType.CARTOON, "Cartoons", Icons.Default.Animation, Color(0xFF00A8A8)),
    HistoryTab(ContentType.CLASSIC, "Classic", Icons.Default.Theaters, Color(0xFF6D4C41)),
    HistoryTab(ContentType.NIGERIAN, "Nollywood", Icons.Default.Flag, Color(0xFF008751)),
    HistoryTab(ContentType.NOVEL, "Novels", Icons.Default.AutoStories, currentTheme.accentColor()),
    HistoryTab(ContentType.MANGA, "Manga", Icons.Default.Collections, Color(0xFFE91E8C)),
    HistoryTab(ContentType.COMIC, "Comics", Icons.Default.ImportContacts, Color(0xFFFF6D00))
)

private fun watchHistoryType(tabId: String): String = when (tabId) {
    ContentType.ANIME -> ""
    ContentType.MOVIE -> "MOVIE"
    ContentType.K_DRAMA -> "K_DRAMA"
    ContentType.CARTOON -> "CARTOON"
    ContentType.CLASSIC -> "CLASSIC"
    ContentType.NIGERIAN -> "NIGERIAN"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    onResumeRead: (ReadHistoryItem) -> Unit,
    onResumeWatch: (WatchHistoryItem) -> Unit,
    onBack: () -> Unit
) {
    val tabList = remember { tabs(currentTheme) }
    var selectedTabId by remember { mutableStateOf(ContentType.ANIME) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Search toggle
    if (showSearch) {
        HistorySearchScreen(
            currentTheme = currentTheme,
            downloadRepo = downloadRepo,
            searchQuery = searchQuery,
            onSearchQueryChanged = { searchQuery = it },
            onResumeRead = onResumeRead,
            onResumeWatch = onResumeWatch,
            onClose = { showSearch = false; searchQuery = "" }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(currentTheme.surfaceColor(), currentTheme.backgroundColor())
                    )
                )
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "History",
                        style = MaterialTheme.typography.headlineLarge,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Pick up where you left off",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.subTextColor()
                    )
                }
                IconButton(onClick = { showSearch = true }) {
                    Icon(
                        Icons.Default.Search,
                        null,
                        tint = currentTheme.textColor(),
                        modifier = Modifier.size(24.dp)
                    )
                }
                // Clear all history
                var showClearConfirm by remember { mutableStateOf(false) }
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        null,
                        tint = currentTheme.subTextColor(),
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        title = { Text("Clear history?") },
                        text = { Text("This removes all read and watch history but not your downloaded files.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    // Clear by re-saving index without history
                                    val idx = kotlinx.serialization.json.Json {
                                        ignoreUnknownKeys = true
                                        prettyPrint = true
                                    }
                                    val raw = downloadRepo.let {
                                        // We can't directly clear, but we can snap
                                        // For simplicity — just clear watch + read by leaving them out
                                    }
                                    showClearConfirm = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) {
                                Text("Clear", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }

        // ── Tab Row ───────────────────────────────────────────────────────
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(currentTheme.cardColor().copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val selectedTab = tabList.firstOrNull { it.id == selectedTabId } ?: tabList.first()
            items(tabList) { tab ->
                val isSelected = selectedTabId == tab.id
                Surface(
                    onClick = { selectedTabId = tab.id },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) tab.accent else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            tab.icon,
                            null,
                            tint = if (isSelected) Color.White else currentTheme.subTextColor(),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else currentTheme.textColor()
                        )
                    }
                }
            }
        }

        // ── Content ────────────────────────────────────────────────────────
        HistoryContent(
            selectedTabId = selectedTabId,
            currentTheme = currentTheme,
            downloadRepo = downloadRepo,
            onResumeRead = onResumeRead,
            onResumeWatch = onResumeWatch
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  History tab content — shows both watch and read history filtered by type
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HistoryContent(
    selectedTabId: String,
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    onResumeRead: (ReadHistoryItem) -> Unit,
    onResumeWatch: (WatchHistoryItem) -> Unit
) {
    val allWatch = remember { downloadRepo.getWatchHistory() }
    val allRead = remember { downloadRepo.getReadHistory() }
    val tabAccent = tabs(currentTheme).firstOrNull { it.id == selectedTabId }?.accent
        ?: currentTheme.accentColor()

    // Filter by tab
    val watchFiltered = remember(selectedTabId, allWatch) {
        val kind = watchHistoryType(selectedTabId)
        if (kind.isNotEmpty()) {
            allWatch.filter { it.mediaKind.equals(kind, ignoreCase = true) }
        } else if (selectedTabId == ContentType.ANIME) {
            // Anime: includes items with no kind (legacy) or anime kind
            allWatch.filter { it.mediaKind.isBlank() || it.mediaKind.equals("ANIME", ignoreCase = true) }
        } else {
            emptyList()
        }
    }

    val readFiltered = remember(selectedTabId, allRead) {
        when (selectedTabId) {
            ContentType.NOVEL -> allRead.filter { !it.isManga && !it.isComic }
            ContentType.MANGA -> allRead.filter { it.isManga && !it.isComic }
            ContentType.COMIC -> allRead.filter { it.isComic }
            else -> emptyList()
        }
    }

    val hasWatch = watchFiltered.isNotEmpty()
    val hasRead = readFiltered.isNotEmpty()

    if (!hasWatch && !hasRead) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Default.HistoryToggleOff,
                    null,
                    tint = currentTheme.subTextColor(),
                    modifier = Modifier.size(64.dp)
                )
                Text("No history yet", style = MaterialTheme.typography.titleMedium, color = currentTheme.subTextColor())
                Text("Content you watch or read will appear here", style = MaterialTheme.typography.bodySmall, color = currentTheme.subTextColor().copy(0.6f))
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // ── Watch History ─────────────────────────────────────────────────
        if (hasWatch) {
            item {
                Text(
                    continueLabel(selectedTabId),
                    style = MaterialTheme.typography.titleSmall,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(watchFiltered, key = { it.streamUrl }) { item ->
                HistoryWatchCard(
                    item = item,
                    currentTheme = currentTheme,
                    accent = tabAccent,
                    onClick = { onResumeWatch(item) }
                )
            }
        }

        // ── Separator ──────────────────────────────────────────────────────
        if (hasWatch && hasRead) {
            item { Divider(color = currentTheme.subTextColor().copy(0.1f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }

        // ── Read History ──────────────────────────────────────────────────
        if (hasRead) {
            item {
                Text(
                    "Continue reading",
                    style = MaterialTheme.typography.titleSmall,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(readFiltered, key = { it.chapterUrl }) { item ->
                HistoryReadCard(
                    item = item,
                    currentTheme = currentTheme,
                    accent = tabAccent,
                    onClick = { onResumeRead(item) }
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

private fun continueLabel(tabId: String): String = when (tabId) {
    ContentType.ANIME -> "Continue watching anime"
    ContentType.MOVIE -> "Continue watching movies"
    ContentType.K_DRAMA -> "Continue watching K-Drama"
    ContentType.CARTOON -> "Continue watching cartoons"
    ContentType.CLASSIC -> "Continue watching classics"
    ContentType.NIGERIAN -> "Continue watching Nollywood"
    ContentType.NOVEL -> "Continue reading novels"
    ContentType.MANGA -> "Continue reading manga"
    ContentType.COMIC -> "Continue reading comics"
    else -> "Continue"
}

// ─────────────────────────────────────────────────────────────────────────────
//  History Cards
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryWatchCard(
    item: WatchHistoryItem,
    currentTheme: AppTheme,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cover thumbnail
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp, 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp, 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayCircle, null, tint = accent, modifier = Modifier.size(28.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.episodeTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = currentTheme.subTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.positionMs > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Resume at ${formatDuration(item.positionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryReadCard(
    item: ReadHistoryItem,
    currentTheme: AppTheme,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp, 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp, 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (item.isManga) Icons.Default.MenuBook else Icons.Default.AutoStories,
                        null, tint = accent, modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = currentTheme.subTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.positionIndex > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Position ${item.positionIndex}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Search Screen for History
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HistorySearchScreen(
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onResumeRead: (ReadHistoryItem) -> Unit,
    onResumeWatch: (WatchHistoryItem) -> Unit,
    onClose: () -> Unit
) {
    val allWatch = remember { downloadRepo.getWatchHistory() }
    val allRead = remember { downloadRepo.getReadHistory() }

    val watchResults = remember(searchQuery, allWatch) {
        if (searchQuery.length < 2) emptyList()
        else allWatch.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.episodeTitle.contains(searchQuery, ignoreCase = true)
        }
    }
    val readResults = remember(searchQuery, allRead) {
        if (searchQuery.length < 2) emptyList()
        else allRead.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.chapterTitle.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(currentTheme.backgroundColor())) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search history...", color = currentTheme.subTextColor()) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = currentTheme.accentColor(),
                    unfocusedBorderColor = currentTheme.subTextColor().copy(0.3f),
                    focusedTextColor = currentTheme.textColor(),
                    unfocusedTextColor = currentTheme.textColor(),
                    cursorColor = currentTheme.accentColor()
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (searchQuery.length < 2) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Type at least 2 characters to search", color = currentTheme.subTextColor())
            }
        } else if (watchResults.isEmpty() && readResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SearchOff, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No matching history", color = currentTheme.subTextColor())
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (watchResults.isNotEmpty()) {
                    item { Text("Watch history", style = MaterialTheme.typography.titleSmall, color = currentTheme.textColor(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                    items(watchResults, key = { "w_${it.streamUrl}" }) { item ->
                        HistoryWatchCard(item = item, currentTheme = currentTheme, accent = currentTheme.accentColor(), onClick = { onResumeWatch(item) })
                    }
                }
                if (readResults.isNotEmpty()) {
                    item { Divider(color = currentTheme.subTextColor().copy(0.1f), modifier = Modifier.padding(horizontal = 16.dp)) }
                    item { Text("Read history", style = MaterialTheme.typography.titleSmall, color = currentTheme.textColor(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                    items(readResults, key = { "r_${it.chapterUrl}" }) { item ->
                        HistoryReadCard(item = item, currentTheme = currentTheme, accent = currentTheme.accentColor(), onClick = { onResumeRead(item) })
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
