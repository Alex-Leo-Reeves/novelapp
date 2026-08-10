package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun TvHomeScreen(
    section: TvSection,
    account: SavedUserAccount?,
    config: TvRemoteConfig = TvRemoteConfigDefaults.default,
    selectedProfile: com.alexleoreeves.novelapp.tv.ui.TvProfile? = null,
    onSwitchProfile: () -> Unit = {},
    onMediaSelected: (UnifiedSearchResult) -> Unit,
    onSearch: (String) -> Unit,
    onReadNovel: (String, String) -> Unit = { _, _ -> },
    onPlaySports: (String, String) -> Unit = { _, _ -> },
    onSignOut: () -> Unit = {},
    onBackHome: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedSearchCategory by remember { mutableStateOf("all") }
    var showSearch by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchPerformed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val novelRepo = remember { TvNovelSearchRepository() }

    fun loadContent() {
        scope.launch {
            if (!searchPerformed) {
                isLoading = true
                items = when (section) {
                    TvSection.HOME -> emptyList()
                    TvSection.ANIME -> fetchContentHome("anime")
                    TvSection.MANGA -> fetchContentHome("manga")
                    TvSection.COMICS -> fetchContentHome("comic")
                    TvSection.NOVELS -> novelRepo.fetchPopularNovels(1)
                    TvSection.CREATION -> emptyList()
                    TvSection.DONGHUA -> fetchContentHome("donghua")
                    TvSection.K_DRAMA -> fetchContentHome("kdrama")
                    TvSection.CARTOON -> fetchContentHome("cartoon")
                    TvSection.CLASSIC -> fetchContentHome("classic")
                    TvSection.MOVIES -> fetchContentHome("movie")
                    TvSection.NOLLYWOOD -> fetchContentHome("nigerian")
                    TvSection.SPORTS -> emptyList()
                    TvSection.DOWNLOADS -> emptyList()
                    TvSection.YOU -> emptyList()
                }
                isLoading = false
            }
        }
    }

    LaunchedEffect(section) { loadContent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            // One back press = one step back: close the search keyboard first,
            // then clear the search-results grid back to the Home feed, then
            // return to the Home section. The OS handles the final exit from Home.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                    if (showSearch) {
                        showSearch = false
                        true
                    } else if (searchPerformed) {
                        searchPerformed = false
                        items = emptyList()
                        searchQuery = ""
                        loadContent()
                        true
                    } else {
                        false
                    }
                } else false
            }
    ) {
        // Top bar with search
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A12))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                section.label,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Premium badge
                if (account?.isPremium == true) {
                    Surface(
                        color = Color(0xFF00BFFF).copy(0.2f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Verified, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(16.dp))
                            Text("PREMIUM", color = Color(0xFF00BFFF), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                var searchBtnFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { showSearch = !showSearch },
                    shape = RoundedCornerShape(10.dp),
                    color = if (searchBtnFocused) Color(0xFF1C1C2E) else Color(0xFF14141E),
                    border = if (searchBtnFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.onFocusChanged { searchBtnFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(20.dp))
                        Text("Search", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (showSearch) {
            val defaultCat = when (section) {
                TvSection.ANIME -> "anime"
                TvSection.MANGA -> "manga"
                TvSection.COMICS -> "comic"
                TvSection.NOVELS -> "novel"
                TvSection.DONGHUA -> "donghua"
                TvSection.K_DRAMA -> "kdrama"
                TvSection.CARTOON -> "cartoon"
                TvSection.CLASSIC -> "classic"
                TvSection.MOVIES -> "movie"
                TvSection.NOLLYWOOD -> "nigerian"
                else -> "all"
            }
            TvSearchScreen(
                initialQuery = searchQuery,
                selectedCategory = selectedSearchCategory.takeIf { it != "all" } ?: defaultCat,
                onCategoryChange = { selectedSearchCategory = it },
                onSearch = { query, category ->
                    if (query.isNotBlank()) {
                        searchQuery = query
                        selectedSearchCategory = category
                        scope.launch {
                            isLoading = true
                            items = searchTvContentForCategory(category, query, novelRepo)
                            searchPerformed = true
                            isLoading = false
                        }
                    }
                    showSearch = false
                },
                onClose = { showSearch = false }
            )
        }

        // Content area. Home search shows the results grid instead of silently
        // returning to the feed — that was the "search does nothing" bug.
        when {
            section == TvSection.HOME && !searchPerformed -> TvHomeFeed(
                account = account,
                config = config,
                onMediaSelected = onMediaSelected
            )

            section == TvSection.HOME && searchPerformed -> SearchResultsGrid(
                items = items,
                isLoading = isLoading,
                query = searchQuery,
                onMediaSelected = onMediaSelected
            )

            section == TvSection.CREATION -> TvCreationScreen(
                account = account,
                onReadNovel = onReadNovel,
                onBackHome = onBackHome
            )

            section == TvSection.SPORTS -> TvSportsScreen(
                account = account,
                onPlay = onPlaySports,
                onBack = onBackHome
            )

            section == TvSection.DOWNLOADS -> TvDownloadsScreen(account)

            section == TvSection.YOU -> TvYouScreen(
                account = account,
                selectedProfile = selectedProfile,
                onSwitchProfile = onSwitchProfile,
                onSignOut = onSignOut,
                onBack = onBackHome
            )

            else -> {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
                    }
                } else if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.SearchOff, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(64.dp))
                            Text("No content found", color = Color.White.copy(0.4f), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(180.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items, key = { it.id }) { item ->
                            TvMediaCard(
                                item = item,
                                onClick = { onMediaSelected(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

suspend fun searchTvContentForCategory(
    category: String,
    query: String,
    novelRepo: TvNovelSearchRepository
): List<UnifiedSearchResult> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()
    val normalized = category.lowercase().ifBlank { "all" }
    if (normalized == "novel") return novelRepo.searchNovels(trimmed)
    if (normalized != "all") return searchContent(normalized, trimmed)

    val categories = listOf("anime", "movie", "donghua", "kdrama", "cartoon", "classic", "nigerian", "manga", "comic", "novel")
    return coroutineScope {
        categories.map { cat ->
            async {
                if (cat == "novel") novelRepo.searchNovels(trimmed) else searchContent(cat, trimmed)
            }
        }.awaitAll()
            .flatten()
            .distinctBy { item -> item.detailPageUrl.ifBlank { item.id } }
            .take(96)
    }
}

@Composable
private fun SearchResultsGrid(
    items: List<UnifiedSearchResult>,
    isLoading: Boolean,
    query: String,
    onMediaSelected: (UnifiedSearchResult) -> Unit
) {
    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
        }
        return
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SearchOff, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(64.dp))
                Text("No results for \"$query\"", color = Color.White.copy(0.4f), style = MaterialTheme.typography.titleLarge)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Text(
            "Results for \"$query\" (${items.size})",
            color = Color.White.copy(0.7f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.id }) { item ->
                TvMediaCard(item = item, onClick = { onMediaSelected(item) })
            }
        }
    }
}

@Composable
private fun TvHomeFeed(
    account: SavedUserAccount?,
    config: TvRemoteConfig = TvRemoteConfigDefaults.default,
    onMediaSelected: (UnifiedSearchResult) -> Unit
) {
    var rowData by remember { mutableStateOf<Map<String, List<UnifiedSearchResult>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }

    val novelRepo = remember { TvNovelSearchRepository() }

    LaunchedEffect(config.version) {
        isLoading = true
        val rows = config.homeRows.ifEmpty { TvRemoteConfigDefaults.default.homeRows }
        val fetched = rows.map { row ->
            async {
                row.key to runCatching {
                    if (row.type == "novel") {
                        novelRepo.fetchPopularNovels(1)
                    } else {
                        fetchContentHome(row.type, 1)
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        rowData = fetched.toMap()
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Text(
            "Welcome${if (account != null) ", ${account.username}" else ""}",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
        Text(
            config.branding.tagline.ifBlank { "Discover anime, novels, manga, movies & more" },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(0.6f)
        )

        if (isLoading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Purple500)
            }
        } else {
            val rows = config.homeRows.ifEmpty { TvRemoteConfigDefaults.default.homeRows }
            rows.forEach { row ->
                val list = rowData[row.key].orEmpty()
                if (list.isNotEmpty()) {
                    ContentRow(row.label.ifBlank { row.key }, list, onMediaSelected)
                }
            }
            if (rows.all { rowData[it.key].orEmpty().isEmpty() }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CloudOff, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(48.dp))
                        Text("Could not load content right now", color = Color.White.copy(0.4f))
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ContentRow(
    label: String,
    items: List<UnifiedSearchResult>,
    onMediaSelected: (UnifiedSearchResult) -> Unit
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items.take(12), key = { it.id }) { item ->
                TvMediaCard(item = item, onClick = { onMediaSelected(item) }, compact = true)
            }
        }
    }
}

@Composable
fun TvMediaCard(
    item: UnifiedSearchResult,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "cardScale")

    val cardWidth = if (compact) 160.dp else 180.dp

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isFocused -> TvCard
                compact -> Color(0xFF0C0C14)
                else -> Color(0xFF0C0C12)
            }
        ),
        border = if (isFocused) BorderStroke(3.dp, Purple500) else BorderStroke(1.dp, Color.White.copy(0.05f)),
        modifier = Modifier
            .width(cardWidth)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(0.8f)),
                            startY = 0.5f
                        )
                    )
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.sourceName.isNotBlank()) {
                    Text(
                        item.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.5f),
                        maxLines = 1
                    )
                }
            }
            // Media type badge
            val badgeColor = when {
                item.isAnime -> Color(0xFF00BFFF)
                item.isManga || item.isComic -> Color(0xFF00BFFF)
                item.isVideo -> Color(0xFF06D6A0)
                else -> Color(0xFFF59E0B)
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = badgeColor,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            ) {
                Text(
                    when {
                        item.isAnime -> "A"
                        item.isManga -> "M"
                        item.isComic -> "C"
                        item.isVideo -> "V"
                        else -> "N"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}
