package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.currentTimeMillis
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
//  Content type tab selector: Novels / Manga / Anime / TMDB media
// ─────────────────────────────────────────────────────────────────────────────
enum class ContentTab(val label: String, val icon: ImageVector) {
    NOVELS("Novels", Icons.Default.AutoStories),
    MANGA("Manga", Icons.Default.Collections),
    COMIC("Comics", Icons.Default.ImportContacts),
    ANIME("Anime", Icons.Default.PlayCircle),
    DONGHUA("Donghua", Icons.Default.Language),
    K_DRAMA("K-Drama", Icons.Default.LiveTv),
    CARTOON("Cartoon", Icons.Default.Animation),
    OLDER_CARTOON("Classic", Icons.Default.Theaters),
    MOVIES("Movies", Icons.Default.Movie),
    NIGERIAN_FILMS("Nollywood", Icons.Default.Flag)  // Nigerian Films tab
}

// Tab accent colors
private val animeAccent = Color(0xFFFF5722)
private val donghuaAccent = Color(0xFFFF6F00)
private val kDramaAccent = Color(0xFFE53935)
private val cartoonAccent = Color(0xFF00A8A8)
private val comicAccent = Color(0xFFFF6D00)    // orange — Western comics
private val olderCartoonAccent = Color(0xFF6D4C41) // brown — nostalgia
private val moviesAccent = Color(0xFF7C4DFF)
private val nigerianAccent = Color(0xFF008751)  // Nollywood green (Nigeria flag green)

private fun ContentTab.videoCategory(): VideoCategory? = when (this) {
    ContentTab.ANIME -> VideoCategory.ANIME
    ContentTab.DONGHUA -> VideoCategory.DONGHUA
    ContentTab.K_DRAMA -> VideoCategory.K_DRAMA
    ContentTab.CARTOON -> VideoCategory.CARTOON
    ContentTab.OLDER_CARTOON -> VideoCategory.CLASSIC
    ContentTab.MOVIES -> VideoCategory.MOVIES
    ContentTab.NIGERIAN_FILMS -> VideoCategory.NIGERIAN
    else -> null
}

