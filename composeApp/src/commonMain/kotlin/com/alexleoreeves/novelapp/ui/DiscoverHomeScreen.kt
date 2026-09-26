package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverHomeScreen(
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    isKidsMode: Boolean = false,
    onNovelSelected: (UnifiedSearchResult) -> Unit,
    onSearchHistorySaved: (String, String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // ── Genre-based watchable feed state ────────────────────────────────────
    // Row plan: Recommended (learned from watch history) → Latest → 15 genre
    // rows. Only TMDB/AniList video content is fetched — novels, manga, comics
    // and the old medium divider rows are gone from the home feed.
    var rowData by remember { mutableStateOf<Map<String, List<UnifiedSearchResult>>>(emptyMap()) }
    var rowLoading by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rowPages by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var rowHasMore by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feedSeedTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var feedSeedIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // TMDB search merged results
    var searchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var nollywoodSearchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

    val scope = rememberCoroutineScope()

    // ── Genre home feed — shared with the TV app (see data/HomeFeed.kt) ──
    val homeFeed = remember {
        val client = io.ktor.client.HttpClient()
        HomeFeedRepository(
            tmdb = TmdbSource(
                client = client,
                readAccessToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN,
                apiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY
            ),
            anilist = AniListSource(client)
        )
    }
    val rowPlan = remember {
        listOf("recommended" to "Recommended For You", "latest" to "Latest") +
            HomeGenres.all.map { "genre_${it.key}" to it.label }
    }

    fun loadRow(rowKey: String, page: Int = 1) {
        if (rowKey in rowLoading) return
        rowLoading = rowLoading + rowKey
        scope.launch {
            try {
                val fetched = when {
                    rowKey == "recommended" -> homeFeed.recommendedRow(feedSeedTitles, feedSeedIds)
                    rowKey == "latest" -> homeFeed.latestRow(page)
                    else -> HomeGenres.all.firstOrNull { "genre_${it.key}" == rowKey }
                        ?.let { homeFeed.genreRow(it, page) } ?: emptyList()
                }
                if (page <= 1) {
                    rowData = rowData + (rowKey to fetched)
                    rowPages = rowPages + (rowKey to 2)
                    if (rowKey != "recommended" && fetched.isNotEmpty()) {
                        rowHasMore = rowHasMore + rowKey
                    }
                } else if (fetched.isNotEmpty()) {
                    rowData = rowData + (rowKey to (rowData[rowKey].orEmpty() + fetched).distinctBy { it.id })
                    rowPages = rowPages + (rowKey to (page + 1))
                } else {
                    rowHasMore = rowHasMore - rowKey
                }
            } catch (_: Exception) {
                rowHasMore = rowHasMore - rowKey
            } finally {
                rowLoading = rowLoading - rowKey
            }
        }
    }

    // ── Load the watchable feed ──────────────────────────────────────────
    LaunchedEffect(Unit) {
        // Seeds first: what the user actually watched (history + downloads).
        val historyTitles = runCatching { downloadRepo.getWatchHistory() }
            .getOrElse { emptyList() }
            .sortedByDescending { it.updatedAt }
            .map { it.title }
        val downloads = runCatching { downloadRepo.getAllItems() }.getOrElse { emptyList() }
        feedSeedTitles = (historyTitles + downloads.map { it.title })
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(6)
        feedSeedIds = downloads.map { it.id }.filter { it.startsWith("tmdb") }.take(3)

        // Recommended + Latest first, then genre rows in batches of 3 so the
        // device is never hit with ~50 requests at once.
        loadRow("recommended")
        loadRow("latest")
        HomeGenres.all.chunked(3).forEach { chunk ->
            chunk.forEach { g -> loadRow("genre_${g.key}") }
            while (chunk.any { "genre_${it.key}" in rowLoading }) {
                delay(150)
            }
        }
    }

    // ── Debounced multi-source search ─────────────────────────────────────
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            isSearching = false
            searchResults = emptyList()
            nollywoodSearchResults = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        delay(400)
        val q = searchQuery
        val repo = com.alexleoreeves.novelapp.data.NovelSearchRepository(
            rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
            rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
        )
        // Fan out: search TMDB movies + Nollywood YouTube in parallel
        try { searchResults = repo.searchVideo(VideoCategory.MOVIES, q) } catch (_: Exception) { searchResults = emptyList() }
        try { nollywoodSearchResults = repo.searchVideo(VideoCategory.NIGERIAN, q) } catch (_: Exception) { nollywoodSearchResults = emptyList() }
        isSearching = false
    }

    val isSearchActive = searchQuery.length >= 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassOverlayColor)
    ) {
        if (isKidsMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFF9100))
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ChildCare, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("KIDS MODE ACTIVE — Family Content Only", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // ── Search bar ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Rounded.Search,
                        "Search",
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (it.length >= 2) onSearchHistorySaved("Discover", it)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search movies, anime, shows...",
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontSize = 15.sp
                                )
                            }
                            inner()
                        }
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Rounded.Close,
                            "Clear",
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ── Content feed ──────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp), // Removed horizontal padding for edge-to-edge scroll
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (isSearchActive) {
                // Search results — TMDB
                if (searchResults.isNotEmpty()) {
                    item { GlassSectionLabel("Movies & Shows — ${searchResults.size} results", modifier = Modifier.padding(horizontal = 16.dp)) }
                    items(searchResults) { item ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                        }
                    }
                }
                // Search results — Nollywood/YouTube
                if (nollywoodSearchResults.isNotEmpty()) {
                    item {
                        GlassSectionLabel(
                            "Nollywood — ${nollywoodSearchResults.size} results",
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = if (searchResults.isEmpty()) 0.dp else 8.dp)
                        )
                    }
                    items(nollywoodSearchResults) { item ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                        }
                    }
                }
                if (searchResults.isEmpty() && nollywoodSearchResults.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No results found",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                // Browse feed: Recommended → Latest → 15 genre rows. Watchable
                // video only — novels/manga and the old medium divider rows
                // (Movies/Anime/Classic/…) no longer exist on home.
                rowPlan.forEach { (rowKey, label) ->
                    val loading = rowKey in rowLoading
                    val items = rowData[rowKey].orEmpty()
                    if (loading || items.isNotEmpty()) {
                        item(key = "${rowKey}_label") {
                            GlassSectionLabel(label, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
                        }
                        if (items.isEmpty()) {
                            item(key = "${rowKey}_shimmer") { SectionShimmerHorizontal() }
                        } else {
                            item(key = "${rowKey}_row") {
                                DiscoverPosterRow(
                                    items = items,
                                    isLoadingMore = loading,
                                    onItemClick = onNovelSelected,
                                    onLoadMore = {
                                        if (rowKey in rowHasMore && rowKey !in rowLoading) {
                                            loadRow(rowKey, rowPages[rowKey] ?: 2)
                                        }
                                    }
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
//  Video Poster Item — vertical card for horizontal scrolling (Netflix style)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun VideoPosterItem(
    item: UnifiedSearchResult,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassShimmerColor)
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                GlassImagePlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    aspectRatio = 2f / 3f
                )
            }
            
            // Optional: Genre chip overlay
            val overlayTag = when {
                item.mediaKind.isNotBlank() -> item.mediaKind
                item.isAnime -> "Anime"
                else -> null
            }
            if (overlayTag != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = overlayTag,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Video Card Item — horizontal wide card (used in search results)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun VideoCardItem(
    item: UnifiedSearchResult,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        GlassCard(
            onClick = onClick,
            contentPadding = PaddingValues(0.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Left: cover image (3:4 aspect)
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(150.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 28.dp,
                                bottomStart = 28.dp,
                                bottomEnd = 16.dp,
                                topEnd = 16.dp
                            )
                        )
                        .background(GlassShimmerColor)
                ) {
                    if (item.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.coverUrl,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        GlassImagePlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            aspectRatio = 110f / 150f
                        )
                    }
                }

                // Right: text content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (item.mediaKind.isNotBlank()) {
                            GlassGenreChip(text = item.mediaKind)
                        }
                        if (item.isAnime) {
                            GlassGenreChip(text = "Anime")
                        }
                    }
                    if (item.synopsis.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = item.synopsis,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = NeonBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Watch",
                            color = NeonBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPosterRow(
    items: List<UnifiedSearchResult>,
    isLoadingMore: Boolean,
    onItemClick: (UnifiedSearchResult) -> Unit,
    onLoadMore: () -> Unit
) {
    val rowState = remember { LazyListState() }
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = rowState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        // Auto-fetch the next page when the user reaches the end of this row
        // (short rows trigger immediately so the row keeps filling up).
        if (shouldLoadMore && items.isNotEmpty()) onLoadMore()
    }

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.id }) { item ->
            VideoPosterItem(item = item, onClick = { onItemClick(item) })
        }
        if (isLoadingMore) {
            item(key = "__row_loading_more__") {
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassShimmerColor),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = NeonBlue
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionShimmerHorizontal() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassShimmerColor)
            )
        }
    }
}
