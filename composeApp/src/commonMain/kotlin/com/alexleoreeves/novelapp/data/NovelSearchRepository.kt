package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
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
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) {
            level = LogLevel.NONE
        }
    }

    /** Novel sources */
    private val sources: List<NovelSource> = listOf(
        WebNovelApiSource(httpClient, rapidApiKey, rapidApiHost),
        LightNovelPubSource(httpClient),
        BoxNovelSource(httpClient),
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
        MangaSeeScraper(httpClient),
        MangaFireScraper(httpClient)
    )

    /** Anime sources */
    internal val aniListSource = AniListSource(httpClient)
    internal val gogoAnimeScraper = GogoAnimeScraper(httpClient)
    internal val hianimeScraper = HianimeScraper(httpClient)

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

        val allNovels = novelTasks.awaitAll().flatten()
        val allManga = mangaTasks.awaitAll().flatten()
        val allAnime = animeTask.await()

        (allNovels + allManga + allAnime).distinctBy { it.title.lowercase().trim() }
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
                    if (source is MangaDexSource) source.searchManga("a") else emptyList()
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

        val novels = novelTasks.awaitAll().flatten()
        val mangas = mangaTasks.awaitAll().flatten()
        val animes = animeTask.await()

        (novels + mangas + animes)
            .distinctBy { it.title.lowercase().trim() }
            .filter { it.title.isNotEmpty() }
    }

    /** Fetch currently airing anime for the Anime tab home feed */
    suspend fun fetchCurrentlyAiring(page: Int = 1): List<AnimeResult> =
        aniListSource.fetchCurrentlyAiring(page = page, perPage = 24)

    /** Fetch trending anime */
    suspend fun fetchTrendingAnime(page: Int = 1): List<AnimeResult> =
        aniListSource.fetchTrending(page = page, perPage = 24)

    /** Search anime only */
    suspend fun searchAnime(query: String): List<AnimeResult> =
        aniListSource.search(query)

    /**
     * Fetch episode list for a given anime title.
     * Tries GogoAnime first, falls back to Hianime.
     */
    suspend fun fetchEpisodes(animeTitleQuery: String): List<AnimeEpisode> {
        val gogo = gogoAnimeScraper.fetchEpisodes(animeTitleQuery)
        if (gogo.isNotEmpty()) return gogo
        return hianimeScraper.fetchEpisodes(animeTitleQuery)
    }

    /**
     * Extract a streaming .m3u8 URL for a specific episode page.
     * Tries GogoAnime first, falls back to Hianime.
     */
    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        if (episodePageUrl.contains("gogoanime")) {
            return gogoAnimeScraper.extractStreamUrl(episodePageUrl)
                ?: hianimeScraper.extractStreamUrl(episodePageUrl)
        }
        return hianimeScraper.extractStreamUrl(episodePageUrl)
            ?: gogoAnimeScraper.extractStreamUrl(episodePageUrl)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Novel / Manga helpers (unchanged from before)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun fetchChapters(novelUrl: String, sourceName: String): List<Chapter> {
        val source = sources.find { it.sourceName == sourceName } ?: sources.first()
        return try { source.fetchChapters(novelUrl) } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchChapterText(chapterUrl: String, sourceName: String): String {
        val source = sources.find { it.sourceName == sourceName } ?: sources.first()
        return try { source.fetchChapterText(chapterUrl) }
        catch (e: Exception) { "Failed to load chapter. Please try again." }
    }

    suspend fun fetchMangaChapters(mangaUrl: String, sourceName: String): List<MangaChapter> {
        val scraper = mangaSources.find { it.sourceName == sourceName } ?: mangaSources.first()
        return try { scraper.fetchMangaChapters(mangaUrl) } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchMangaPages(chapterUrl: String, sourceName: String): List<String> {
        val scraper = mangaSources.find { it.sourceName == sourceName } ?: mangaSources.first()
        return try { scraper.fetchMangaPages(chapterUrl) } catch (e: Exception) { emptyList() }
    }
}
