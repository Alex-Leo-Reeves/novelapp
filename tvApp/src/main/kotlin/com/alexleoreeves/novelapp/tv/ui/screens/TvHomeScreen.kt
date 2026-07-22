package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.components.TvSearchKeyboard
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun TvHomeScreen(
    section: TvSection,
    account: SavedUserAccount?,
    onMediaSelected: (UnifiedSearchResult) -> Unit,
    onSearch: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var suggestions by remember { mutableStateOf(listOf("One Piece", "Attack on Titan", "Solo Leveling", "Demon Slayer")) }
    val scope = rememberCoroutineScope()

    fun loadContent() {
        scope.launch {
            isLoading = true
            items = when (section) {
                TvSection.HOME -> emptyList()
                TvSection.ANIME -> fetchContentHome("anime")
                TvSection.MANGA -> fetchContentHome("manga")
                TvSection.COMICS -> fetchContentHome("comic")
                TvSection.NOVELS -> fetchContentHome("novel")
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

    LaunchedEffect(section) { loadContent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    section.label,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            TvSearchScreen(
                initialQuery = searchQuery,
                onSearch = { query ->
                    searchQuery = query
                    scope.launch {
                        isLoading = true
                        items = searchContent(
                            when (section) {
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
                                else -> "anime"
                            },
                            query
                        )
                        isLoading = false
                    }
                    showSearch = false
                },
                onClose = { showSearch = false }
            )
        }

        // Content grid
        when {
            section == TvSection.HOME -> TvHomeFeed(account, onMediaSelected)
            section == TvSection.SPORTS -> TvSportsScreen(account, onBack = {})
            section == TvSection.DOWNLOADS -> TvDownloadsScreen(account)
            section == TvSection.YOU -> TvYouScreen(account, onSignOut = {}, onBack = {})
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

@Composable
private fun TvHomeFeed(
    account: SavedUserAccount?,
    onMediaSelected: (UnifiedSearchResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var trendingAnime by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var trendingNovels by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var trendingManga by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var trendingMovies by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        trendingAnime = fetchContentHome("anime")
        trendingNovels = fetchContentHome("novel")
        trendingManga = fetchContentHome("manga")
        trendingMovies = fetchContentHome("movie")
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // Greeting
        Text(
            "Welcome${if (account != null) ", ${account.username}" else ""}",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
        Text(
            "Discover anime, novels, manga, movies & more",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(0.6f)
        )

        if (isLoading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Purple500)
            }
        } else {
            ContentRow("🔥 Trending Anime", trendingAnime, onMediaSelected)
            ContentRow("📚 Popular Novels", trendingNovels, onMediaSelected)
            ContentRow("🎨 Top Manga", trendingManga, onMediaSelected)
            ContentRow("🎬 New Movies", trendingMovies, onMediaSelected)
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
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

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
        interactionSource = interactionSource,
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
