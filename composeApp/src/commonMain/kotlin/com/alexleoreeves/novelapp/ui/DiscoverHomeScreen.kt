package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
//  Content type tab selector: Novels / Manga / Anime
// ─────────────────────────────────────────────────────────────────────────────
enum class ContentTab(val label: String, val icon: ImageVector) {
    NOVELS("Novels", Icons.Default.AutoStories),
    MANGA("Manga", Icons.Default.Collections),
    ANIME("Anime", Icons.Default.PlayCircle)
}

// Anime-specific accent color
private val animeAccent = Color(0xFFFF5722)

// ─────────────────────────────────────────────────────────────────────────────
//  Discover Home Screen: separated Novels / Manga / Anime dashboard
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun DiscoverHomeScreen(
    currentTheme: AppTheme,
    contentTab: ContentTab = ContentTab.NOVELS,
    isDesktop: Boolean = false,
    onNovelSelected: (UnifiedSearchResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val repository = remember {
        NovelSearchRepository(
            geminiApiKey = BuildKonfig.GEMINI_API_KEY,
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }

    // ── State ──────────────────────────────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(contentTab) }
    var selectedCategory by remember { mutableStateOf(NovelCategory.ALL) }
    var isSearching by remember { mutableStateOf(false) }

    var popularItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var airingAnime by remember { mutableStateOf<List<AnimeResult>>(emptyList()) }
    var animeSearchResults by remember { mutableStateOf<List<AnimeResult>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoadingPopular by remember { mutableStateOf(true) }
    var isLoadingAnime by remember { mutableStateOf(true) }

    val categories = NovelCategory.values().toList()

    LaunchedEffect(contentTab) {
        searchQuery = ""
        searchResults = emptyList()
        animeSearchResults = emptyList()
        isSearching = false
        selectedCategory = NovelCategory.ALL
    }

    // ── Initial popular content load ───────────────────────────────────────
    LaunchedEffect(Unit) {
        isLoadingPopular = true
        popularItems = repository.fetchPopularAll(page = 1)
        isLoadingPopular = false
    }

    // ── Anime tab content load ─────────────────────────────────────────────
    LaunchedEffect(activeTab) {
        if (activeTab == ContentTab.ANIME && airingAnime.isEmpty()) {
            isLoadingAnime = true
            airingAnime = repository.fetchCurrentlyAiring()
            isLoadingAnime = false
        }
    }

    // ── Filter popular content by active tab ───────────────────────────────
    val filteredPopular by remember(popularItems, activeTab, selectedCategory) {
        derivedStateOf {
            var list = when (activeTab) {
                ContentTab.NOVELS -> popularItems.filter { !it.isManga && !it.isAnime }
                ContentTab.MANGA -> popularItems.filter { it.isManga }
                ContentTab.ANIME -> popularItems.filter { it.isAnime }
            }
            if (activeTab == ContentTab.NOVELS && selectedCategory != NovelCategory.ALL) {
                list = list.filter {
                    it.genre.contains(selectedCategory.label, ignoreCase = true)
                }
            }
            list
        }
    }

    // ── Filter search results by active tab ────────────────────────────────
    val filteredSearch by remember(searchResults, activeTab) {
        derivedStateOf {
            when (activeTab) {
                ContentTab.NOVELS -> searchResults.filter { !it.isManga && !it.isAnime }
                ContentTab.MANGA -> searchResults.filter { it.isManga }
                ContentTab.ANIME -> searchResults.filter { it.isAnime }
            }
        }
    }

    // ── Debounced search ───────────────────────────────────────────────────
    LaunchedEffect(searchQuery, activeTab) {
        if (searchQuery.length < 2) {
            isSearching = false
            searchResults = emptyList()
            animeSearchResults = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(400)
        val q = searchQuery
        scope.launch {
            when (activeTab) {
                ContentTab.ANIME -> {
                    animeSearchResults = repository.searchAnime(q)
                    isSearching = false
                }
                else -> {
                    searchResults = when (activeTab) {
                        ContentTab.NOVELS -> repository.searchAll(q).filter { !it.isManga && !it.isAnime }
                        ContentTab.MANGA -> repository.searchAll(q).filter { it.isManga }
                        else -> emptyList()
                    }
                    isSearching = false
                }
            }
        }
    }

    val displayItems = if (searchQuery.length >= 2) filteredSearch else filteredPopular

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Header ─────────────────────────────────────────────────────────
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
                            when (activeTab) {
                                ContentTab.NOVELS -> "Light Novels from 5 sources"
                                ContentTab.MANGA -> "Manga from MangaDex, MangaSee & MangaFire"
                                ContentTab.ANIME -> "Currently airing · GogoAnime & Hianime streams"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                    Icon(
                        if (activeTab == ContentTab.ANIME) Icons.Default.PlayCircle
                        else Icons.Default.AutoAwesome,
                        null,
                        tint = if (activeTab == ContentTab.ANIME) animeAccent
                               else currentTheme.accentColor(),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (activeTab) {
                                ContentTab.NOVELS -> "Search light novels..."
                                ContentTab.MANGA -> "Search manga titles..."
                                ContentTab.ANIME -> "Search anime (AniList)..."
                            },
                            color = currentTheme.subTextColor()
                        )
                    },
                    leadingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = if (activeTab == ContentTab.ANIME) animeAccent
                                        else currentTheme.accentColor()
                            )
                        } else {
                            Icon(
                                Icons.Default.Search,
                                null,
                                tint = if (activeTab == ContentTab.ANIME) animeAccent
                                       else currentTheme.accentColor()
                            )
                        }
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null, tint = currentTheme.subTextColor())
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (activeTab == ContentTab.ANIME) animeAccent
                                            else currentTheme.accentColor(),
                        unfocusedBorderColor = currentTheme.subTextColor().copy(0.3f),
                        focusedTextColor = currentTheme.textColor(),
                        unfocusedTextColor = currentTheme.textColor(),
                        cursorColor = currentTheme.accentColor(),
                        unfocusedContainerColor = currentTheme.cardColor().copy(0.5f),
                        focusedContainerColor = currentTheme.cardColor().copy(0.5f)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.height(14.dp))

                // Premium Segmented Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(currentTheme.cardColor().copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ContentTab.values().forEach { tab ->
                        val selected = activeTab == tab
                        val tabColor = if (tab == ContentTab.ANIME) animeAccent else currentTheme.accentColor()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) tabColor else Color.Transparent)
                                .clickable { activeTab = tab }
                                .padding(vertical = 8.dp),
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

        val tabAccent = if (activeTab == ContentTab.ANIME) animeAccent else currentTheme.accentColor()

        // ── Genre / Filter Chips ───────────────────────────────────────────
        // Novel genre chips
        AnimatedVisibility(
            visible = activeTab == ContentTab.NOVELS,
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
            visible = activeTab == ContentTab.MANGA,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            var mangaCategory by remember { mutableStateOf("All") }
            val mangaGenres = listOf("All","Action","Romance","Fantasy","Horror","Comedy","Sports","Sci-Fi","Supernatural")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mangaGenres) { genre ->
                    FilterChip(
                        selected = mangaCategory == genre,
                        onClick = { mangaCategory = genre },
                        label = { Text(genre) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE91E8C),
                            selectedLabelColor = Color.White,
                            containerColor = currentTheme.cardColor(),
                            labelColor = currentTheme.subTextColor()
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = mangaCategory == genre,
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
            visible = activeTab == ContentTab.ANIME,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            var animeGenre by remember { mutableStateOf("All") }
            val animeGenres = listOf("All","Action","Romance","Fantasy","Horror","Comedy","Sports","Sci-Fi","Supernatural","Isekai","Shonen","Seinen")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(animeGenres) { genre ->
                    FilterChip(
                        selected = animeGenre == genre,
                        onClick = { animeGenre = genre },
                        label = { Text(genre) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = animeAccent,
                            selectedLabelColor = Color.White,
                            containerColor = currentTheme.cardColor(),
                            labelColor = currentTheme.subTextColor()
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = animeGenre == genre,
                            selectedBorderColor = animeAccent,
                            borderColor = currentTheme.subTextColor().copy(0.3f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        // ── Section Label ──────────────────────────────────────────────────
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
                val resultCount = if (activeTab == ContentTab.ANIME) animeSearchResults.size else filteredSearch.size
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
                        ContentTab.ANIME -> "Currently Airing"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Content Area ────────────────────────────────────────────────────
        val animeCols = if (isDesktop) 4 else 2
        val mangaCols = if (isDesktop) 5 else 3
        val novelCols = if (isDesktop) 4 else 2

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // Anime tab
                activeTab == ContentTab.ANIME -> {
                    val showAnime = if (searchQuery.length >= 2) animeSearchResults else airingAnime
                    if (isLoadingAnime && searchQuery.isEmpty()) {
                        LoadingShimmerGrid(currentTheme)
                    } else if (showAnime.isEmpty() && !isSearching) {
                        EmptyStateView(currentTheme = currentTheme, tab = activeTab, hasSearch = searchQuery.isNotEmpty())
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(animeCols),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(showAnime, key = { it.id }) { anime ->
                                AnimeCard(
                                    anime = anime,
                                    currentTheme = currentTheme,
                                    onClick = {
                                        onNovelSelected(
                                            UnifiedSearchResult(
                                                id = "anilist_${anime.id}",
                                                title = anime.displayTitle,
                                                author = "",
                                                coverUrl = anime.coverUrl,
                                                sourceName = "AniList",
                                                url = "anilist:${anime.id}",
                                                genre = anime.genres.joinToString(", "),
                                                synopsis = anime.synopsis,
                                                isManga = false,
                                                isAnime = true,
                                                animeResult = anime
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                // Novel / Manga / All tabs
                isLoadingPopular && searchQuery.isEmpty() -> LoadingShimmerGrid(currentTheme)
                displayItems.isEmpty() && !isSearching -> EmptyStateView(
                    currentTheme = currentTheme,
                    tab = activeTab,
                    hasSearch = searchQuery.isNotEmpty()
                )
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (activeTab == ContentTab.MANGA) mangaCols else novelCols),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayItems, key = { it.id }) { item ->
                            ContentCard(
                                item = item,
                                currentTheme = currentTheme,
                                compact = activeTab == ContentTab.MANGA,
                                onClick = { onNovelSelected(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Anime Card — with AIRING badge + countdown timer to next episode
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeCard(
    anime: AnimeResult,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    // Compute countdown from nextAiringAt (unix seconds)
    val countdown by produceState(initialValue = "") {
        while (true) {
            if (anime.nextAiringAt > 0L) {
                val nowSec = System.currentTimeMillis() / 1000L
                val diff = anime.nextAiringAt - nowSec
                value = if (diff > 0) {
                    val h = diff / 3600
                    val m = (diff % 3600) / 60
                    if (h > 0) "EP${anime.nextEpisode} in ${h}h ${m}m" else "EP${anime.nextEpisode} in ${m}m"
                } else "EP${anime.nextEpisode} airing soon"
            }
            kotlinx.coroutines.delay(60_000L) // update every minute
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
                // ANIME badge
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
                // AIRING status indicator
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
                // Countdown at bottom
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

            // Title below cover
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

// ─────────────────────────────────────────────────────────────────────────────
//  Unified Content Card
// ─────────────────────────────────────────────────────────────────────────────
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
                // Type badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when {
                        item.isAnime -> animeAccent
                        item.isManga -> Color(0xFFE91E8C)
                        else -> currentTheme.accentColor()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text(
                        when {
                            item.isAnime -> "A"
                            item.isManga -> "M"
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

// ─────────────────────────────────────────────────────────────────────────────
//  Empty State View
// ─────────────────────────────────────────────────────────────────────────────
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
                    ContentTab.NOVELS -> "No novels loaded yet"
                    ContentTab.ANIME -> "Loading anime schedule..."
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

// ─────────────────────────────────────────────────────────────────────────────
//  Shimmer Loading Grid
// ─────────────────────────────────────────────────────────────────────────────
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
