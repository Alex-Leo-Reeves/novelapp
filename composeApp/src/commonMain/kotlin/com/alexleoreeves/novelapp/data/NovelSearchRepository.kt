package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.currentTimeMillis
import com.alexleoreeves.novelapp.platform.platformHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

private val backendContentJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Aggregates search results from all registered sources in parallel.
 * Every source is treated as an equal — all fire simultaneously.
 * Individual source failures are isolated and never crash the whole search.
 */
class NovelSearchRepository(
    rapidApiKey: String,
    rapidApiHost: String
) {
    private val sourceSemaphore = Semaphore(3)
    private val feedCache = mutableMapOf<String, CacheEntry>()
    private data class CacheEntry(val data: List<UnifiedSearchResult>, val timestamp: Long)
    private fun isCacheFresh(key: String): Boolean {
        val entry = feedCache[key] ?: return false
        return (currentTimeMillis() - entry.timestamp) < 120_000L // 2 min TTL
    }

    val httpClient = platformHttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) {
            level = LogLevel.NONE
        }
        install(HttpRedirect) {
            checkHttpMethod = false
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        defaultRequest {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,application/json,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
        }
    }

    /** Novel sources */
    private val sources: List<NovelSource> = listOf(
        WebNovelApiSource(httpClient, rapidApiKey, rapidApiHost),
        FreeWebNovelSource(httpClient),
        LightNovelPubSource(httpClient),
        BoxNovelSource(httpClient),
        WuxiaWorldSource(httpClient),
        ReadNovelFullSource(httpClient),
        RoyalRoadSource(httpClient)
    )

    /** Manga sources */
    private val mangaSources: List<MangaScraper> = listOf(
        MangaDexSource(
            httpClient = httpClient,
            clientId = com.alexleoreeves.novelapp.BuildKonfig.MANGADEX_CLIENT_ID,
            clientSecret = com.alexleoreeves.novelapp.BuildKonfig.MANGADEX_CLIENT_SECRET,
            username = com.alexleoreeves.novelapp.BuildKonfig.MANGADEX_USERNAME,
            password = com.alexleoreeves.novelapp.BuildKonfig.MANGADEX_PASSWORD
        ),
        MangaFireScraper(httpClient),
        WebtoonScraper(httpClient),
        WeebCentralScraper(httpClient)
    )

    /** Comic sources — Western comics from ReadAllComics, ZipComic, BatCave, GetComics */
    private val comicSources: List<ComicSource> = listOf(
        GetComicsSource(httpClient),
        ZipComicScraper(httpClient),
        ReadAllComicsScraper(httpClient),
        BatCaveScraper(httpClient)
    )

    /** Anime sources */
    internal val aniListSource = AniListSource(httpClient)
    internal val aninekoScraper = AninekoScraper(httpClient)
    internal val animePaheScraper = AnimePaheScraper(httpClient)
    internal val consumetAnimeScraper = ConsumetAnimeScraper(httpClient)
    internal val tmdbSource = TmdbSource(
        client = httpClient,
        readAccessToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN,
        apiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY
    )
    private val dramaCoolScraper = DramaCoolScraper(httpClient)
    private val kimCartoonScraper = KimCartoonScraper(httpClient)
    private val wcoStreamScraper = WcoStreamScraper(httpClient)
    private val youtubeNollywoodScraper = YouTubeNollywoodScraper(httpClient)

    // ─────────────────────────────────────────────────────────────────────────
    //  Unified Search — Novels + Manga + Anime all simultaneously
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun searchAll(query: String): List<UnifiedSearchResult> = coroutineScope {
        val novelTasks = sources.map { source ->
            async {
                try { source.search(query) }
                catch (e: Exception) {
                    println("[Search] ${source.sourceName} failed silently: ${e.message}")
                    emptyList()
                }
            }
        }
        val mangaTasks = mangaSources.map { source ->
            async {
                try { source.searchManga(query) }
                catch (e: Exception) {
                    println("[Manga Search] ${source.sourceName} failed silently: ${e.message}")
                    emptyList()
                }
            }
        }
        val animeTask = async {
            try {
                aniListSource.search(query).map { anime ->
                    UnifiedSearchResult(
                        id = "anilist_${anime.id}",
                        title = anime.displayTitle,
                        author = "",
                        coverUrl = anime.coverUrl,
                        sourceName = "AniList",
                        url = "anilist:${anime.id}",
                        genre = anime.genres.take(3).joinToString(", "),
                        synopsis = anime.synopsis,
                        isManga = false,
                        isAnime = true,
                        animeResult = anime
                    )
                }
            } catch (e: Exception) {
                println("[Anime Search] AniList failed: ${e.message}")
                emptyList()
            }
        }

        val allNovels = (novelTasks.awaitAll().flatten() + knownNovelFallbacks(query))
            .balancedNovelResults(query)
        val allManga = mangaTasks.awaitAll().flatten()
        val allAnime = animeTask.await()

        (allNovels + allManga + allAnime)
            .filter { it.title.isNotBlank() }
            .distinctBy {
                "${it.sourceName}:${it.detailPageUrl.ifBlank { it.url }.ifBlank { it.title }}".lowercase().trim()
            }
    }

    suspend fun searchNovels(query: String): List<UnifiedSearchResult> = coroutineScope {
        val sourceResults = sources.map { source ->
            async {
                try {
                    val results = sourceSemaphore.withPermit {
                        withTimeoutOrNull(8_000) { source.search(query) }.orEmpty()
                    }
                    println("[Novel Search] ${source.sourceName}: ${results.size} result(s) for \"$query\"")
                    results
                }
                catch (e: Exception) {
                    println("[Novel Search] ${source.sourceName} failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        sourceResults
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .balancedNovelResults(query)
            .take(72)
    }

    suspend fun searchManga(query: String): List<UnifiedSearchResult> = coroutineScope {
        mangaSources.map { source ->
            async {
                try {
                    sourceSemaphore.withPermit {
                        withTimeoutOrNull(8_000) { source.searchManga(query) }.orEmpty()
                    }
                }
                catch (e: Exception) {
                    println("[Manga Search] ${source.sourceName} failed silently: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()
            .flatten()
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .take(72)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Popular / Trending — Fetch home screen content for all three tabs
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun fetchPopularAll(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        val novelTasks = sources.map { source ->
            async {
                try {
                    sourceSemaphore.withPermit {
                        withTimeoutOrNull(8_000) { source.fetchPopular(page) }.orEmpty()
                    }
                }
                catch (e: Exception) { emptyList() }
            }
        }
        val mangaTasks = mangaSources.map { source ->
            async {
                try {
                    source.searchManga("one")
                } catch (e: Exception) { emptyList() }
            }
        }
        val animeTask = async {
            try {
                aniListSource.fetchCurrentlyAiring(page = page, perPage = 20).map { anime ->
                    UnifiedSearchResult(
                        id = "anilist_${anime.id}",
                        title = anime.displayTitle,
                        author = "",
                        coverUrl = anime.coverUrl,
                        sourceName = "AniList",
                        url = "anilist:${anime.id}",
                        genre = anime.genres.take(3).joinToString(", "),
                        synopsis = anime.synopsis,
                        isManga = false,
                        isAnime = true,
                        animeResult = anime
                    )
                }
            } catch (e: Exception) { emptyList() }
        }

        val novels = (novelTasks.awaitAll().flatten() + curatedPopularNovelSeeds())
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .rankedNovelResults("")
        val mangas = mangaTasks.awaitAll().flatten()
        val animes = animeTask.await()

        (novels + mangas + animes)
            .distinctBy { it.title.lowercase().trim() }
            .filter { it.title.isNotEmpty() }
    }

    suspend fun fetchPopularNovels(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        cachedFeed("novels:$page") {
            val novelTasks = sources.map { source ->
                async {
                    try {
                        sourceSemaphore.withPermit {
                            withTimeoutOrNull(8_000) { source.fetchPopular(page) }.orEmpty()
                        }
                    } catch (e: Exception) {
                        println("[Novel Feed] ${source.sourceName} failed: ${e.message}")
                        emptyList()
                    }
                }
            }
            (novelTasks.awaitAll().flatten() + curatedPopularNovelSeeds())
                .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
                .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
                .rankedNovelResults("")
                .take(48)
        }
    }

    suspend fun fetchPopularManga(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        cachedFeed("manga:$page") {
            val seed = listOf("solo", "one", "kingdom", "hero").getOrElse((page - 1).mod(4)) { "one" }
            mangaSources.map { source ->
                async {
                    try {
                        sourceSemaphore.withPermit {
                            withTimeoutOrNull(8_000) { source.searchManga(seed) }.orEmpty()
                        }
                    } catch (e: Exception) {
                        println("[Manga Feed] ${source.sourceName} failed: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
                .flatten()
                .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
                .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
                .take(48)
        }
    }

    /** Fetch currently airing anime for the Anime tab home feed */
    suspend fun fetchCurrentlyAiring(page: Int = 1): List<AnimeResult> {
        val aniList = runCatching { aniListSource.fetchCurrentlyAiring(page = page, perPage = 24) }
            .getOrElse { emptyList() }
        return aniList
            .ifEmpty { tmdbSource.fetchAnimeFallback(page) }
            .ifEmpty { backendAnime(page) }
    }

    /** Fetch trending anime */
    suspend fun fetchTrendingAnime(page: Int = 1): List<AnimeResult> {
        val aniList = runCatching { aniListSource.fetchTrending(page = page, perPage = 24) }
            .getOrElse { emptyList() }
        return aniList
            .ifEmpty { tmdbSource.fetchAnimeFallback(page) }
            .ifEmpty { backendAnime(page) }
    }

    /** Search anime only */
    suspend fun searchAnime(query: String): List<AnimeResult> {
        val aniList = runCatching { aniListSource.search(query) }
            .getOrElse { emptyList() }
        return aniList
            .ifEmpty { tmdbSource.searchAnimeFallback(query) }
            .ifEmpty { backendAnimeSearch(query) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Video feed (K-Drama, Cartoon, Movies, Classic) — TMDB pipeline
    //  Always merges: client TMDB + server backend + WCOStream (for Classic)
    //  Never returns empty — falls back to curated seeds when all sources fail
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun fetchVideo(category: VideoCategory, page: Int = 1): List<UnifiedSearchResult> =
        cachedFeed("video:${category.name}:$page") {
            val results = mutableListOf<UnifiedSearchResult>()

            // Source 1: Client-side TMDB (always has fallback API key)
            val client = tmdbSource.fetchVideo(category, page)
            println("[Video Feed] ${category.name}: TMDB returned ${client.size} items")
            results.addAll(client)

            // Source 2: Server backend
            if (results.size < 18) {
                val server = backendVideo(category, page = page)
                println("[Video Feed] ${category.name}: Server returned ${server.size} items")
                results.addAll(server)
            }

            // Source 3: TMDB multi-page for donghua (fetch more pages for richer results)
    if (category == VideoCategory.DONGHUA && results.size < 18) {
        val more = tmdbSource.fetchVideo(VideoCategory.DONGHUA, page.coerceIn(1, 5) + 1)
        results.addAll(more)
        println("[Video Feed] Donghua: ${results.size} items after extra page")
    }

    // Source 4: Curated Chinese donghua/movie seeds as fallback
    if (category == VideoCategory.DONGHUA && results.size < 12) {
        val seeds = curatedChineseContentSeeds(page)
        println("[Video Feed] Donghua: Curated Chinese seeds (${seeds.size})")
        results.addAll(seeds)
    }

    // Source 3 (original): TMDB multi-page for movies
            if (category == VideoCategory.MOVIES && results.size < 18) {
                for (extraPage in (page.coerceIn(1, 5) + 1)..(page.coerceIn(1, 5) + 2)) {
                    if (results.size >= 36) break
                    val more = tmdbSource.fetchVideo(category, extraPage)
                    results.addAll(more)
                }
                println("[Video Feed] Movies: ${results.size} items after multi-page")
            }

            // Source 4: TMDB trending movies fallback for MOVIES tab (ensures popular movies always appear)
            if (category == VideoCategory.MOVIES && results.size < 12) {
                val trendingMovies = runCatching {
                    TMDBMovieScraper(httpClient).fetchTrending("movie")
                }.getOrElse { emptyList() }
                println("[Video Feed] Movies: TMDB trending returned ${trendingMovies.size} items")
                results.addAll(
                    trendingMovies.map { it.toUnifiedVideo(category, "TMDB") }
                )
            }

            // Source 5: WCOStream for Classic/Older Cartoon tab (always loads some classic cartoons)
            if (category == VideoCategory.CLASSIC && results.size < 24) {
                val wcoStream = fetchWcoStreamCartoons(page)
                println("[Video Feed] ${category.name}: WCOStream returned ${wcoStream.size} items")
                results.addAll(wcoStream)
            }

            // Source 6: YouTube Nollywood via Piped API (for NIGERIAN category)
            // This brings in fresh YouTube-based Nigerian movie content alongside TMDB.
            // Piped returns ad-free direct .mp4 streams that play in ExoPlayer.
            if (category == VideoCategory.NIGERIAN && results.size < 24) {
                val youtubeNollywood = youtubeNollywoodScraper.fetchYouTubeNollywoodFeed(page)
                println("[Video Feed] ${category.name}: YouTube Nollywood returned ${youtubeNollywood.size} items")
                results.addAll(youtubeNollywood.map { it.toYouTubeNollywoodVideo() })
            }

            // Source 7: Curated fallback seeds when everything else fails
            if (results.isEmpty()) {
                val seeds = curatedVideoSeeds(category)
                println("[Video Feed] ${category.name}: Using curated seeds (${seeds.size})")
                results.addAll(seeds)
            }

            results.distinctBy { it.id }
                .take(48)
        }

    suspend fun searchVideo(category: VideoCategory, query: String, page: Int = 1): List<UnifiedSearchResult> =
        cachedFeed("video_search:${category.name}:$query:$page") {
            val results = mutableListOf<UnifiedSearchResult>()
            val normalizedQuery = query.trim()

            // ── Source 1: Client-side TMDB search (type-specific), search pages 1-3 ──
            // Search multiple TMDB pages in parallel for best coverage
            // This catches movies/shows that may not appear on page 1 of type-specific search
            val pageSearch = coroutineScope {
                (page.coerceIn(1, 3)..3).map { p ->
                    async {
                        runCatching { tmdbSource.searchVideo(category, query, p) }
                            .getOrDefault(emptyList())
                    }
                }.map { it.await() }.flatten()
            }
            println("[Video Search] ${category.name}: Client TMDB ${pageSearch.size} items across pages 1-3 for '$query'")
            results.addAll(pageSearch)

            // ── Source 2: TMDB multi-search (search/multi) ──
            // Covers both movies and TV in one call. For MOVIES, also search TV in case
            // the query matches a TV show that the user expects to find.
            if (results.size < 15) {
                val mediaResults = mutableListOf<MediaResult>()
                for (mp in 1..3) {
                    val multi = runCatching {
                        TMDBMovieScraper(httpClient).searchMultiPaged(query, mp)
                    }.getOrElse { emptyList() }
                    mediaResults.addAll(multi)
                    if (mediaResults.size >= 36) break
                }
                println("[Video Search] ${category.name}: TMDB multi-search returned ${mediaResults.size} items for '$query'")
                if (mediaResults.isNotEmpty()) {
                    val filtered = mediaResults
                        .filter { media ->
                            when (category) {
                            VideoCategory.MOVIES -> true
                            VideoCategory.ANIME -> media.genres.contains("Animation", ignoreCase = true) &&
                                media.genres.contains("Japanese", ignoreCase = true)
                            VideoCategory.K_DRAMA -> media.genres.contains("Korean", ignoreCase = true)
                            VideoCategory.DONGHUA -> media.genres.contains("Animation", ignoreCase = true) &&
                                media.genres.contains("Chinese", ignoreCase = true)
                            VideoCategory.CARTOON -> media.genres.contains("Animation", ignoreCase = true) &&
                                !media.genres.contains("Japanese", ignoreCase = true)
                            VideoCategory.CLASSIC -> !media.genres.contains("Japanese", ignoreCase = true) &&
                                !media.genres.contains("Korean", ignoreCase = true)
                            VideoCategory.NIGERIAN -> media.genres.contains("Nigeria", ignoreCase = true) ||
                                media.genres.contains("Nigerian", ignoreCase = true) ||
                                media.genres.contains("Nollywood", ignoreCase = true)
                            }
                        }
                        .ifEmpty { mediaResults }
                    results.addAll(filtered.map { it.toUnifiedVideo(category, "TMDB") })
                }
            }

            // ── Source 3: Server backend search ──
            if (results.size < 12) {
                val server = backendVideo(category, query, page)
                println("[Video Search] ${category.name}: Server returned ${server.size} items for '$query'")
                results.addAll(server)
            }

            // ── Source 4: WCOStream for Classic (classic cartoons search) ──
            if (category == VideoCategory.CLASSIC && query.isNotBlank() && results.size < 12) {
                val wcoStream = wcoStreamScraper.search(query)
                    .map { it.toUnifiedVideo(VideoCategory.CLASSIC, "WCOStream") }
                println("[Video Search] ${category.name}: WCOStream returned ${wcoStream.size} items for '$query'")
                results.addAll(wcoStream)
            }

            // ── Source 5b: YouTube Nollywood via Piped API (for NIGERIAN category)
            // Searches YouTube for Nigerian movie content when TMDB returns thin results.
            // Piped returns ad-free direct .mp4 streams, no ads or tracking.
            if (category == VideoCategory.NIGERIAN && query.isNotBlank() && results.size < 12) {
                val youtubeNollywood = youtubeNollywoodScraper.search(query, page)
                println("[Video Search] ${category.name}: YouTube Nollywood returned ${youtubeNollywood.size} items for '$query'")
                results.addAll(youtubeNollywood.map { it.toYouTubeNollywoodVideo() })
            }

            // ── Source 6: TMDB trending title-match fallback (broadest net) ──
            if (results.size < 6 && query.isNotBlank()) {
                val searchTerms = normalizedQuery.split(" ").filter { it.length > 2 }
                val trendingMovies = runCatching {
                    TMDBMovieScraper(httpClient).fetchTrending("movie")
                }.getOrElse { emptyList() }
                val trendingTv = runCatching {
                    TMDBMovieScraper(httpClient).fetchTrending("tv")
                }.getOrElse { emptyList() }
                val allTrending = (trendingMovies + trendingTv)
                    .filter { media ->
                        media.title.contains(query, ignoreCase = true) ||
                            searchTerms.all { term -> media.title.contains(term, ignoreCase = true) } ||
                            media.description.contains(query, ignoreCase = true)
                    }
                    .distinctBy { it.id }
                if (allTrending.isNotEmpty()) {
                    println("[Video Search] ${category.name}: Trending title-match (${allTrending.size}) for '$query'")
                    results.addAll(
                        allTrending.map { it.toUnifiedVideo(category, "TMDB") }
                    )
                }
            }

            results.distinctBy { it.id }
                .take(48)
        }

    /**
     * Curated video seeds — popular movies, shows, cartoons, classics that always appear
     * when TMDB is unavailable or returns no results. This ensures the app never shows
     * an empty screen on first launch.
     */
    private fun curatedVideoSeeds(category: VideoCategory): List<UnifiedSearchResult> {
        return when (category) {
            VideoCategory.CLASSIC -> listOf(
                "Friends", "The Office", "Breaking Bad", "Game of Thrones",
                "Stranger Things", "The Crown", "Sherlock", "Doctor Who",
                "The Simpsons", "Seinfeld", "The Fresh Prince of Bel-Air", "Full House",
                "The Walking Dead", "Better Call Saul", "The Mandalorian", "House",
                "The Big Bang Theory", "How I Met Your Mother", "Modern Family", "Supernatural"
            ).mapIndexed { i, title ->
                UnifiedSearchResult(
                    id = "curated_classic_$i",
                    title = title,
                    coverUrl = "",
                    detailPageUrl = "tmdb://tv/$i",
                    sourceName = "TMDB",
                    genre = "Classic TV",
                    synopsis = "",
                    isVideo = true,
                    mediaKind = VideoCategory.CLASSIC.name
                )
            }
            VideoCategory.MOVIES -> listOf(
                "Inception", "The Matrix", "Interstellar", "The Dark Knight",
                "Avengers: Endgame", "Spider-Man: No Way Home", "Dune", "Oppenheimer",
                "The Godfather", "Pulp Fiction", "Forrest Gump", "Fight Club",
                "The Shawshank Redemption", "Goodfellas", "The Silence of the Lambs", "The Departed",
                "Tenet", "Blade Runner 2049", "Mad Max: Fury Road", "John Wick"
            ).mapIndexed { i, title ->
                UnifiedSearchResult(
                    id = "curated_movie_$i",
                    title = title,
                    coverUrl = "",
                    detailPageUrl = "tmdb://movie/${300 + i}",
                    sourceName = "TMDB",
                    genre = "Movie",
                    synopsis = "",
                    isVideo = true,
                    mediaKind = VideoCategory.MOVIES.name
                )
            }
            VideoCategory.CARTOON -> listOf(
                "SpongeBob SquarePants", "The Simpsons", "Family Guy", "Rick and Morty",
                "Adventure Time", "Gravity Falls", "Regular Show", "Steven Universe",
                "South Park", "Phineas and Ferb", "Bob's Burgers", "American Dad",
                "The Amazing World of Gumball", "Avatar: The Last Airbender", "Teen Titans Go", "Ben 10"
            ).mapIndexed { i, title ->
                UnifiedSearchResult(
                    id = "curated_cartoon_$i",
                    title = title,
                    coverUrl = "",
                    detailPageUrl = "tmdb://tv/${500 + i}",
                    sourceName = "TMDB",
                    genre = "Animation, Cartoon",
                    synopsis = "",
                    isVideo = true,
                    mediaKind = VideoCategory.CARTOON.name
                )
            }
            VideoCategory.K_DRAMA -> listOf(
                "Squid Game", "Crash Landing on You", "True Beauty", "Nevertheless",
                "My Love from the Star", "Descendants of the Sun", "Goblin", "Itaewon Class",
                "The Heirs", "What's Wrong with Secretary Kim", "King the Land", "Business Proposal",
                "Hotel del Luna", "Vincenzo", "Extraordinary Attorney Woo", "The Glory"
            ).mapIndexed { i, title ->
                UnifiedSearchResult(
                    id = "curated_kdrama_$i",
                    title = title,
                    coverUrl = "",
                    detailPageUrl = "tmdb://tv/${700 + i}",
                    sourceName = "TMDB",
                    genre = "Korean Drama, K-Drama",
                    synopsis = "",
                    isVideo = true,
                    mediaKind = VideoCategory.K_DRAMA.name
                )
            }
            VideoCategory.NIGERIAN -> {
                // Use the known Nollywood titles with real TMDB IDs for fallback seeds.
                // These come directly from the NollywoodScraper companion object.
                NollywoodScraper.KNOWN_NOLLYWOOD_TITLES.take(20).mapIndexed { i, entry ->
                    UnifiedSearchResult(
                        id = "nollywood_fallback_$i",
                        title = entry.title,
                        coverUrl = "",
                        detailPageUrl = "tmdb://${entry.tmdbType}/${entry.tmdbId}",
                        sourceName = "TMDB",
                        genre = entry.genres.ifBlank { "Nigerian, Nollywood" },
                        synopsis = "",
                        isVideo = true,
                        mediaKind = VideoCategory.NIGERIAN.name
                    )
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Curated Chinese donghua/movie seeds — popular donghua titles that always appear
     * when TMDB returns no results. These have relatively stable TMDB IDs.
     */
    private fun curatedChineseContentSeeds(page: Int = 1, query: String = ""): List<UnifiedSearchResult> {
        val allSeeds = listOf(
            "The King's Avatar", "Mo Dao Zu Shi", "Heaven Official's Blessing",
            "Scissor Seven", "Fog Hill of Five Elements", "Link Click",
            "The Daily Life of the Immortal King", "A Will Eternal",
            "Battle Through the Heavens", "Soul Land", "Douluo Continent",
            "Perfect World", "Swallowed Star", "Martial Master",
            "Against the Sky Supreme", "Release That Witch",
            "The Legend of Hei", "Fairies Albums", "Grandmaster of Demonic Cultivation",
            "Spare Me Great Lord", "Reverend Insanity", "Tales of Demons and Gods",
            "Fights Break Sphere", "Throne of Seal", "Dragon Raja",
            "The Outcast", "The Silver Guardian", "To Be Heroine",
            "The Guardian", "Psychic Princess", "Fox Spirit Matchmaker",
            "Big Fish & Begonia", "Ne Zha", "Ne Zha Reborn",
            "White Snake", "Green Snake", "Jiang Ziya",
            "The Wandering Earth", "The Wandering Earth 2",
            "Creation of the Gods I", "No More Bets",
            "YOLO", "Article 20", "Pegasus 2", "The Battle at Lake Changjin",
            "Hi, Mom", "Detective Chinatown 3",
            "The Taking of Tiger Mountain", "Operation Red Sea",
            "Wolf Warrior 2", "Lost in Russia"
        )
        val pageSize = 24
        val skip = ((page - 1) * pageSize).coerceAtMost(allSeeds.size)
        val seeds = if (query.isNotBlank()) {
            allSeeds.filter { it.contains(query, ignoreCase = true) || query.contains(it, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?: allSeeds
        } else {
            allSeeds
        }
        return seeds.drop(skip).take(pageSize).mapIndexed { i, title ->
            UnifiedSearchResult(
                id = "donghua_seed_${page}_$i",
                title = title,
                coverUrl = "",
                detailPageUrl = "tmdb://tv/${10000 + skip + i}",
                sourceName = "TMDB",
                genre = "Donghua, Chinese, Animation",
                synopsis = "",
                isVideo = true,
                mediaKind = VideoCategory.DONGHUA.name
            )
        }
    }

    /**
     * Fetch classic cartoons from WCOStream for the Classic/Older Cartoons tab.
     * Searches popular seeds that cover 90s/2000s Nickelodeon, Cartoon Network, Disney Channel legacy animation.
     */
    private suspend fun fetchWcoStreamCartoons(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        cachedFeed("wcostream:$page") {
            val seeds = listOf(
                "spongebob", "pokemon", "fairly oddparents", "powerpuff girls",
                "dexters laboratory", "ed edd n eddy", "rugrats", "scooby doo",
                "tom and jerry", "looney tunes", "courage the cowardly dog",
                "johnny bravo", "samurai jack", "ben 10", "the adventures of jimmy neutron",
                "rocket power", "wild thornberrys", "catdog", "hey arnold", "doug",
                "animaniacs", "batman the animated series", "x-men animated", "spider-man animated",
                "darkwing duck", "tailspin", "ducktales", "chip n dale rescue rangers"
            )
            val seed = seeds.getOrElse((page - 1).mod(seeds.size)) { "spongebob" }
            wcoStreamScraper.search(seed)
                .map { it.toUnifiedVideo(VideoCategory.CLASSIC, "WCOStream") }
                .distinctBy { it.id }
                .take(24)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  YouTube Nollywood (Piped API) — public helpers for playback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the YouTube video ID from a YOUTUBE_NOLLY unified result.
     * ID format: "youtube_nollywood_<videoId>"
     */
    fun extractYouTubeNollywoodVideoId(item: UnifiedSearchResult): String? {
        val prefix = "youtube_nollywood_"
        return item.id
            .removePrefix(prefix)
            .takeIf { it != item.id && it.isNotBlank() }
    }

    /**
     * Get the ad-free direct .mp4 stream URL for a YouTube Nollywood video
     * via the Piped API. Returns null if the stream cannot be resolved.
     */
    suspend fun extractYouTubeNollywoodStream(videoId: String): String? =
        youtubeNollywoodScraper.extractStreamUrl(videoId)

    /**
     * Convenience: resolve a YOUTUBE_NOLLY UnifiedSearchResult to a direct
     * playable stream URL. Returns null if the item type is not YOUTUBE_NOLLY
     * or if Piped cannot resolve the stream.
     */
    suspend fun resolveYouTubeNollywoodStream(item: UnifiedSearchResult): String? {
        val videoId = extractYouTubeNollywoodVideoId(item) ?: return null
        return extractYouTubeNollywoodStream(videoId)
    }

    private suspend fun backendAnime(page: Int = 1): List<AnimeResult> =
        backendContentItems("anime", page = page).mapNotNull { it.toBackendAnimeResult() }

    private suspend fun backendAnimeSearch(query: String, page: Int = 1): List<AnimeResult> =
        backendContentItems("anime", query = query, page = page).mapNotNull { it.toBackendAnimeResult() }

    private suspend fun backendVideo(
        category: VideoCategory,
        query: String = "",
        page: Int = 1
    ): List<UnifiedSearchResult> =
        backendContentItems(category.backendContentType(), query, page).mapNotNull { it.toBackendUnifiedVideo(category) }

    private suspend fun backendContentItems(
        type: String,
        query: String = "",
        page: Int = 1
    ): List<JsonObject> = runCatching {
        val endpoint = if (query.isBlank()) "home" else "search"
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/content/$endpoint") {
            parameter("type", type)
            parameter("page", page)
            if (query.isNotBlank()) parameter("q", query)
        }.bodyAsText()
        val root = backendContentJson
            .parseToJsonElement(raw)
            .jsonObject
        root["data"]
            ?.jsonObject
            ?.get("items")
            ?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
    }.getOrElse { error ->
        println("[Content API] $type feed failed: ${error.message}")
        emptyList()
    }

    private suspend fun cachedFeed(
        key: String,
        loader: suspend () -> List<UnifiedSearchResult>
    ): List<UnifiedSearchResult> {
        // Check fresh cache
        if (isCacheFresh(key)) {
            feedCache[key]?.let { return it.data }
        }
        val loaded = loader()
        // Only cache non-empty results — never lock an empty feed for 2 minutes
        if (loaded.isNotEmpty()) {
            feedCache[key] = CacheEntry(loaded, currentTimeMillis())
            if (feedCache.size > 24) {
                feedCache.keys.firstOrNull()?.let { feedCache.remove(it) }
            }
        }
        return loaded
    }

    /**
     * Fetch episode list for a given anime title.
     * Tries Anineko first, falls back to AnimePahe.
     */
    suspend fun fetchEpisodes(
        animeTitleQuery: String,
        episodeCount: Int = 0,
        alternateQueries: List<String> = emptyList()
    ): List<AnimeEpisode> {
        val normalizedAnimeTitle = animeTitleQuery.normalizedAnimeSearchTitle()
        val maxEpisodes = episodeCount.takeIf { it > 0 } ?: 300
        val knownSlug = knownAnimeSlugOverrides[normalizedAnimeTitle]
            ?: knownAnimeSlugOverrides.entries.firstOrNull { (k, _) ->
                normalizedAnimeTitle.contains(k) || k.contains(normalizedAnimeTitle)
            }?.value

        if (knownSlug != null) {
            val episodes = aninekoScraper.fetchEpisodesBySlug(knownSlug, maxEpisodes)
            if (episodes.isNotEmpty()) return episodes
        }

        val queries = (listOf(animeTitleQuery) + alternateQueries)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        for (query in queries) {
            val aninekoEpisodes = aninekoScraper.fetchEpisodes(query, maxEpisodes)
                .distinctBy { it.url }
                .sortedByDescending { it.episodeNumber }
            if (aninekoEpisodes.isNotEmpty()) return aninekoEpisodes

            val animePaheEpisodes = animePaheScraper.fetchEpisodes(query)
                .distinctBy { it.url }
                .sortedByDescending { it.episodeNumber }
            if (animePaheEpisodes.isNotEmpty()) return animePaheEpisodes
        }

        if (episodeCount > 0) {
            return aninekoScraper.fallbackEpisodes(
                animeTitleQuery = animeTitleQuery,
                episodeCount = episodeCount,
                maxEpisodes = maxEpisodes.coerceAtMost(episodeCount).coerceAtLeast(1)
            )
        }

        return emptyList()
    }

    suspend fun fetchEpisodesFromAnimeProvider(
        provider: String,
        animeTitleQuery: String,
        episodeCount: Int = 0,
        alternateQueries: List<String> = emptyList(),
        preferredAninekoSlug: String? = null
    ): List<AnimeEpisode> {
        val normalizedProvider = provider.lowercase()
        if (normalizedProvider == "auto") {
            return fetchEpisodes(animeTitleQuery, episodeCount, alternateQueries)
        }
        val queries = (listOf(animeTitleQuery) + alternateQueries)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val maxEpisodes = episodeCount.takeIf { it > 0 } ?: 300
        return when (normalizedProvider) {
            "anineko" -> {
                preferredAninekoSlug?.takeIf { it.isNotBlank() }?.let { slug ->
                    aninekoScraper.fetchEpisodesBySlug(slug, maxEpisodes)
                        .takeIf { it.isNotEmpty() }
                        ?.let { return it }
                }
                val normalizedAnimeTitle = animeTitleQuery.normalizedAnimeSearchTitle()
                val knownSlug = knownAnimeSlugOverrides[normalizedAnimeTitle]
                    ?: knownAnimeSlugOverrides.entries.firstOrNull { (k, _) ->
                        normalizedAnimeTitle.contains(k) || k.contains(normalizedAnimeTitle)
                    }?.value
                knownSlug?.let { slug ->
                    aninekoScraper.fetchEpisodesBySlug(slug, maxEpisodes)
                        .takeIf { it.isNotEmpty() }
                        ?.let { return it }
                }
                queries.firstNotNullOfOrNull { query ->
                    aninekoScraper.fetchEpisodes(query, maxEpisodes)
                        .distinctBy { it.url }
                        .sortedByDescending { it.episodeNumber }
                        .takeIf { it.isNotEmpty() }
                } ?: if (episodeCount > 0) {
                    aninekoScraper.fallbackEpisodes(
                        animeTitleQuery = animeTitleQuery,
                        episodeCount = episodeCount,
                        maxEpisodes = maxEpisodes.coerceAtMost(episodeCount).coerceAtLeast(1)
                    )
                } else {
                    emptyList()
                }
            }
            "animepahe" -> queries.firstNotNullOfOrNull { query ->
                animePaheScraper.fetchEpisodes(query)
                    .distinctBy { it.url }
                    .sortedByDescending { it.episodeNumber }
                    .takeIf { it.isNotEmpty() }
            }.orEmpty()
            "hianime", "animekai", "kickassanime", "animesaturn", "animeunity", "animesama", "consumetpahe", "zoro" ->
                consumetAnimeScraper.fetchEpisodes(
                    provider = normalizedProvider,
                    animeTitleQuery = queries.firstOrNull() ?: animeTitleQuery,
                    alternateQueries = queries.drop(1),
                    maxEpisodes = maxEpisodes
                )
            else -> fetchEpisodes(animeTitleQuery, episodeCount, alternateQueries)
        }
    }

    suspend fun fetchAnimeSeasonChoices(anime: AnimeResult): List<AnimeSeasonChoice> {
        val chain = aniListSource.fetchSeasonChain(anime.id)
            .takeIf { it.isNotEmpty() }
            ?: listOf(anime)
        val choices = chain.mapIndexed { index, item ->
            val title = item.displayTitle
            AnimeSeasonChoice(
                id = item.id,
                label = title.animeSeasonLabel(index),
                title = title,
                titleEnglish = item.titleEnglish,
                titleRomaji = item.titleRomaji,
                episodeCount = item.episodeCount,
                aninekoSlug = item.bestKnownAninekoSlug()
            )
        }
        return choices
            .distinctBy { it.id }
            .ifEmpty { listOf(anime.toCurrentSeasonChoice()) }
    }

    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        if (ConsumetAnimeScraper.isConsumetEpisodeUrl(episodePageUrl)) {
            return consumetAnimeScraper.extractStreamUrl(episodePageUrl)
                ?.takeIf { it.isDirectPlayableAnimeStream() }
        }
        val extracted = if (episodePageUrl.contains("animepahe", ignoreCase = true)) {
            animePaheScraper.extractStreamUrl(episodePageUrl)
                ?: aninekoScraper.extractStreamUrl(episodePageUrl)
        } else {
            aninekoScraper.extractStreamUrl(episodePageUrl)
                ?: animePaheScraper.extractStreamUrl(episodePageUrl)
        }
        return extracted?.takeIf { it.isDirectPlayableAnimeStream() }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Novel / Manga helpers (unchanged from before)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun fetchChapters(novelUrl: String, sourceName: String): List<Chapter> {
        val source = sources.find { it.sourceName == sourceName } ?: sources.first()
        return try { source.fetchChapters(novelUrl).normalizedChapterOrder() } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchChapterText(chapterUrl: String, sourceName: String): String {
        val source = sources.find { it.sourceName == sourceName } ?: sources.first()
        return try { source.fetchChapterText(chapterUrl) }
        catch (e: Exception) { "Failed to load chapter. Please try again." }
    }

    suspend fun fetchMangaChapters(mangaUrl: String, sourceName: String): List<MangaChapter> {
        // Check comic sources first (ReadAllComics, ZipComic, BatCave)
        val comicSource = comicSources.find { it.sourceName == sourceName }
        if (comicSource != null) return try { comicSource.fetchChapters(mangaUrl).normalizedComicChapterOrder() } catch (e: Exception) { emptyList() }
        // Fall back to manga sources
        val scraper = mangaSources.find { it.sourceName == sourceName } ?: mangaSources.first()
        return try { scraper.fetchMangaChapters(mangaUrl).normalizedMangaChapterOrder() } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchMangaPages(chapterUrl: String, sourceName: String): List<String> {
        // Check comic sources first (ReadAllComics, ZipComic, BatCave)
        val comicSource = comicSources.find { it.sourceName == sourceName }
        if (comicSource != null) return try { comicSource.fetchPages(chapterUrl) } catch (e: Exception) { emptyList() }
        // Fall back to manga sources
        val scraper = mangaSources.find { it.sourceName == sourceName } ?: mangaSources.first()
        return try { scraper.fetchMangaPages(chapterUrl) } catch (e: Exception) { emptyList() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Comic helpers — Western comics from ReadAllComics, ZipComic, BatCave
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun searchComics(query: String): List<UnifiedSearchResult> = coroutineScope {
        comicSources.map { source ->
            async {
                try {
                    sourceSemaphore.withPermit {
                        withTimeoutOrNull(8_000) { source.search(query) }.orEmpty()
                    }
                } catch (e: Exception) {
                    println("[Comic Search] ${source.sourceName} failed silently: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()
            .flatten()
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .take(72)
    }

    suspend fun fetchPopularComics(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        cachedFeed("comics:$page") {
            val seed = listOf("batman", "superman", "spider-man", "x-men", "avengers", "justice")
                .getOrElse((page - 1).mod(6)) { "batman" }
            comicSources.map { source ->
                async {
                    try {
                        sourceSemaphore.withPermit {
                            withTimeoutOrNull(8_000) { source.search(seed) }.orEmpty()
                        }
                    } catch (e: Exception) {
                        println("[Comic Feed] ${source.sourceName} failed: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
                .flatten()
                .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
                .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
                .take(48)
                .ifEmpty {
                    // Fallback: curated comic seeds when scrapers fail
                    listOf(
                        "Batman: The Dark Knight Returns", "Superman: Red Son",
                        "Spider-Man: Blue", "Watchmen", "V for Vendetta",
                        "The Walking Dead", "Saga", "Invincible",
                        "The Sandman", "Preacher", "Hellboy", "Sin City",
                    ).mapIndexed { i, title ->
                        UnifiedSearchResult(
                            id = "curated_comic_$i",
                            title = title,
                            coverUrl = "",
                            detailPageUrl = "comic:curated:$i",
                            sourceName = "ZipComic",
                            isComic = true,
                            genre = "Western Comic"
                        )
                    }
                }
        }
    }

    suspend fun fetchComicChapters(comicUrl: String, sourceName: String): List<MangaChapter> {
        val source = comicSources.find { it.sourceName == sourceName } ?: comicSources.first()
        return try { source.fetchChapters(comicUrl).normalizedComicChapterOrder() } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchComicPages(chapterUrl: String, sourceName: String): List<String> {
        val source = comicSources.find { it.sourceName == sourceName } ?: comicSources.first()
        return try { source.fetchPages(chapterUrl) } catch (e: Exception) { emptyList() }
    }
}

private fun String.isDirectPlayableAnimeStream(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
        Regex("""/(playlist|manifest|hls|dash)(/|$)""").containsMatchIn(clean) ||
        startsWith("file:", ignoreCase = true)
}

private data class KnownNovelEntry(
    val title: String,
    val sourceName: String,
    val detailPageUrl: String,
    val coverUrl: String = "",
    val author: String = "",
    val genre: String = "",
    val aliases: List<String> = emptyList()
)

private val knownNovelEntries = listOf(
    // ── System / Game ────────────────────────────────────────────────────────
    KnownNovelEntry(
        title = "My Vampire System",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/my-vampire-system-v1.html",
        genre = "Fantasy, System, Action"
    ),
    KnownNovelEntry(
        title = "Solo Leveling",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/solo-leveling.html",
        genre = "Action, Adventure, Fantasy"
    ),
    KnownNovelEntry(
        title = "Omniscient Reader's Viewpoint",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/omniscient-readers-viewpoint.html",
        genre = "Action, Fantasy, System"
    ),
    KnownNovelEntry(
        title = "The Beginning After The End",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/the-beginning-after-the-end.html",
        genre = "Fantasy, Isekai, Action"
    ),
    KnownNovelEntry(
        title = "Reincarnation of the Suicidal Battle God",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/reincarnation-of-the-suicidal-battle-god.html",
        genre = "Action, Fantasy"
    ),
    KnownNovelEntry(
        title = "Return of the Mount Hua Sect",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/return-of-the-mount-hua-sect.html",
        genre = "Martial Arts, Action"
    ),
    KnownNovelEntry(
        title = "Shadow Slave",
        sourceName = "RoyalRoad",
        detailPageUrl = "https://www.royalroad.com/fiction/48105/shadow-slave",
        genre = "Fantasy, Dark, Action"
    ),
    KnownNovelEntry(
        title = "Dungeon Crawler Carl",
        sourceName = "RoyalRoad",
        detailPageUrl = "https://www.royalroad.com/fiction/29358/dungeon-crawler-carl",
        genre = "LitRPG, Comedy, Action"
    ),
    // ── Xianxia / Wuxia / Xuanhuan ──────────────────────────────────────────
    KnownNovelEntry(
        title = "Renegade Immortal",
        sourceName = "Wuxiaworld",
        detailPageUrl = "https://www.wuxiaworld.com/novel/renegade-immortal",
        coverUrl = "https://cdn.wuxiaworld.com/images/covers/rge.webp",
        author = "Er Gen",
        genre = "Xianxia, Action, Fantasy",
        aliases = listOf("Xian Ni")
    ),
    KnownNovelEntry(
        title = "A Will Eternal",
        sourceName = "Wuxiaworld",
        detailPageUrl = "https://www.wuxiaworld.com/novel/a-will-eternal",
        coverUrl = "https://cdn.wuxiaworld.com/images/covers/awe.webp",
        author = "Er Gen",
        genre = "Xianxia, Comedy, Fantasy"
    ),
    KnownNovelEntry(
        title = "I Shall Seal the Heavens",
        sourceName = "Wuxiaworld",
        detailPageUrl = "https://www.wuxiaworld.com/novel/i-shall-seal-the-heavens",
        coverUrl = "https://cdn.wuxiaworld.com/images/covers/issth.webp",
        author = "Er Gen",
        genre = "Xianxia, Action, Adventure",
        aliases = listOf("ISSTH")
    ),
    KnownNovelEntry(
        title = "Pursuit of the Truth",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/pursuit-of-the-truth-v1.html",
        coverUrl = "https://img.readnovelfull.com/thumb/t-300x439/Pursuit-of-the-Truth-ndUNFBDCxY.jpg",
        author = "Er Gen",
        genre = "Xianxia, Mystery, Tragedy",
        aliases = listOf("Beseech the Devil", "PotT")
    ),
    KnownNovelEntry(
        title = "Martial Peak",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/martial-peak-v3.html",
        genre = "Martial Arts, Xuanhuan, Action"
    ),
    KnownNovelEntry(
        title = "Martial God Asura",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/martial-god-asura.html",
        genre = "Martial Arts, Action, Xuanhuan"
    ),
    KnownNovelEntry(
        title = "Emperor's Domination",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/emperor-s-domination.html",
        genre = "Xuanhuan, Action"
    ),
    KnownNovelEntry(
        title = "Against the Gods",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/against-the-gods.html",
        genre = "Martial Arts, Xuanhuan, Romance"
    ),
    KnownNovelEntry(
        title = "Tales of Demons and Gods",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/tales-of-demons-and-gods.html",
        genre = "Xianxia, Fantasy, Action"
    ),
    // ── Isekai / Reincarnation ──────────────────────────────────────────────
    KnownNovelEntry(
        title = "Reincarnated as a Slime",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/that-time-i-got-reincarnated-as-a-slime",
        genre = "Isekai, Fantasy, Action"
    ),
    KnownNovelEntry(
        title = "Overlord",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/overlord",
        genre = "Isekai, Dark Fantasy, Action"
    ),
    KnownNovelEntry(
        title = "Mushoku Tensei: Jobless Reincarnation",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/mushoku-tensei-jobless-reincarnation",
        genre = "Isekai, Fantasy, Adventure",
        aliases = listOf("Mushoku Tensei")
    ),
    KnownNovelEntry(
        title = "The Rising of the Shield Hero",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/the-rising-of-the-shield-hero",
        genre = "Isekai, Action, Fantasy"
    ),
    KnownNovelEntry(
        title = "Arifureta: From Commonplace to World's Strongest",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/arifureta-from-commonplace-to-worlds-strongest",
        genre = "Isekai, Action, Fantasy"
    ),
    KnownNovelEntry(
        title = "Sword Art Online",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/sword-art-online",
        genre = "Isekai, Sci-Fi, Action"
    ),
    KnownNovelEntry(
        title = "Re:Zero",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/rezero-starting-life-in-another-world",
        genre = "Isekai, Dark Fantasy, Drama",
        aliases = listOf("Re:Zero")
    ),
    KnownNovelEntry(
        title = "No Game No Life",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/no-game-no-life",
        genre = "Isekai, Game, Comedy"
    ),
    // ── Romance / Drama ─────────────────────────────────────────────────────
    KnownNovelEntry(
        title = "Under the Oak Tree",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/under-the-oak-tree.html",
        genre = "Romance, Fantasy, Drama"
    ),
    KnownNovelEntry(
        title = "The Villainess Reverses the Hourglass",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/the-villainess-reverses-the-hourglass.html",
        genre = "Romance, Fantasy, Drama"
    ),
    KnownNovelEntry(
        title = "I Am the Fated Villain",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/i-am-the-fated-villain.html",
        genre = "Fantasy, Romance, Action"
    ),
    // ── Mystery / Thriller ──────────────────────────────────────────────────
    KnownNovelEntry(
        title = "Lord of the Mysteries",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/lord-of-the-mysteries",
        genre = "Mystery, Fantasy, Supernatural"
    ),
    KnownNovelEntry(
        title = "The Grandmaster of Demonic Cultivation",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/the-grandmaster-of-demonic-cultivation",
        genre = "Xianxia, Mystery, BL",
        aliases = listOf("Mo Dao Zu Shi", "MDZS")
    ),
    // ── Sci-Fi ───────────────────────────────────────────────────────────────
    KnownNovelEntry(
        title = "Infinite Dendrogram",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/infinite-dendrogram",
        genre = "LitRPG, Sci-Fi, Action"
    ),
    KnownNovelEntry(
        title = "All the Gallant Men",
        sourceName = "RoyalRoad",
        detailPageUrl = "https://www.royalroad.com/fiction/11193/mother-of-learning",
        genre = "Fantasy, Time Loop, Mystery"
    ),
    // ── Horror / Dark ────────────────────────────────────────────────────────
    KnownNovelEntry(
        title = "The Tutorial is Too Hard",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/the-tutorial-is-too-hard.html",
        genre = "Action, Fantasy, Dark"
    ),
    KnownNovelEntry(
        title = "Warlock of the Magus World",
        sourceName = "ReadNovelFull",
        detailPageUrl = "https://readnovelfull.com/warlock-of-the-magus-world.html",
        genre = "Dark Fantasy, Xuanhuan, Sci-Fi"
    )
)


private fun knownNovelFallbacks(query: String): List<UnifiedSearchResult> {
    val normalizedQuery = query.normalizeForNovelSearch()
    if (normalizedQuery.isBlank()) {
        return knownNovelEntries.map { it.toResult() }
    }
    return knownNovelEntries
        .filter { entry ->
            val title = entry.title.normalizeForNovelSearch()
            val aliases = entry.aliases.map { it.normalizeForNovelSearch() }
            title.contains(normalizedQuery) ||
                normalizedQuery.contains(title) ||
                aliases.any { it.contains(normalizedQuery) || normalizedQuery.contains(it) }
        }
        .map { it.toResult() }
}

private fun curatedPopularNovelSeeds(): List<UnifiedSearchResult> =
    knownNovelEntries.map { it.toResult() }

private fun MediaResult.toUnifiedVideo(category: VideoCategory, sourceLabel: String): UnifiedSearchResult =
    UnifiedSearchResult(
        id = "source_${sourceLabel}_${id}".normalizeForNovelSearch().replace(" ", "_"),
        title = title,
        coverUrl = coverUrl,
        detailPageUrl = id,
        sourceName = sourceLabel,
        genre = genres.ifBlank { category.label },
        synopsis = description,
        isVideo = true,
        mediaKind = category.name,
        url = id
    )

private fun VideoCategory.backendContentType(): String = when (this) {
    VideoCategory.ANIME -> "anime"
    VideoCategory.DONGHUA -> "donghua"
    VideoCategory.K_DRAMA -> "kdrama"
    VideoCategory.CARTOON -> "cartoon"
    VideoCategory.CLASSIC -> "classic"
    VideoCategory.MOVIES -> "movies"
    VideoCategory.NIGERIAN -> "nigerian"
}

private fun JsonObject.contentString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.toBackendUnifiedVideo(category: VideoCategory): UnifiedSearchResult? {
    val title = contentString("title").ifBlank { return null }
    val detailUrl = contentString("detailUrl")
    val kind = contentString("kind")
    val rawId = contentString("id").ifBlank { "$kind:$title" }
    return UnifiedSearchResult(
        id = "backend_${category.name}_${rawId}".normalizeForNovelSearch().replace(" ", "_"),
        title = title,
        coverUrl = contentString("coverUrl"),
        detailPageUrl = detailUrl,
        sourceName = contentString("sourceName").ifBlank { "NovelApp" },
        genre = contentString("subtitle").ifBlank { category.label },
        synopsis = contentString("synopsis"),
        isVideo = true,
        mediaKind = category.name,
        url = detailUrl
    )
}

private fun JsonObject.toBackendAnimeResult(): AnimeResult? {
    val title = contentString("title").ifBlank { return null }
    return AnimeResult(
        id = contentString("id").ifBlank { "backend_anime_${title.normalizeForNovelSearch()}" },
        titleRomaji = title,
        titleEnglish = title,
        coverUrl = contentString("coverUrl"),
        synopsis = contentString("synopsis"),
        genres = contentString("subtitle")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() },
        sourceName = contentString("sourceName").ifBlank { "NovelApp" }
    )
}

private fun KnownNovelEntry.toResult(): UnifiedSearchResult =
    UnifiedSearchResult(
        id = "known_${sourceName}_${title}".normalizeForNovelSearch().replace(" ", "_"),
        title = title,
        coverUrl = coverUrl,
        detailPageUrl = detailPageUrl,
        sourceName = sourceName,
        author = author,
        genre = genre,
        synopsis = "Known popular title fallback. If live provider search is blocked, this direct listing keeps the title discoverable."
    )

private fun List<UnifiedSearchResult>.rankedNovelResults(query: String): List<UnifiedSearchResult> =
    sortedWith(
        compareByDescending<UnifiedSearchResult> { it.novelSearchScore(query) }
            .thenBy { it.title.lowercase().trim() }
            .thenBy { it.sourceName.lowercase() }
    )

private fun List<UnifiedSearchResult>.balancedNovelResults(query: String): List<UnifiedSearchResult> {
    val ranked = rankedNovelResults(query)
    val nonRoyalRoad = ranked.filter { it.sourceName != "RoyalRoad" }
    val royalRoad = ranked.filter { it.sourceName == "RoyalRoad" }
    if (nonRoyalRoad.isEmpty()) return royalRoad.take(30)

    val royalRoadLimit = if (query.normalizeForNovelSearch().hasTranslatedNovelIntent()) {
        minOf(2, royalRoad.size)
    } else {
        minOf(6, maxOf(2, nonRoyalRoad.size / 3 + 1))
    }
    return (nonRoyalRoad + royalRoad.take(royalRoadLimit))
        .rankedNovelResults(query)
        .take(40)
}

private fun UnifiedSearchResult.novelSearchScore(query: String): Int {
    val q = query.normalizeForNovelSearch()
    val title = title.normalizeForNovelSearch()
    val sourceBoost = when (sourceName.lowercase()) {
        "webnovel api" -> 900
        "wuxiaworld" -> 850
        "readnovelfull" -> 850
        "freewebnovel" -> 820
        "lightnovelpub" -> 790
        "boxnovel" -> 760
        "royalroad" -> 50
        else -> 400
    }
    val titleScore = when {
        q.isBlank() -> 0
        title == q -> 10_000
        title.startsWith(q) -> 7_500
        title.contains(q) -> 5_000
        q.split(" ").filter { it.length > 2 }.all { title.contains(it) } -> 3_500
        else -> 0
    }
    val knownWebNovelIntent = q.hasTranslatedNovelIntent()
    val intentBoost = if (knownWebNovelIntent && sourceName != "RoyalRoad") 650 else 0
    val royalRoadPenalty = if (sourceName == "RoyalRoad" && knownWebNovelIntent) 800 else 0
    return titleScore + sourceBoost + intentBoost - royalRoadPenalty
}

private fun String.normalizeForNovelSearch(): String =
    lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

private fun String.hasTranslatedNovelIntent(): Boolean =
    listOf(
        "vampire",
        "system",
        "renegade",
        "immortal",
        "will eternal",
        "martial",
        "mysteries",
        "pursuit",
        "truth",
        "heaven",
        "seal",
        "dao",
        "demon",
        "devil",
        "emperor",
        "cultivation",
        "xianxia",
        "wuxia",
        "xuanhuan"
    ).any { contains(it) }

private val knownAnimeSlugOverrides = mapOf(
    "dragon ball" to "dragon-ball",
    "dragon ball z" to "dragon-ball-z",
    "dragon ball super" to "dragon-ball-super",
    "jujutsu kaisen 2nd season" to "jujutsu-kaisen-2nd-season",
    "jujutsu kaisen season 2" to "jujutsu-kaisen-2nd-season",
    "jujutsu kaisen 0 movie" to "jujutsu-kaisen-0-movie",
    "my hero academia" to "boku-no-hero-academia",
    "boku no hero academia" to "boku-no-hero-academia",
    "my hero academia 2" to "my-hero-academia-2",
    "my hero academia 3" to "my-hero-academia-3",
    "my hero academia 4" to "my-hero-academia-4",
    "my hero academia 5" to "my-hero-academia-5",
    "my hero academia 6" to "my-hero-academia-6",
    "my hero academia 7" to "my-hero-academia-7",
    "my hero academia final season" to "my-hero-academia-final-season",
    "solo leveling season 2 arise from the shadow" to "solo-leveling-season-2-arise-from-the-shadow",
    "solo leveling 2nd season" to "solo-leveling-season-2-arise-from-the-shadow",
    "solo leveling" to "solo-leveling",
    "jujutsu kaisen" to "jujutsu-kaisen-tv",
    "one piece" to "one-piece",
    "re zero" to "rezero-starting-life-in-another-world",
    "rezero" to "rezero-starting-life-in-another-world",
    "re zero starting life in another world" to "rezero-starting-life-in-another-world",
    "liar game" to "liar-game"
)

private fun String.normalizedAnimeSearchTitle(): String =
    lowercase()
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun String.removeAnimeSeasonSuffix(): String =
    normalizedAnimeSearchTitle()
        .replace(Regex("""\b(season\s+\d+|\d+(st|nd|rd|th)\s+season|s\d+|part\s+\d+|cour\s+\d+|movie|special|ova|ona|recap|dub|sub)\b"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun AnimeResult.toCurrentSeasonChoice(): AnimeSeasonChoice =
    AnimeSeasonChoice(
        id = id,
        label = displayTitle.animeSeasonLabel(0),
        title = displayTitle,
        titleEnglish = titleEnglish,
        titleRomaji = titleRomaji,
        episodeCount = episodeCount,
        aninekoSlug = bestKnownAninekoSlug()
    )

private fun AnimeResult.bestKnownAninekoSlug(): String? =
    listOf(displayTitle, titleEnglish, titleRomaji)
        .map { it.normalizedAnimeSearchTitle() }
        .firstNotNullOfOrNull { title ->
            knownAnimeSlugOverrides[title]
                ?: knownAnimeSlugOverrides.entries.firstOrNull { (key, _) ->
                    title.contains(key) || key.contains(title)
                }?.value
        }

private fun String.animeSeasonLabel(index: Int): String {
    val normalized = normalizedAnimeSearchTitle()
    Regex("""\bseason\s+(\d+)\b""").find(normalized)?.groupValues?.getOrNull(1)?.let { return "Season $it" }
    Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""").find(normalized)?.groupValues?.getOrNull(1)?.let { return "Season $it" }
    Regex("""\bpart\s+(\d+)\b""").find(normalized)?.groupValues?.getOrNull(1)?.let { return "Part $it" }
    if ("final season" in normalized) return "Final"
    if ("movie" in normalized) return "Movie"
    if ("special" in normalized || "ova" in normalized || "ona" in normalized) return "Specials"
    return "Season ${index + 1}"
}
