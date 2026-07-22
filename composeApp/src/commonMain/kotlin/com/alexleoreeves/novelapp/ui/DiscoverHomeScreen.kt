package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

// ─────────────────────────────────────────────────────────────────────────────
//  Discover Home Screen — Unified video feed with glass cards.
//  Shows Anime, Movies, Nollywood, K-Drama, Cartoon, Classic, Nigerian, Donghua
//  — all in one scrollable feed with labeled sections. No sub-tabs.
//  Search fans out to TMDB + YouTube in parallel.
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverHomeScreen(
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    onNovelSelected: (UnifiedSearchResult) -> Unit,
    onSearchHistorySaved: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // Section data — loaded lazily per category
    var animeItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var movieItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var nollywoodItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var kdramaItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var cartoonItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var classicItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var donghuaItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

    // TMDB search merged results
    var searchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var nollywoodSearchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

    var isLoadingAnime by remember { mutableStateOf(false) }
    var isLoadingMovies by remember { mutableStateOf(false) }
    var isLoadingNollywood by remember { mutableStateOf(false) }
    var isLoadingKDrama by remember { mutableStateOf(false) }
    var isLoadingCartoon by remember { mutableStateOf(false) }
    var isLoadingClassic by remember { mutableStateOf(false) }
    var isLoadingDonghua by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // ── Load all sections on mount ────────────────────────────────────────
    fun loadSection(category: VideoCategory, setter: (List<UnifiedSearchResult>) -> Unit, loadingSetter: (Boolean) -> Unit) {
        scope.launch {
            loadingSetter(true)
            try {
                val repo = com.alexleoreeves.novelapp.data.NovelSearchRepository(
                    rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
                    rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
                )
                setter(repo.fetchVideo(category))
            } catch (_: Exception) {}
            loadingSetter(false)
        }
    }

    LaunchedEffect(Unit) {
        if (animeItems.isEmpty()) loadSection(VideoCategory.ANIME, { animeItems = it }, { isLoadingAnime = it })
        if (movieItems.isEmpty()) loadSection(VideoCategory.MOVIES, { movieItems = it }, { isLoadingMovies = it })
        if (nollywoodItems.isEmpty()) loadSection(VideoCategory.NIGERIAN, { nollywoodItems = it }, { isLoadingNollywood = it })
        if (kdramaItems.isEmpty()) loadSection(VideoCategory.K_DRAMA, { kdramaItems = it }, { isLoadingKDrama = it })
        if (cartoonItems.isEmpty()) loadSection(VideoCategory.CARTOON, { cartoonItems = it }, { isLoadingCartoon = it })
        if (classicItems.isEmpty()) loadSection(VideoCategory.CLASSIC, { classicItems = it }, { isLoadingClassic = it })
        if (donghuaItems.isEmpty()) loadSection(VideoCategory.DONGHUA, { donghuaItems = it }, { isLoadingDonghua = it })
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
        val merged = mutableListOf<UnifiedSearchResult>()
        try { searchResults = repo.searchVideo(VideoCategory.MOVIES, q) } catch (_: Exception) { searchResults = emptyList() }
        try { nollywoodSearchResults = repo.searchVideo(VideoCategory.NIGERIAN, q) } catch (_: Exception) { nollywoodSearchResults = emptyList() }
        isSearching = false
    }

    val isSearchActive = searchQuery.length >= 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassOverlayColor)
            .statusBarsPadding()
    ) {
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
                        color = NeonMagenta
                    )
                } else {
                    Icon(
                        Icons.Default.Search,
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
                            Icons.Default.Close,
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (isSearchActive) {
                // Search results — TMDB
                if (searchResults.isNotEmpty()) {
                    item { GlassSectionLabel("Movies & Shows — ${searchResults.size} results") }
                    items(searchResults) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }
                // Search results — Nollywood/YouTube
                if (nollywoodSearchResults.isNotEmpty()) {
                    item {
                        GlassSectionLabel(
                            "Nollywood — ${nollywoodSearchResults.size} results",
                            modifier = if (searchResults.isEmpty()) Modifier else Modifier.padding(top = 8.dp)
                        )
                    }
                    items(nollywoodSearchResults) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
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
                // Browse feed — labeled sections
                item { GlassSectionLabel("Anime") }
                if (isLoadingAnime) {
                    item { SectionShimmer() }
                } else {
                    items(animeItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }

                item { GlassSectionLabel("Movies") }
                if (isLoadingMovies) {
                    item { SectionShimmer() }
                } else {
                    items(movieItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }

                item { GlassSectionLabel("Nollywood") }
                if (isLoadingNollywood) {
                    item { SectionShimmer() }
                } else {
                    items(nollywoodItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }

                item { GlassSectionLabel("K-Drama") }
                if (isLoadingKDrama) {
                    item { SectionShimmer() }
                } else {
                    items(kdramaItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }

                item { GlassSectionLabel("Cartoon") }
                if (isLoadingCartoon) {
                    item { SectionShimmer() }
                } else {
                    items(cartoonItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }

                item { GlassSectionLabel("Classic") }
                if (isLoadingClassic) {
                    item { SectionShimmer() }
                } else {
                    items(classicItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }

                item { GlassSectionLabel("Donghua") }
                if (isLoadingDonghua) {
                    item { SectionShimmer() }
                } else {
                    items(donghuaItems.take(5)) { item ->
                        VideoCardItem(item = item, onClick = { onNovelSelected(item) })
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Video Card Item — glass card with Coil 3 cover image
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
                            tint = NeonMagenta,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Watch",
                            color = NeonMagenta,
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
private fun SectionShimmer() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(vertical = 8.dp)
            .clip(GlassCardShape)
            .background(GlassShimmerColor)
    )
}
