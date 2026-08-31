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

    // Section data — loaded lazily per category
    var recommendedItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoadingRecommended by remember { mutableStateOf(false) }
    var animeItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var movieItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var nollywoodItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var kdramaItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var cartoonItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var classicItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var donghuaItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    
    // Mixed media additions
    var popularNovelItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var popularMangaItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var popularComicItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

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
    var isLoadingNovels by remember { mutableStateOf(false) }
    var isLoadingManga by remember { mutableStateOf(false) }
    var isLoadingComics by remember { mutableStateOf(false) }

    // ── Infinite scrolling state (per-section rows) ───────────────────────
    var animePage by remember { mutableStateOf(2) }
    var moviePage by remember { mutableStateOf(2) }
    var nollywoodPage by remember { mutableStateOf(2) }
    var kdramaPage by remember { mutableStateOf(2) }
    var cartoonPage by remember { mutableStateOf(2) }
    var classicPage by remember { mutableStateOf(2) }
    var donghuaPage by remember { mutableStateOf(2) }
    var novelPage by remember { mutableStateOf(2) }
    var mangaPage by remember { mutableStateOf(2) }
    var comicPage by remember { mutableStateOf(2) }

    var isLoadingMoreAnime by remember { mutableStateOf(false) }
    var isLoadingMoreMovies by remember { mutableStateOf(false) }
    var isLoadingMoreNollywood by remember { mutableStateOf(false) }
    var isLoadingMoreKDrama by remember { mutableStateOf(false) }
    var isLoadingMoreCartoon by remember { mutableStateOf(false) }
    var isLoadingMoreClassic by remember { mutableStateOf(false) }
    var isLoadingMoreDonghua by remember { mutableStateOf(false) }
    var isLoadingMoreNovels by remember { mutableStateOf(false) }
    var isLoadingMoreManga by remember { mutableStateOf(false) }
    var isLoadingMoreComics by remember { mutableStateOf(false) }

    var hasMoreAnime by remember { mutableStateOf(true) }
    var hasMoreMovies by remember { mutableStateOf(true) }
    var hasMoreNollywood by remember { mutableStateOf(true) }
    var hasMoreKDrama by remember { mutableStateOf(true) }
    var hasMoreCartoon by remember { mutableStateOf(true) }
    var hasMoreClassic by remember { mutableStateOf(true) }
    var hasMoreDonghua by remember { mutableStateOf(true) }
    var hasMoreNovels by remember { mutableStateOf(true) }
    var hasMoreManga by remember { mutableStateOf(true) }
    var hasMoreComics by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    // ── Load-more helpers (append next page on row end) ──────────────────
    fun loadMoreSection(
        category: VideoCategory,
        page: Int,
        current: List<UnifiedSearchResult>,
        setter: (List<UnifiedSearchResult>) -> Unit,
        setPage: (Int) -> Unit,
        isLoading: Boolean,
        setLoading: (Boolean) -> Unit,
        hasMore: Boolean,
        setHasMore: (Boolean) -> Unit
    ) {
        if (isLoading || !hasMore) return
        setLoading(true)
        scope.launch {
            try {
                val repo = com.alexleoreeves.novelapp.data.NovelSearchRepository(
                    rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
                    rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
                )
                val more = repo.fetchVideo(category, page)
                if (more.isNotEmpty()) {
                    setter((current + more).distinctBy { it.id })
                    setPage(page + 1)
                } else {
                    setHasMore(false)
                }
            } catch (_: Exception) {
            } finally {
                setLoading(false)
            }
        }
    }

    fun loadMoreMixed(
        fetch: suspend (Int) -> List<UnifiedSearchResult>,
        page: Int,
        current: List<UnifiedSearchResult>,
        setter: (List<UnifiedSearchResult>) -> Unit,
        setPage: (Int) -> Unit,
        isLoading: Boolean,
        setLoading: (Boolean) -> Unit,
        hasMore: Boolean,
        setHasMore: (Boolean) -> Unit
    ) {
        if (isLoading || !hasMore) return
        setLoading(true)
        scope.launch {
            try {
                val more = fetch(page)
                if (more.isNotEmpty()) {
                    setter((current + more).distinctBy { it.id })
                    setPage(page + 1)
                } else {
                    setHasMore(false)
                }
            } catch (_: Exception) {
            } finally {
                setLoading(false)
            }
        }
    }

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
        if (recommendedItems.isEmpty()) {
            scope.launch {
                isLoadingRecommended = true
                try {
                    val tmdb = com.alexleoreeves.novelapp.data.TmdbSource(
                        client = io.ktor.client.HttpClient(),
                        readAccessToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN,
                        apiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY
                    )
                    val aniList = com.alexleoreeves.novelapp.data.AniListSource(io.ktor.client.HttpClient())
                    val engine = com.alexleoreeves.novelapp.data.RecommendationEngine(tmdb, aniList, downloadRepo)
                    val recs = engine.getRecommendations()
                    recommendedItems = if (recs.isNotEmpty()) recs else {
                        val movies = tmdb.fetchVideo(VideoCategory.MOVIES, 1).take(15)
                        val anime = aniList.fetchTrending(1).take(15)
                        (movies + anime).shuffled()
                    }
                } catch (_: Exception) {
                    try {
                        val tmdb = com.alexleoreeves.novelapp.data.TmdbSource(
                            client = io.ktor.client.HttpClient(),
                            readAccessToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN,
                            apiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY
                        )
                        val aniList = com.alexleoreeves.novelapp.data.AniListSource(io.ktor.client.HttpClient())
                        val movies = tmdb.fetchVideo(VideoCategory.MOVIES, 1).take(15)
                        val anime = aniList.fetchTrending(1).take(15)
                        recommendedItems = (movies + anime).shuffled()
                    } catch (_: Exception) {}
                }
                isLoadingRecommended = false
            }
        }
        if (animeItems.isEmpty()) loadSection(VideoCategory.ANIME, { animeItems = it }, { isLoadingAnime = it })
        if (movieItems.isEmpty()) loadSection(VideoCategory.MOVIES, { movieItems = it }, { isLoadingMovies = it })
        if (nollywoodItems.isEmpty()) loadSection(VideoCategory.NIGERIAN, { nollywoodItems = it }, { isLoadingNollywood = it })
        if (kdramaItems.isEmpty()) loadSection(VideoCategory.K_DRAMA, { kdramaItems = it }, { isLoadingKDrama = it })
        if (cartoonItems.isEmpty()) loadSection(VideoCategory.CARTOON, { cartoonItems = it }, { isLoadingCartoon = it })
        if (classicItems.isEmpty()) loadSection(VideoCategory.CLASSIC, { classicItems = it }, { isLoadingClassic = it })
        if (donghuaItems.isEmpty()) loadSection(VideoCategory.DONGHUA, { donghuaItems = it }, { isLoadingDonghua = it })
        
        // Load mixed media
        scope.launch {
            val repo = com.alexleoreeves.novelapp.data.NovelSearchRepository(
                rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
                rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
            )
            if (popularNovelItems.isEmpty()) {
                isLoadingNovels = true
                try { popularNovelItems = repo.fetchPopularNovels() } catch(_: Exception) {}
                isLoadingNovels = false
            }
            if (popularMangaItems.isEmpty()) {
                isLoadingManga = true
                try { popularMangaItems = repo.fetchPopularManga() } catch(_: Exception) {}
                isLoadingManga = false
            }
            if (popularComicItems.isEmpty()) {
                isLoadingComics = true
                try { popularComicItems = repo.fetchPopularComics() } catch(_: Exception) {}
                isLoadingComics = false
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
                // Browse feed — Recommended section is the very FIRST section
                if (recommendedItems.isNotEmpty() || isLoadingRecommended) {
                    item { GlassSectionLabel("Recommended For You", modifier = Modifier.padding(horizontal = 16.dp)) }
                    if (isLoadingRecommended) {
                        item { SectionShimmerHorizontal() }
                    } else {
                        item {
                            DiscoverPosterRow(
                                items = recommendedItems,
                                isLoadingMore = false,
                                onItemClick = onNovelSelected,
                                onLoadMore = {}
                            )
                        }
                    }
                }

                item { GlassSectionLabel("Anime", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingAnime) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = animeItems,
                            isLoadingMore = isLoadingMoreAnime,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.ANIME,
                                    page = animePage,
                                    current = animeItems,
                                    setter = { animeItems = it },
                                    setPage = { animePage = it },
                                    isLoading = isLoadingMoreAnime,
                                    setLoading = { isLoadingMoreAnime = it },
                                    hasMore = hasMoreAnime,
                                    setHasMore = { hasMoreAnime = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Trending Novels", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingNovels) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = popularNovelItems,
                            isLoadingMore = isLoadingMoreNovels,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreMixed(
                                    fetch = { page ->
                                        com.alexleoreeves.novelapp.data.NovelSearchRepository(
                                            rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
                                            rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
                                        ).fetchPopularNovels(page)
                                    },
                                    page = novelPage,
                                    current = popularNovelItems,
                                    setter = { popularNovelItems = it },
                                    setPage = { novelPage = it },
                                    isLoading = isLoadingMoreNovels,
                                    setLoading = { isLoadingMoreNovels = it },
                                    hasMore = hasMoreNovels,
                                    setHasMore = { hasMoreNovels = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Movies", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingMovies) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = movieItems,
                            isLoadingMore = isLoadingMoreMovies,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.MOVIES,
                                    page = moviePage,
                                    current = movieItems,
                                    setter = { movieItems = it },
                                    setPage = { moviePage = it },
                                    isLoading = isLoadingMoreMovies,
                                    setLoading = { isLoadingMoreMovies = it },
                                    hasMore = hasMoreMovies,
                                    setHasMore = { hasMoreMovies = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Nollywood", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingNollywood) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = nollywoodItems,
                            isLoadingMore = isLoadingMoreNollywood,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.NIGERIAN,
                                    page = nollywoodPage,
                                    current = nollywoodItems,
                                    setter = { nollywoodItems = it },
                                    setPage = { nollywoodPage = it },
                                    isLoading = isLoadingMoreNollywood,
                                    setLoading = { isLoadingMoreNollywood = it },
                                    hasMore = hasMoreNollywood,
                                    setHasMore = { hasMoreNollywood = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Popular Manga", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingManga) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = popularMangaItems,
                            isLoadingMore = isLoadingMoreManga,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreMixed(
                                    fetch = { page ->
                                        com.alexleoreeves.novelapp.data.NovelSearchRepository(
                                            rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
                                            rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
                                        ).fetchPopularManga(page)
                                    },
                                    page = mangaPage,
                                    current = popularMangaItems,
                                    setter = { popularMangaItems = it },
                                    setPage = { mangaPage = it },
                                    isLoading = isLoadingMoreManga,
                                    setLoading = { isLoadingMoreManga = it },
                                    hasMore = hasMoreManga,
                                    setHasMore = { hasMoreManga = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("K-Drama", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingKDrama) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = kdramaItems,
                            isLoadingMore = isLoadingMoreKDrama,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.K_DRAMA,
                                    page = kdramaPage,
                                    current = kdramaItems,
                                    setter = { kdramaItems = it },
                                    setPage = { kdramaPage = it },
                                    isLoading = isLoadingMoreKDrama,
                                    setLoading = { isLoadingMoreKDrama = it },
                                    hasMore = hasMoreKDrama,
                                    setHasMore = { hasMoreKDrama = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Cartoon", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingCartoon) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = cartoonItems,
                            isLoadingMore = isLoadingMoreCartoon,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.CARTOON,
                                    page = cartoonPage,
                                    current = cartoonItems,
                                    setter = { cartoonItems = it },
                                    setPage = { cartoonPage = it },
                                    isLoading = isLoadingMoreCartoon,
                                    setLoading = { isLoadingMoreCartoon = it },
                                    hasMore = hasMoreCartoon,
                                    setHasMore = { hasMoreCartoon = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Popular Comics", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingComics) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = popularComicItems,
                            isLoadingMore = isLoadingMoreComics,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreMixed(
                                    fetch = { page ->
                                        com.alexleoreeves.novelapp.data.NovelSearchRepository(
                                            rapidApiKey = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_KEY,
                                            rapidApiHost = com.alexleoreeves.novelapp.BuildKonfig.RAPID_API_HOST
                                        ).fetchPopularComics(page)
                                    },
                                    page = comicPage,
                                    current = popularComicItems,
                                    setter = { popularComicItems = it },
                                    setPage = { comicPage = it },
                                    isLoading = isLoadingMoreComics,
                                    setLoading = { isLoadingMoreComics = it },
                                    hasMore = hasMoreComics,
                                    setHasMore = { hasMoreComics = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Classic", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingClassic) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = classicItems,
                            isLoadingMore = isLoadingMoreClassic,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.CLASSIC,
                                    page = classicPage,
                                    current = classicItems,
                                    setter = { classicItems = it },
                                    setPage = { classicPage = it },
                                    isLoading = isLoadingMoreClassic,
                                    setLoading = { isLoadingMoreClassic = it },
                                    hasMore = hasMoreClassic,
                                    setHasMore = { hasMoreClassic = it }
                                )
                            }
                        )
                    }
                }

                item { GlassSectionLabel("Donghua", modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
                if (isLoadingDonghua) {
                    item { SectionShimmerHorizontal() }
                } else {
                    item {
                        DiscoverPosterRow(
                            items = donghuaItems,
                            isLoadingMore = isLoadingMoreDonghua,
                            onItemClick = onNovelSelected,
                            onLoadMore = {
                                loadMoreSection(
                                    category = VideoCategory.DONGHUA,
                                    page = donghuaPage,
                                    current = donghuaItems,
                                    setter = { donghuaItems = it },
                                    setPage = { donghuaPage = it },
                                    isLoading = isLoadingMoreDonghua,
                                    setLoading = { isLoadingMoreDonghua = it },
                                    hasMore = hasMoreDonghua,
                                    setHasMore = { hasMoreDonghua = it }
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
