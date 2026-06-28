package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import com.alexleoreeves.novelapp.platform.platformHttpClient
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/**
 * Aggregates search results from all registered sources in parallel.
 * Every source is treated as an equal — all fire simultaneously.
 * Individual source failures are isolated and never crash the whole search.
 */
class NovelSearchRepository(
    geminiApiKey: String,
    rapidApiKey: String,
    rapidApiHost: String
) {
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
            .rankedNovelResults(query)
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
                    val results = source.search(query)
                    println("[Novel Search] ${source.sourceName}: ${results.size} result(s) for \"$query\"")
                    results
                }
                catch (e: Exception) {
                    println("[Novel Search] ${source.sourceName} failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        (sourceResults + knownNovelFallbacks(query))
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .rankedNovelResults(query)
    }

    suspend fun searchManga(query: String): List<UnifiedSearchResult> = coroutineScope {
        mangaSources.map { source ->
            async {
                try { source.searchManga(query) }
                catch (e: Exception) {
                    println("[Manga Search] ${source.sourceName} failed silently: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()
            .flatten()
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Popular / Trending — Fetch home screen content for all three tabs
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun fetchPopularAll(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        val novelTasks = sources.map { source ->
            async {
                try { source.fetchPopular(page) }
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

        val novels = (novelTasks.awaitAll().flatten() + knownNovelFallbacks(""))
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .rankedNovelResults("")
        val mangas = mangaTasks.awaitAll().flatten()
        val animes = animeTask.await()

        (novels + mangas + animes)
            .distinctBy { it.title.lowercase().trim() }
            .filter { it.title.isNotEmpty() }
    }

    /** Fetch currently airing anime for the Anime tab home feed */
    suspend fun fetchCurrentlyAiring(page: Int = 1): List<AnimeResult> {
        val aniList = aniListSource.fetchCurrentlyAiring(page = page, perPage = 24)
        return aniList.ifEmpty { tmdbSource.fetchAnimeFallback(page) }
    }

    /** Fetch trending anime */
    suspend fun fetchTrendingAnime(page: Int = 1): List<AnimeResult> {
        val aniList = aniListSource.fetchTrending(page = page, perPage = 24)
        return aniList.ifEmpty { tmdbSource.fetchAnimeFallback(page) }
    }

    /** Search anime only */
    suspend fun searchAnime(query: String): List<AnimeResult> {
        val aniList = aniListSource.search(query)
        return aniList.ifEmpty { tmdbSource.searchAnimeFallback(query) }
    }

    suspend fun fetchVideo(category: VideoCategory, page: Int = 1): List<UnifiedSearchResult> =
        tmdbSource.fetchVideo(category, page)

    suspend fun searchVideo(category: VideoCategory, query: String, page: Int = 1): List<UnifiedSearchResult> =
        tmdbSource.searchVideo(category, query, page)

    /**
     * Fetch episode list for a given anime title.
     * Tries Anineko first, falls back to AnimePahe.
     */
    suspend fun fetchEpisodes(
        animeTitleQuery: String,
        episodeCount: Int = 0,
        alternateQueries: List<String> = emptyList()
    ): List<AnimeEpisode> {
        val queries = (listOf(animeTitleQuery) + alternateQueries)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        for (query in queries) {
            val aninekoEpisodes = aninekoScraper.fetchEpisodes(query)
                .distinctBy { it.url }
                .sortedByDescending { it.episodeNumber }
            if (aninekoEpisodes.isNotEmpty()) return aninekoEpisodes

            val animePaheEpisodes = animePaheScraper.fetchEpisodes(query)
                .distinctBy { it.url }
                .sortedByDescending { it.episodeNumber }
            if (animePaheEpisodes.isNotEmpty()) return animePaheEpisodes
        }

        return aninekoScraper.fallbackEpisodes(queries.firstOrNull().orEmpty(), episodeCount)
    }

    /**
     * Extract a streaming .m3u8 URL for a specific episode page.
     * Tries Anineko first, falls back to AnimePahe.
     */
    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        if (episodePageUrl.contains("animepahe", ignoreCase = true)) {
            return animePaheScraper.extractStreamUrl(episodePageUrl)
                ?: aninekoScraper.extractStreamUrl(episodePageUrl)
        }
        return aninekoScraper.extractStreamUrl(episodePageUrl)
            ?: animePaheScraper.extractStreamUrl(episodePageUrl)
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
    KnownNovelEntry(
        title = "My Vampire System",
        sourceName = "FreeWebNovel",
        detailPageUrl = "https://freewebnovel.com/novel/my-vampire-system",
        genre = "Fantasy, System, Action"
    ),
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
        title = "Martial Peak",
        sourceName = "BoxNovel",
        detailPageUrl = "https://boxnovel.com/novel/martial-peak/",
        genre = "Martial Arts, Xuanhuan, Action"
    ),
    KnownNovelEntry(
        title = "Lord of the Mysteries",
        sourceName = "LightNovelPub",
        detailPageUrl = "https://lightnovelpub.me/book/lord-of-the-mysteries",
        genre = "Mystery, Fantasy, Supernatural"
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

private fun UnifiedSearchResult.novelSearchScore(query: String): Int {
    val q = query.normalizeForNovelSearch()
    val title = title.normalizeForNovelSearch()
    val sourceBoost = when (sourceName.lowercase()) {
        "webnovel api" -> 900
        "wuxiaworld" -> 850
        "freewebnovel" -> 800
        "lightnovelpub" -> 750
        "boxnovel" -> 700
        "royalroad" -> 250
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
    val knownWebNovelIntent = listOf("vampire", "system", "renegade", "immortal", "will eternal", "martial", "mysteries", "xianxia", "wuxia", "xuanhuan")
        .any { q.contains(it) }
    val intentBoost = if (knownWebNovelIntent && sourceName != "RoyalRoad") 450 else 0
    return titleScore + sourceBoost + intentBoost
}

private fun String.normalizeForNovelSearch(): String =
    lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
