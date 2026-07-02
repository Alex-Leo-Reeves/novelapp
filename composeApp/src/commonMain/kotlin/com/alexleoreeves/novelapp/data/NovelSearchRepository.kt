package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
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
    private val feedCache = mutableMapOf<String, List<UnifiedSearchResult>>()

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
        WeebCentralScraper(httpClient)  // replaces MangaSeeScraper (mangasee123.com → weebcentral.com)
    )

    /** Anime sources */
    internal val aniListSource = AniListSource(httpClient)
    internal val aninekoScraper = AninekoScraper(httpClient)
    internal val animePaheScraper = AnimePaheScraper(httpClient)
    internal val tmdbSource = TmdbSource(
        client = httpClient,
        readAccessToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN,
        apiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY
    )
    private val dramaCoolScraper = DramaCoolScraper(httpClient)
    private val kimCartoonScraper = KimCartoonScraper(httpClient)

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

    suspend fun fetchVideo(category: VideoCategory, page: Int = 1): List<UnifiedSearchResult> =
        cachedFeed("video:${category.name}:$page") {
            tmdbSource.fetchVideo(category, page)
                .ifEmpty { backendVideo(category, page = page) }
        }

    suspend fun searchVideo(category: VideoCategory, query: String, page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        val tmdb = async { tmdbSource.searchVideo(category, query, page) }
        val source = async {
            when (category) {
                VideoCategory.K_DRAMA -> dramaCoolScraper.search(query).map { it.toUnifiedVideo(VideoCategory.K_DRAMA, "DramaCool") }
                VideoCategory.CARTOON -> kimCartoonScraper.search(query).map { it.toUnifiedVideo(VideoCategory.CARTOON, "KimCartoon") }
                VideoCategory.MOVIES -> emptyList()
            }
        }
        val combined = (source.await() + tmdb.await())
            .distinctBy { it.detailPageUrl.ifBlank { it.id } }
        combined.ifEmpty { backendVideo(category, query, page) }
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
        feedCache[key]?.let { return it }
        return loader().also { loaded ->
            feedCache[key] = loaded
            if (feedCache.size > 24) {
                feedCache.keys.firstOrNull()?.let { feedCache.remove(it) }
            }
        }
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
        alternateQueries: List<String> = emptyList()
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
            else -> fetchEpisodes(animeTitleQuery, episodeCount, alternateQueries)
        }
    }

    suspend fun extractStreamUrl(episodePageUrl: String): String? {
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
        val scraper = mangaSources.find { it.sourceName == sourceName } ?: mangaSources.first()
        return try { scraper.fetchMangaChapters(mangaUrl).normalizedMangaChapterOrder() } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchMangaPages(chapterUrl: String, sourceName: String): List<String> {
        val scraper = mangaSources.find { it.sourceName == sourceName } ?: mangaSources.first()
        return try { scraper.fetchMangaPages(chapterUrl) } catch (e: Exception) { emptyList() }
    }
}

private fun String.isDirectPlayableAnimeStream(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
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
    VideoCategory.K_DRAMA -> "kdrama"
    VideoCategory.CARTOON -> "cartoon"
    VideoCategory.MOVIES -> "movies"
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
    "my hero academia" to "boku-no-hero-academia",
    "boku no hero academia" to "boku-no-hero-academia",
    "solo leveling" to "ore-dake-level-up-na-ken",
    "jujutsu kaisen" to "jujutsu-kaisen-tv",
    "one piece" to "one-piece"
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
