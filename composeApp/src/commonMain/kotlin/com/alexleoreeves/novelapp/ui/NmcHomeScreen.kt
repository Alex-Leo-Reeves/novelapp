package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle

// ─────────────────────────────────────────────────────────────────────────────
//  NMC Home Screen — Unified Novels, Manga, Comics in one glass feed
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun NmcHomeScreen(
    currentTheme: AppTheme,
    repository: NovelSearchRepository,
    downloadRepo: LocalDownloadRepository,
    favorites: List<FavoriteNovel>,
    onNovelSelected: (UnifiedSearchResult) -> Unit,
    onChapterSelected: (Chapter) -> Unit,
    onToggleFavorite: (FavoriteNovel) -> Unit,
    onSearchHistorySaved: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // Popular content — all three sources
    var novelItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var mangaItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var comicItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

    var novelSearchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var mangaSearchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var comicSearchResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

    var isLoadingNovels by remember { mutableStateOf(false) }
    var isLoadingManga by remember { mutableStateOf(false) }
    var isLoadingComics by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // ── Load popular content ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (novelItems.isEmpty()) {
            isLoadingNovels = true
            try { novelItems = repository.fetchPopularNovels() }
            catch (_: Exception) {}
            isLoadingNovels = false
        }
        if (mangaItems.isEmpty()) {
            isLoadingManga = true
            try { mangaItems = repository.fetchPopularManga() }
            catch (_: Exception) {}
            isLoadingManga = false
        }
        if (comicItems.isEmpty()) {
            isLoadingComics = true
            try { comicItems = repository.fetchPopularComics() }
            catch (_: Exception) {}
            isLoadingComics = false
        }
    }

    // ── Debounced multi-source search ─────────────────────────────────────
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            isSearching = false
            novelSearchResults = emptyList()
            mangaSearchResults = emptyList()
            comicSearchResults = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        delay(400)
        val q = searchQuery
        // Fan out to all three sources in parallel
        try { novelSearchResults = repository.searchNovels(q) } catch (_: Exception) {}
        try { mangaSearchResults = repository.searchManga(q) } catch (_: Exception) {}
        try { comicSearchResults = repository.searchComics(q) } catch (_: Exception) {}
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
                        if (it.length >= 2) onSearchHistorySaved("NMC", it)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = Color.White,
                        fontSize = 15.sp
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search novels, manga, comics...",
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
                // Search results — all three sections
                if (novelSearchResults.isNotEmpty()) {
                    item { GlassSectionLabel("Novels — ${novelSearchResults.size} results") }
                    items(novelSearchResults) { item ->
                        NmcCardItem(
                            item = item,
                            isFavorite = favorites.any { it.id == item.id },
                            onClick = { onNovelSelected(item) },
                            onToggleFav = {
                                onToggleFavorite(
                                    FavoriteNovel(
                                        id = item.id,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                        detailPageUrl = item.detailPageUrl,
                                        sourceName = item.sourceName,
                                        author = item.author,
                                        genre = item.genre
                                    )
                                )
                            }
                        )
                    }
                }
                if (mangaSearchResults.isNotEmpty()) {
                    item { GlassSectionLabel("Manga — ${mangaSearchResults.size} results") }
                    items(mangaSearchResults) { item ->
                        NmcCardItem(
                            item = item,
                            isFavorite = favorites.any { it.id == item.id },
                            onClick = { onNovelSelected(item) },
                            onToggleFav = {
                                onToggleFavorite(
                                    FavoriteNovel(
                                        id = item.id,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                        detailPageUrl = item.detailPageUrl,
                                        sourceName = item.sourceName,
                                        author = item.author,
                                        genre = item.genre
                                    )
                                )
                            }
                        )
                    }
                }
                if (comicSearchResults.isNotEmpty()) {
                    item { GlassSectionLabel("Comics — ${comicSearchResults.size} results") }
                    items(comicSearchResults) { item ->
                        NmcCardItem(
                            item = item,
                            isFavorite = favorites.any { it.id == item.id },
                            onClick = { onNovelSelected(item) },
                            onToggleFav = {
                                onToggleFavorite(
                                    FavoriteNovel(
                                        id = item.id,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                        detailPageUrl = item.detailPageUrl,
                                        sourceName = item.sourceName,
                                        author = item.author,
                                        genre = item.genre
                                    )
                                )
                            }
                        )
                    }
                }
                if (novelSearchResults.isEmpty() && mangaSearchResults.isEmpty() && comicSearchResults.isEmpty()) {
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
                // Browse feed — sections
                item { GlassSectionLabel("Novels") }
                if (isLoadingNovels) {
                    item { LoadingShimmer() }
                } else {
                    items(novelItems.take(6)) { item ->
                        NmcCardItem(
                            item = item,
                            isFavorite = favorites.any { it.id == item.id },
                            onClick = { onNovelSelected(item) },
                            onToggleFav = {
                                onToggleFavorite(
                                    FavoriteNovel(
                                        id = item.id,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                        detailPageUrl = item.detailPageUrl,
                                        sourceName = item.sourceName,
                                        author = item.author,
                                        genre = item.genre
                                    )
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Manga") }
                if (isLoadingManga) {
                    item { LoadingShimmer() }
                } else {
                    items(mangaItems.take(6)) { item ->
                        NmcCardItem(
                            item = item,
                            isFavorite = favorites.any { it.id == item.id },
                            onClick = { onNovelSelected(item) },
                            onToggleFav = {
                                onToggleFavorite(
                                    FavoriteNovel(
                                        id = item.id,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                        detailPageUrl = item.detailPageUrl,
                                        sourceName = item.sourceName,
                                        author = item.author,
                                        genre = item.genre
                                    )
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Comics") }
                if (isLoadingComics) {
                    item { LoadingShimmer() }
                } else {
                    items(comicItems.take(6)) { item ->
                        NmcCardItem(
                            item = item,
                            isFavorite = favorites.any { it.id == item.id },
                            onClick = { onNovelSelected(item) },
                            onToggleFav = {
                                onToggleFavorite(
                                    FavoriteNovel(
                                        id = item.id,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                        detailPageUrl = item.detailPageUrl,
                                        sourceName = item.sourceName,
                                        author = item.author,
                                        genre = item.genre
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  NMC Card Item — glass card with Coil image
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun NmcCardItem(
    item: UnifiedSearchResult,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFav: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        GlassCard(
            onClick = onClick,
            contentPadding = PaddingValues(0.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Left: cover image
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(140.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, bottomEnd = 16.dp, topEnd = 16.dp))
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
                            aspectRatio = 100f / 140f
                        )
                    }
                }

                // Right: text content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
                        if (item.sourceName.isNotBlank()) {
                            GlassGenreChip(text = item.sourceName)
                        }
                        if (item.isManga) {
                            GlassGenreChip(text = "Manga")
                        }
                        if (item.isComic) {
                            GlassGenreChip(text = "Comic")
                        }
                    }
                    if (item.author.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "by ${item.author}",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.genre.ifBlank { "" },
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        IconButton(
                            onClick = onToggleFav,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFavorite) NeonMagenta else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingShimmer() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 8.dp)
            .clip(GlassCardShape)
            .background(GlassShimmerColor)
    )
}