private fun ContentTab.tabAccent(currentTheme: AppTheme): Color = when (this) {
    ContentTab.ANIME -> animeAccent
    ContentTab.DONGHUA -> donghuaAccent
    ContentTab.K_DRAMA -> kDramaAccent
    ContentTab.CARTOON -> cartoonAccent
    ContentTab.COMIC -> comicAccent
    ContentTab.OLDER_CARTOON -> olderCartoonAccent
    ContentTab.MOVIES -> moviesAccent
    ContentTab.NIGERIAN_FILMS -> nigerianAccent
    else -> currentTheme.accentColor()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Discover Home Screen: separated Novels / Manga / Anime dashboard
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun DiscoverHomeScreen(
    currentTheme: AppTheme,
    contentTab: ContentTab = ContentTab.NOVELS,
    initialSearchQuery: String = "",
    recentSearches: List<String> = emptyList(),
    isDesktop: Boolean = false,
    onContentTabChanged: (ContentTab) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchCommitted: (ContentTab, String) -> Unit = { _, _ -> },
    onNovelSelected: (UnifiedSearchResult) -> Unit
) {
    val repository = remember {
        NovelSearchRepository(
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }

    // ── State ──────────────────────────────────────────────────────────────
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    var activeTab by remember { mutableStateOf(contentTab) }
    var selectedCategory by remember { mutableStateOf(NovelCategory.ALL) }
    var selectedMangaGenre by remember { mutableStateOf("All") }
    var selectedAnimeGenre by remember { mutableStateOf("All") }
    var isSearching by remember { mutableStateOf(false) }

    var popularItemsByTab by remember { mutableStateOf<Map<ContentTab, List<UnifiedSearchResult>>>(emptyMap()) }
    var airingAnime by remember { mutableStateOf<List<AnimeResult>>(emptyList()) }
    var animeSearchResults by remember { mutableStateOf<List<AnimeResult>>(emptyList()) }
    var videoItemsByTab by remember { mutableStateOf<Map<ContentTab, List<UnifiedSearchResult>>>(emptyMap()) }
    var videoSearchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoadingPopular by remember { mutableStateOf(false) }
    var isLoadingAnime by remember { mutableStateOf(false) }
    var isLoadingVideo by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showTopArea by remember { mutableStateOf(true) }
    var pullDistance by remember { mutableStateOf(0f) }
    var pageByTab by remember {
        mutableStateOf(ContentTab.values().associateWith { 1 })
    }

    val categories = NovelCategory.values().toList()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(contentTab) {
        if (activeTab != contentTab) {
            searchQuery = ""
            onSearchQueryChanged("")
            searchResults = emptyList()
            animeSearchResults = emptyList()
            videoSearchResults = emptyList()
            isSearching = false
        }
        activeTab = contentTab
        selectedCategory = NovelCategory.ALL
        selectedMangaGenre = "All"
        selectedAnimeGenre = "All"
        showTopArea = true
    }

    LaunchedEffect(activeTab) {
        onContentTabChanged(activeTab)
        gridState.scrollToItem(0)
        showTopArea = true
        pullDistance = 0f
    }

    // Keep the header always visible to prevent layout oscillation that fights user scrolling.
    // The header is compact enough that auto-hiding is not worth the scroll jank.
    LaunchedEffect(Unit) {
        showTopArea = true
    }

    // ── Lazy popular content load for the active tab only ──────────────────
    LaunchedEffect(activeTab) {
        if (activeTab == ContentTab.NOVELS && popularItemsByTab[ContentTab.NOVELS].isNullOrEmpty()) {
            isLoadingPopular = true
            val loaded = repository.fetchPopularNovels(page = pageByTab[ContentTab.NOVELS] ?: 1)
            popularItemsByTab = popularItemsByTab + (ContentTab.NOVELS to loaded)
            isLoadingPopular = false
        }
        if (activeTab == ContentTab.MANGA && popularItemsByTab[ContentTab.MANGA].isNullOrEmpty()) {
            isLoadingPopular = true
            val loaded = repository.fetchPopularManga(page = pageByTab[ContentTab.MANGA] ?: 1)
            popularItemsByTab = popularItemsByTab + (ContentTab.MANGA to loaded)
            isLoadingPopular = false
        }
        if (activeTab == ContentTab.COMIC && popularItemsByTab[ContentTab.COMIC].isNullOrEmpty()) {
            isLoadingPopular = true
            val loaded = repository.fetchPopularComics(page = pageByTab[ContentTab.COMIC] ?: 1)
            popularItemsByTab = popularItemsByTab + (ContentTab.COMIC to loaded)
            isLoadingPopular = false
        }
    }

    // ── TMDB-backed video tabs ────────────────────────────────────────────
    LaunchedEffect(activeTab) {
        val category = activeTab.videoCategory() ?: return@LaunchedEffect
        if (videoItemsByTab[activeTab].isNullOrEmpty()) {
            isLoadingVideo = true
            val loaded = repository.fetchVideo(category)
            videoItemsByTab = videoItemsByTab + (activeTab to loaded)
            isLoadingVideo = false
        }
    }

    // ── Filter popular content by active tab ───────────────────────────────
    val activePopularItems = popularItemsByTab[activeTab].orEmpty()
    val filteredPopular by remember(activePopularItems, activeTab, selectedCategory, selectedMangaGenre) {
        derivedStateOf {
            var list = when (activeTab) {
                ContentTab.NOVELS -> activePopularItems.filter { !it.isManga && !it.isAnime && !it.isComic }
                ContentTab.MANGA -> activePopularItems.filter { it.isManga }
                ContentTab.COMIC -> activePopularItems.filter { it.isComic }
                ContentTab.ANIME -> activePopularItems.filter { it.isAnime }
                ContentTab.DONGHUA,
                ContentTab.K_DRAMA,
                ContentTab.CARTOON,
                ContentTab.OLDER_CARTOON,
                ContentTab.MOVIES,
                ContentTab.NIGERIAN_FILMS -> emptyList()
            }
            if (activeTab == ContentTab.NOVELS && selectedCategory != NovelCategory.ALL) {
                val genreFiltered = list.filter {
                    it.genre.contains(selectedCategory.label, ignoreCase = true)
                }
                if (genreFiltered.isNotEmpty()) list = genreFiltered
            }
            if (activeTab == ContentTab.MANGA && selectedMangaGenre != "All") {
                val genreFiltered = list.filter {
                    it.genre.contains(selectedMangaGenre, ignoreCase = true) ||
                        it.title.contains(selectedMangaGenre, ignoreCase = true)
                }
                if (genreFiltered.isNotEmpty()) list = genreFiltered
            }
            list
        }
    }

    // ── Filter search results by active tab ────────────────────────────────
    val filteredSearch by remember(searchResults, activeTab, videoSearchResults) {
        derivedStateOf {
            when (activeTab) {
                ContentTab.NOVELS -> searchResults.filter { !it.isManga && !it.isAnime && !it.isComic }
                ContentTab.MANGA -> searchResults.filter { it.isManga }
                ContentTab.COMIC -> searchResults.filter { it.isComic }
                ContentTab.ANIME,
                ContentTab.DONGHUA,
                ContentTab.K_DRAMA,
                ContentTab.CARTOON,
                ContentTab.OLDER_CARTOON,
                ContentTab.MOVIES,
                ContentTab.NIGERIAN_FILMS -> videoSearchResults
            }
        }
    }

    // ── Debounced search ───────────────────────────────────────────────────
    LaunchedEffect(searchQuery, activeTab) {
        if (searchQuery.length < 2) {
            isSearching = false
            searchResults = emptyList()
            animeSearchResults = emptyList()
            videoSearchResults = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(400)
        val q = searchQuery
        val tab = activeTab
        when (tab) {
            ContentTab.ANIME,
            ContentTab.DONGHUA,
            ContentTab.K_DRAMA,
            ContentTab.CARTOON,
            ContentTab.OLDER_CARTOON,
            ContentTab.MOVIES,
            ContentTab.NIGERIAN_FILMS -> {
                val category = tab.videoCategory()
                videoSearchResults = if (category != null) repository.searchVideo(category, q) else emptyList()
                isSearching = false
                onSearchCommitted(tab, q)
            }
            else -> {
                searchResults = when (tab) {
                    ContentTab.NOVELS -> repository.searchNovels(q)
                    ContentTab.MANGA -> repository.searchManga(q)
                    ContentTab.COMIC -> repository.searchComics(q)
                    else -> emptyList()
                }
                isSearching = false
                onSearchCommitted(tab, q)
            }
        }
    }

    val displayItems = if (searchQuery.length >= 2) {
        filteredSearch
    } else {
        activeTab.videoCategory()?.let { videoItemsByTab[activeTab].orEmpty() } ?: filteredPopular
    }
    val tabAccent = activeTab.tabAccent(currentTheme)
    fun refreshActiveTab() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            val nextPage = ((pageByTab[activeTab] ?: 1) + 1).let { if (it > 8) 1 else it }
            pageByTab = pageByTab + (activeTab to nextPage)
            try {
                if (searchQuery.length >= 2) {
                    when (activeTab) {
                        ContentTab.ANIME,
                        ContentTab.DONGHUA,
                        ContentTab.K_DRAMA,
                        ContentTab.CARTOON,
                        ContentTab.OLDER_CARTOON,
                        ContentTab.MOVIES,
                        ContentTab.NIGERIAN_FILMS -> {
                            val category = activeTab.videoCategory()
                            videoSearchResults = if (category != null) {
                                repository.searchVideo(category, searchQuery, nextPage)
                            } else {
                                emptyList()
                            }
                        }
                        ContentTab.NOVELS -> searchResults = repository.searchNovels(searchQuery)
                        ContentTab.MANGA -> searchResults = repository.searchManga(searchQuery)
                        ContentTab.COMIC -> searchResults = repository.searchComics(searchQuery)
                    }
                } else {
                    when (activeTab) {
                        ContentTab.NOVELS,
                        ContentTab.MANGA,
                        ContentTab.COMIC -> {
                            isLoadingPopular = true
                            val loaded = when (activeTab) {
                                ContentTab.NOVELS -> repository.fetchPopularNovels(page = nextPage)
                                ContentTab.COMIC -> repository.fetchPopularComics(page = nextPage)
                                else -> repository.fetchPopularManga(page = nextPage)
                            }
                            popularItemsByTab = popularItemsByTab + (activeTab to loaded)
                            isLoadingPopular = false
                        }
                        ContentTab.ANIME,
                        ContentTab.DONGHUA,
                        ContentTab.K_DRAMA,
                        ContentTab.CARTOON,
                        ContentTab.OLDER_CARTOON,
                        ContentTab.MOVIES,
                        ContentTab.NIGERIAN_FILMS -> {
                            val category = activeTab.videoCategory()
                            if (category != null) {
                                isLoadingVideo = true
                                videoItemsByTab = videoItemsByTab + (activeTab to repository.fetchVideo(category, nextPage))
                                isLoadingVideo = false
                            }
                        }
                    }
                }
                gridState.animateScrollToItem(0)
            } finally {
                isLoadingPopular = false
                isLoadingAnime = false
                isLoadingVideo = false
                isRefreshing = false
                pullDistance = 0f
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showTopArea,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(currentTheme.surfaceColor(), currentTheme.backgroundColor())
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                activeTab.label,
                                style = MaterialTheme.typography.headlineLarge,
                                color = currentTheme.textColor(),
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "Search, browse, and continue your stories.",
                                style = MaterialTheme.typography.bodySmall,
                                color = currentTheme.subTextColor()
                            )
                        }
                        Icon(
                            activeTab.icon,
                            null,
                            tint = tabAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            onSearchQueryChanged(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                when (activeTab) {
                                    ContentTab.NOVELS -> "Search light novels..."
                                    ContentTab.MANGA -> "Search manga titles..."
                                    ContentTab.COMIC -> "Search western comics..."
                                    ContentTab.ANIME -> "Search anime..."
                                    ContentTab.DONGHUA -> "Search donghua..."
                                    ContentTab.K_DRAMA -> "Search K-drama..."
                                    ContentTab.CARTOON -> "Search cartoons..."
                                    ContentTab.OLDER_CARTOON -> "Search classic cartoons..."
                                    ContentTab.MOVIES -> "Search movies..."
                                    ContentTab.NIGERIAN_FILMS -> "Search Nollywood..."
                                },
                                color = currentTheme.subTextColor()
                            )
                        },
                        leadingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = tabAccent
                                )
                            } else {
                                Icon(
                                    Icons.Default.Search,
                                    null,
                                    tint = tabAccent
                                )
                            }
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        onSearchQueryChanged("")
                                    }
                                ) {
                                    Icon(Icons.Default.Close, null, tint = currentTheme.subTextColor())
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tabAccent,
                            unfocusedBorderColor = currentTheme.subTextColor().copy(0.3f),
                            focusedTextColor = currentTheme.textColor(),
                            unfocusedTextColor = currentTheme.textColor(),
                            cursorColor = tabAccent,
                            unfocusedContainerColor = currentTheme.cardColor().copy(0.5f),
                            focusedContainerColor = currentTheme.cardColor().copy(0.5f)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(Modifier.height(14.dp))

                    AnimatedVisibility(visible = recentSearches.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 10.dp)
                        ) {
                            items(recentSearches.take(3)) { query ->
                                AssistChip(
                                    onClick = {
                                        searchQuery = query
                                        onSearchQueryChanged(query)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = tabAccent
                                        )
                                    },
                                    label = {
                                        Text(
                                            query,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = currentTheme.cardColor(),
                                        labelColor = currentTheme.textColor()
                                    ),
                                    border = AssistChipDefaults.assistChipBorder(
                                        enabled = true,
                                        borderColor = tabAccent.copy(alpha = 0.35f)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Swipeable tab rail
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(currentTheme.cardColor().copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(ContentTab.values().toList()) { tab ->
                            val selected = activeTab == tab
                            val tabColor = tab.tabAccent(currentTheme)
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 104.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) tabColor else Color.Transparent)
                                    .clickable {
                                        activeTab = tab
                                        searchQuery = ""
                                        onSearchQueryChanged("")
                                        onContentTabChanged(tab)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        tab.icon,
                                        null,
                                        tint = if (selected) Color.White else currentTheme.subTextColor(),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        tab.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color.White else currentTheme.subTextColor()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Genre / Filter Chips ───────────────────────────────────────────
        // Novel genre chips
        AnimatedVisibility(
            visible = showTopArea && activeTab == ContentTab.NOVELS,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                category.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = currentTheme.accentColor(),
                            selectedLabelColor = Color.White,
                            containerColor = currentTheme.cardColor(),
                            labelColor = currentTheme.subTextColor()
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = isSelected,
                            selectedBorderColor = currentTheme.accentColor(),
                            borderColor = currentTheme.subTextColor().copy(0.3f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        // Manga genre chips
        AnimatedVisibility(
            visible = showTopArea && activeTab == ContentTab.MANGA,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val mangaGenres = listOf("All","Action","Romance","Fantasy","Horror","Comedy","Sports","Sci-Fi","Supernatural","Manhwa","Manhua","Webtoon")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mangaGenres) { genre ->
                    FilterChip(
                        selected = selectedMangaGenre == genre,
                        onClick = { selectedMangaGenre = genre },
                        label = { Text(genre) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE91E8C),
                            selectedLabelColor = Color.White,
                            containerColor = currentTheme.cardColor(),
                            labelColor = currentTheme.subTextColor()
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selectedMangaGenre == genre,
                            selectedBorderColor = Color(0xFFE91E8C),
                            borderColor = currentTheme.subTextColor().copy(0.3f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        // Anime genre chips
        AnimatedVisibility(
            visible = showTopArea && activeTab == ContentTab.ANIME,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val animeGenres = listOf("All","Action","Romance","Fantasy","Horror","Comedy","Sports","Sci-Fi","Supernatural","Isekai","Shonen","Seinen")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(animeGenres) { genre ->
                    FilterChip(
                        selected = selectedAnimeGenre == genre,
                        onClick = { selectedAnimeGenre = genre },
                        label = { Text(genre) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = animeAccent,
                            selectedLabelColor = Color.White,
                            containerColor = currentTheme.cardColor(),
                            labelColor = currentTheme.subTextColor()
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selectedAnimeGenre == genre,
                            selectedBorderColor = animeAccent,
                            borderColor = currentTheme.subTextColor().copy(0.3f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        // ── Section Label ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showTopArea,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            if (searchQuery.length >= 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = tabAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Results for \"$searchQuery\"", style = MaterialTheme.typography.labelLarge, color = currentTheme.subTextColor())
                    val resultCount = when {
                        activeTab.videoCategory() != null -> videoSearchResults.size
                        else -> filteredSearch.size
                    }
                    if (resultCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("· $resultCount found", style = MaterialTheme.typography.labelLarge, color = tabAccent, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (activeTab) {
                            ContentTab.NOVELS -> "Popular Novels"
                            ContentTab.MANGA -> "Popular Manga"
                            ContentTab.COMIC -> "Western Comics"
                            ContentTab.ANIME -> "Currently Airing"
                            ContentTab.DONGHUA -> "Popular Donghua"
                            ContentTab.K_DRAMA -> "Popular K-Drama"
                            ContentTab.CARTOON -> "Popular Cartoons"
                            ContentTab.OLDER_CARTOON -> "Classic Cartoons"
                            ContentTab.MOVIES -> "Popular Movies"
                            ContentTab.NIGERIAN_FILMS -> "Popular Nollywood"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Content Area ────────────────────────────────────────────────────
        val animeCols = if (isDesktop) 4 else 2
        val mangaCols = if (isDesktop) 5 else 3
        val novelCols = if (isDesktop) 4 else 2
        val mediaCols = if (isDesktop) 4 else 2

    // Pull-to-refresh: uses grid overscroll detection via scroll offset.
    // Only triggers when the user is actively scrolling past the top — ignores layout shifts.
    Box(modifier = Modifier.fillMaxSize()) {
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.isScrollInProgress }
                .collect { scrolling ->
                    if (!scrolling) {
                        if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset > 140 && !isRefreshing) {
                            refreshActiveTab()
                        }
                    }
                }
        }
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.firstVisibleItemScrollOffset }
                .collect { offset ->
                    if (gridState.isScrollInProgress && gridState.firstVisibleItemIndex == 0) {
                        pullDistance = offset.toFloat().coerceAtMost(200f)
                    } else if (gridState.firstVisibleItemIndex > 0 || offset <= 0) {
                        pullDistance = 0f
                    }
                }
        }

        if (isRefreshing) {
            Surface(
                color = currentTheme.cardColor().copy(alpha = 0.96f),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f)
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = tabAccent,
                        strokeWidth = 2.dp
                    )
                    Text(
                        "Refreshing",
                        color = currentTheme.textColor(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        when {
                activeTab.videoCategory() != null -> {
                    if (isLoadingVideo && searchQuery.isEmpty()) {
                        LoadingShimmerGrid(currentTheme)
                    } else if (displayItems.isEmpty() && !isSearching) {
                        EmptyStateView(currentTheme = currentTheme, tab = activeTab, hasSearch = searchQuery.isNotEmpty())
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(mediaCols),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(displayItems, key = { it.id }) { item ->
                                ContentCard(
                                    item = item,
                                    currentTheme = currentTheme,
                                    compact = false,
                                    onClick = { onNovelSelected(item) }
                                )
                            }
                        }
                    }
                }
                isLoadingPopular && searchQuery.isEmpty() -> LoadingShimmerGrid(currentTheme)
                displayItems.isEmpty() && !isSearching -> EmptyStateView(
                    currentTheme = currentTheme,
                    tab = activeTab,
                    hasSearch = searchQuery.isNotEmpty()
                )
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(
                            when (activeTab) {
                                ContentTab.MANGA, ContentTab.COMIC -> mangaCols
                                else -> novelCols
                            }
                        ),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayItems, key = { it.id }) { item ->
                            ContentCard(
                                item = item,
                                currentTheme = currentTheme,
                                compact = activeTab == ContentTab.MANGA || activeTab == ContentTab.COMIC,
                                onClick = { onNovelSelected(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Anime Card (unchanged, omitted for brevity - kept below)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeCard(
    anime: AnimeResult,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    val countdown by produceState(initialValue = "") {
        while (true) {
            if (anime.nextAiringAt > 0L) {
                val nowSec = currentTimeMillis() / 1000L
                val diff = anime.nextAiringAt - nowSec
                value = if (diff > 0) {
                    val h = diff / 3600
                    val m = (diff % 3600) / 60
                    if (h > 0) "EP${anime.nextEpisode} in ${h}h ${m}m" else "EP${anime.nextEpisode} in ${m}m"
                } else "EP${anime.nextEpisode} airing soon"
            }
            kotlinx.coroutines.delay(60_000L)
        }
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
            ) {
                AsyncImage(
                    model = anime.coverUrl,
                    contentDescription = anime.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(0.75f)),
                                startY = 0.55f
                            )
                        )
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = animeAccent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text(
                        "A",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                if (anime.status == "RELEASING") {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF4CAF50)
                        ) {
                            Text(
                                "● LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (countdown.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(0.65f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    ) {
                        Text(
                            countdown,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFCC00),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    anime.displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (anime.genres.isNotEmpty()) {
                    Text(
                        anime.genres.take(2).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.subTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Unified Content Card
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentCard(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    compact: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (compact) 0.7f else 0.72f)
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
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when {
                        item.isAnime -> animeAccent
                        item.isManga -> Color(0xFFE91E8C)
                        item.isVideo -> when (item.mediaKind) {
                            VideoCategory.ANIME.name -> animeAccent
                            VideoCategory.DONGHUA.name -> donghuaAccent
                            VideoCategory.K_DRAMA.name -> kDramaAccent
                            VideoCategory.CARTOON.name -> cartoonAccent
                            VideoCategory.MOVIES.name -> moviesAccent
                            VideoCategory.NIGERIAN.name -> nigerianAccent
                            else -> moviesAccent
                        }
                        else -> currentTheme.accentColor()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text(
                        when {
                            item.isAnime -> "A"
                            item.isManga -> "M"
                            item.isVideo -> when (item.mediaKind) {
                                VideoCategory.DONGHUA.name -> "D"
                                VideoCategory.K_DRAMA.name -> "K"
                                VideoCategory.CARTOON.name -> "C"
                                VideoCategory.MOVIES.name -> "V"
                                VideoCategory.NIGERIAN.name -> "N"
                                else -> "V"
                            }
                            else -> "N"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(0.6f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                ) {
                    Text(
                        item.sourceName.take(8),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.9f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                if (!compact) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 6.dp, end = 6.dp, bottom = 30.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    )
                }
            }

            if (!compact) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.author.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = currentTheme.subTextColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = currentTheme.textColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ── Empty State View
@Composable
fun EmptyStateView(currentTheme: AppTheme, tab: ContentTab, hasSearch: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                when {
                    hasSearch -> Icons.Default.SearchOff
                    tab == ContentTab.MANGA -> Icons.Default.Collections
                    tab == ContentTab.ANIME -> Icons.Default.PlayCircle
                    tab == ContentTab.DONGHUA -> Icons.Default.Language
                    tab == ContentTab.K_DRAMA -> Icons.Default.LiveTv
                    tab == ContentTab.CARTOON -> Icons.Default.Animation
                    tab == ContentTab.MOVIES -> Icons.Default.Movie
                    tab == ContentTab.NIGERIAN_FILMS -> Icons.Default.Flag
                    else -> Icons.Default.AutoStories
                },
                null,
                tint = currentTheme.subTextColor(),
                modifier = Modifier.size(64.dp)
            )
            Text(
                if (hasSearch) "No results found"
                else when (tab) {
                    ContentTab.MANGA -> "No manga loaded yet"
                    ContentTab.COMIC -> "No comics loaded yet"
                    ContentTab.NOVELS -> "No novels loaded yet"
                    ContentTab.ANIME -> "Loading anime schedule..."
                    ContentTab.DONGHUA -> "No donghua loaded yet"
                    ContentTab.K_DRAMA -> "No K-drama loaded yet"
                    ContentTab.CARTOON -> "No cartoons loaded yet"
                    ContentTab.OLDER_CARTOON -> "No classic cartoons loaded yet"
                    ContentTab.MOVIES -> "No movies loaded yet"
                    ContentTab.NIGERIAN_FILMS -> "No Nollywood films loaded yet"
                },
                style = MaterialTheme.typography.titleMedium,
                color = currentTheme.subTextColor()
            )
            Text(
                if (hasSearch) "Try a different search term or switch tabs"
                else "Pull down to refresh",
                style = MaterialTheme.typography.bodySmall,
                color = currentTheme.subTextColor().copy(0.6f)
            )
        }
    }
}

// ── Shimmer Loading Grid
@Composable
fun LoadingShimmerGrid(currentTheme: AppTheme) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(8) {
            Column {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)).background(currentTheme.cardColor().copy(shimmerAlpha)))
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(currentTheme.cardColor().copy(shimmerAlpha)))
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(currentTheme.cardColor().copy(shimmerAlpha * 0.7f)))
            }
        }
    }
}
