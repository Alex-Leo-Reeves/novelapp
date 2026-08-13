package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** CinePro Core instance used for Server 10 playback. */
private const val CINEPRO_BASE_URL = "https://cinepro-core-esmh.onrender.com"

class TvMediaRepository {
    private val httpClient = platformHttpClient()
    private val tmdbScraper = TMDBMovieScraper(httpClient)
    private val dramaScraper = DramaCoolScraper(httpClient)
    private val cartoonScraper = KimCartoonScraper(httpClient)
    private val wcoStreamScraper = WcoStreamScraper(httpClient)
    private val donghuaStreamScraper = DonghuaSiteScraper.donghuaStream(httpClient)
    private val luciferDonghuaScraper = DonghuaSiteScraper.luciferDonghua(httpClient)
    private val animeXinScraper = AnimeXinScraper(httpClient)
    private val aninekoScraper = AninekoScraper(httpClient)
    private val animePaheScraper = AnimePaheScraper(httpClient)
    private val consumetAnimeScraper = ConsumetAnimeScraper(httpClient)
    private val anivexaApi = AnivexaApi(httpClient)
    private val youtubeNollywoodScraper = YouTubeNollywoodScraper(httpClient)

    /**
     * Fetch the episode list for a video title.
     *
     * When [animeServer] is provided for an anime title, the episode list source
     * follows the selected server:
     *  - AnimeHeaven (Server 1): its own episode grid → gate.php player pages.
     *  - HiAnime (Server 2): Consumet hianime provider.
     *  - TMDB-based servers (Server 3+): episodes reload from TMDB so the
     *    numbered markers (`tv:{id}:{s}:{e}`) resolve through StreamServer embeds.
     */
    suspend fun fetchVideoEpisodes(item: UnifiedSearchResult, animeServer: AnimeServer? = null): List<Chapter> {
        val kind = item.mediaKind.lowercase()
        val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)
        val isTmdb = item.detailPageUrl.startsWith("tmdb://")

        return try {
            // When an Anivexa provider (Servers 1-13) is selected, load its own
            // AniList-keyed episode list — never the backend/TMDB chapters, whose
            // numbered markers can't resolve through an Anivexa provider.
            val useBackendForAnime = animeServer?.isAnivexa != true
            val backendChapters = if (item.isAnime && useBackendForAnime || isTmdb && !item.isAnime) {
                fetchChapters(
                    if (item.isAnime) "anime" else kind.ifBlank { "movie" },
                    item.detailPageUrl.ifBlank { item.url },
                    item.title,
                    item.sourceName
                )
            } else {
                emptyList()
            }
            if (backendChapters.isNotEmpty()) return backendChapters

            val episodes = when {
                isDonghua -> donghuaStreamScraper.fetchEpisodes(
                    titleQuery = item.title,
                    alternateQueries = listOf(item.title.substringBefore(":")),
                    maxEpisodes = 300
                ).map { ep ->
                    Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                }
                item.isAnime -> {
                    val effectiveAnimeServer = animeServer ?: AnimeServer.ANINEKO
                    if (effectiveAnimeServer.isAnivexa) {
                        // Anivexa-API provider (Servers 1-13): episodes are keyed by
                        // AniList ID. Episode URLs are `anivexa://` markers resolved
                        // through the app backend, which mounts the Anivexa worker.
                        val anilistId = resolveAnilistId(item)
                        if (anilistId == null) {
                            // No AniList id → can't key an Anivexa provider. Fall back
                            // to TMDB chapters so the title still shows a playable
                            // episode list (resolution degrades to VidLink).
                            fetchTmdbChaptersForAnime(item)
                        } else {
                            val anivexaEpisodes = anivexaApi.fetchEpisodes(
                                provider = effectiveAnimeServer.anivexaProviderKey.orEmpty(),
                                anilistId = anilistId
                            ).map { ep ->
                                Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                            }
                            if (anivexaEpisodes.isNotEmpty()) {
                                anivexaEpisodes
                            } else {
                                // The provider returned nothing (site down / rate
                                // limited). Fall back to TMDB chapters so the user
                                // still gets an episode list instead of "no content";
                                // resolution degrades to VidLink (Server 14).
                                fetchTmdbChaptersForAnime(item)
                            }
                        }
                    } else {
                        // VIDLINK (Server 14, LAST): reload episodes from TMDB so the
                        // numbered markers (`tv:{id}:{s}:{e}`) resolve via VidLink.
                        fetchTmdbChaptersForAnime(item)
                    }
                }
                isTmdb -> {
                    val parts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                    val tmdbType = parts.getOrNull(0) ?: "movie"
                    val tmdbId = parts.getOrNull(1) ?: ""
                    if (tmdbType == "tv") {
                        tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId).map { ep ->
                            Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                        }
                    } else {
                        emptyList()
                    }
                }
                item.detailPageUrl.contains("dramacool", true) -> {
                    dramaScraper.fetchEpisodes(item.detailPageUrl).map { ep ->
                        Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                    }
                }
                item.detailPageUrl.contains("kimcartoon", true) -> {
                    cartoonScraper.fetchEpisodes(item.detailPageUrl).map { ep ->
                        Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                    }
                }
                item.sourceName == "WCOStream" || item.detailPageUrl.contains("wcostream", true) -> {
                    wcoStreamScraper.fetchEpisodes(item.detailPageUrl).map { ep ->
                        Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                    }
                }
                else -> emptyList()
            }

            episodes
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Search TMDB for an anime title and build a TMDB-based chapter list
     * (`tv:{id}:{s}:{e}` markers) for the TMDB-backed anime servers (Server 3+).
     */
    private suspend fun fetchTmdbChaptersForAnime(item: UnifiedSearchResult): List<Chapter> {
        // Exact tmdb:// URL already present → use it directly.
        if (item.detailPageUrl.startsWith("tmdb://tv/")) {
            val tvId = item.detailPageUrl.removePrefix("tmdb://tv/").substringBefore("/")
            return tmdbScraper.fetchTVSeasonsAndEpisodes(tvId).map { ep ->
                Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
            }
        }

        val queries = listOf(
            item.title,
            item.title.substringBefore(":").trim(),
            item.title.removeAnimeSeasonSuffixForTv()
        ).filter { it.isNotBlank() }.distinctBy { it.lowercase() }

        for (query in queries) {
            val results = runCatching { tmdbScraper.searchMultiPaged(query, 2) }.getOrElse { emptyList() }
            val best = results
                .filter { it.type == "TVSHOW" }
                .sortedWith(
                    compareByDescending<MediaResult> { tvAnimeTitleMatchScore(query, it.title) }
                        .thenBy { it.title.length }
                )
                .firstOrNull()
                ?.takeIf { tvAnimeTitleMatchScore(query, it.title) > 0 }
            if (best != null) {
                return tmdbScraper.fetchTVSeasonsAndEpisodes(best.id).map { ep ->
                    Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                }
            }
        }
        return emptyList()
    }

    /**
     * Resolve the AniList ID for an anime item.
     *  - `anilist:{id}` detail URLs return the id directly.
     *  - `animeResult` (AniList-sourced search results) provides it as a fallback.
     *  - TMDB-sourced anime bridge through the backend AniList title search.
     */
    private suspend fun resolveAnilistId(item: UnifiedSearchResult): String? {
        val detailUrl = item.detailPageUrl.ifBlank { item.url }
        if (detailUrl.startsWith("anilist:")) {
            val id = detailUrl.removePrefix("anilist:").trim()
            if (id.isNotBlank() && id.all { it.isDigit() }) return id
        }
        item.animeResult?.let {
            val directId = it.id.trim()
            if (directId.isNotBlank() && directId.all { c -> c.isDigit() }) return directId
        }
        return runCatching { anivexaApi.searchAnilistId(item.title) }.getOrNull()
    }

    /**
     * Build a TMDB-based embed page for the WebView donghua servers
     * (Nontongo / AutoEmbed / EmbedSu / VidSrc). Returns null when the item
     * has no resolvable tmdb://tv id, so the caller falls back to the raw
     * episode URL.
     */
    private fun resolveDonghuaTmdbEmbed(
        server: DonghuaServer,
        item: UnifiedSearchResult,
        chapter: Chapter
    ): String? {
        val tmdbId = item.detailPageUrl
            .removePrefix("tmdb://tv/")
            .substringBefore("/")
            .trim()
            .takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
            ?: return null
        val ep = chapter.chapterNumber.coerceAtLeast(1)
        return when (server) {
            DonghuaServer.NONTONGO -> "https://nontongo.win/embed/tv/$tmdbId/1/$ep"
            DonghuaServer.AUTOEMBED -> "https://autoembed.co/tv/tmdb/$tmdbId-1-$ep"
            DonghuaServer.EMBEDSU -> "https://embed.su/embed/tv/$tmdbId/1/$ep"
            DonghuaServer.VIDSRC -> "https://vidsrc.cc/v2/embed/tv/$tmdbId/1/$ep"
            else -> null
        }
    }

    suspend fun resolveStreamUrl(
        item: UnifiedSearchResult,
        chapter: Chapter?,
        server: StreamServer?,
        donghuaServer: DonghuaServer?,
        animeServer: AnimeServer? = null
    ): String? {
        val kind = item.mediaKind.lowercase()
        val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)
        val isTmdb = item.detailPageUrl.startsWith("tmdb://")
        val targetServer = server ?: StreamServer.VIDLINK

        if (!isDonghua) {
            parseTmdbPlaybackMarker(chapter?.url, item.detailPageUrl, chapter?.chapterNumber)?.let { marker ->
                // CinePro (Server 10) is an OMSS JSON API, NOT an embed page.
                // Fetch its sources and resolve the first real stream URL so
                // LibVLC can play it directly — otherwise the raw JSON text is
                // rendered in the WebView player.
                if (targetServer == StreamServer.CINEPRO) {
                    return resolveCineProStreamUrl(marker.mediaType, marker.tmdbId, marker.season, marker.episode)
                        ?: targetServer.buildEmbedUrl(marker.tmdbId, marker.mediaType, marker.season, marker.episode)
                }
                return targetServer.buildEmbedUrl(marker.tmdbId, marker.mediaType, marker.season, marker.episode)
            }
        }

        // nollywood direct
        if (item.id.startsWith("youtube_nollywood_")) {
            val videoId = item.id.removePrefix("youtube_nollywood_")
            return youtubeNollywoodScraper.extractStreamUrl(videoId)
        }

        if (isDonghua && chapter != null) {
            val targetServer = donghuaServer ?: DonghuaServer.ANIMEXIN
            return when (targetServer) {
                // AnimeXin (Server 7): resolve the episode page to its embed player URL.
                DonghuaServer.ANIMEXIN -> {
                    animeXinScraper.resolveEpisodePlayerUrl(chapter.url) ?: chapter.url
                }
                // DonghuaStream (Server 3): the episode URL is already a direct HLS
                // player page (or direct stream) — play as-is.
                DonghuaServer.DONGHUA_STREAM -> chapter.url
                // Lucifer Donghua (Server 5): episode pages are HTML players that
                // load fine in the visible WebView player.
                DonghuaServer.LUCIFER_DONGHUA -> chapter.url
                // WebView embed servers (Nontongo/AutoEmbed/EmbedSu/VidSrc): build
                // the TMDB-based embed page; fall back to the raw episode URL.
                DonghuaServer.NONTONGO, DonghuaServer.AUTOEMBED,
                DonghuaServer.EMBEDSU, DonghuaServer.VIDSRC ->
                    resolveDonghuaTmdbEmbed(targetServer, item, chapter) ?: chapter.url
            }
        }

        if (item.isAnime && chapter != null) {
            val effectiveAnimeServer = animeServer ?: AnimeServer.ANINEKO
            if (effectiveAnimeServer.isAnivexa && AnivexaApi.isAnivexaEpisodeUrl(chapter.url)) {
                // Anivexa-API provider: resolve the direct HLS/MP4/embed via the
                // app backend. Direct URLs play in LibVLC (TvPlayerScreen); embed
                // pages open in the visible WebView (TvEmbedPlayerScreen).
                return anivexaApi.resolveStream(chapter.url)?.url
            }
            // VIDLINK (Server 14, LAST): falls through — the numbered-marker logic
            // below resolves it via StreamServer.VIDLINK (server is null → default).
        }
        val tmdbParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
        val tmdbType = tmdbParts.getOrNull(0) ?: "movie"
        val tmdbId = tmdbParts.getOrNull(1) ?: ""

        val isMovieKind = tmdbType == "movie" || ((kind == "movie" || kind == "movies") && chapter == null)

        if (isTmdb || (chapter != null && chapter.url.startsWith("tmdb:"))) {
            val isTvShow = !isMovieKind
            if (isTvShow) {
                val urlParts = (chapter?.url ?: "").split(":")
                val tvId = urlParts.getOrNull(1)?.ifBlank { null } ?: tmdbId
                val s = urlParts.getOrNull(2)?.ifBlank { null } ?: "1"
                val e = urlParts.getOrNull(3)?.ifBlank { null } ?: chapter?.chapterNumber?.coerceAtLeast(1)?.toString() ?: "1"
                // CinePro (Server 10): resolve a real playable stream URL
                // instead of returning the raw OMSS JSON API endpoint.
                if (targetServer == StreamServer.CINEPRO) {
                    return resolveCineProStreamUrl("tv", tvId, s, e)
                        ?: targetServer.buildEmbedUrl(tvId, "tv", s, e)
                }
                return targetServer.buildEmbedUrl(tvId, "tv", s, e)
            } else {
                // CinePro (Server 10): resolve a real playable stream URL
                // instead of returning the raw OMSS JSON API endpoint.
                if (targetServer == StreamServer.CINEPRO) {
                    return resolveCineProStreamUrl("movie", tmdbId)
                        ?: targetServer.buildEmbedUrl(tmdbId, "movie", "1", "1")
                }
                return targetServer.buildEmbedUrl(tmdbId, "movie", "1", "1")
            }
        }

        if (chapter != null) {
            if (item.detailPageUrl.contains("dramacool", true)) {
                return dramaScraper.extractStreamUrl(chapter.url)
            }
            if (item.detailPageUrl.contains("kimcartoon", true)) {
                return cartoonScraper.extractStreamUrl(chapter.url)
            }
            if (item.sourceName == "WCOStream" || item.detailPageUrl.contains("wcostream", true)) {
                return wcoStreamScraper.extractStreamUrl(chapter.url)
            }
        }

        return chapter?.url
    }

    /**
     * Queries the CinePro Core OMSS API and returns the first directly
     * playable stream URL (rewriting localhost proxy URLs to the remote
     * instance). Returns null when no stream is available.
     */
    private suspend fun resolveCineProStreamUrl(
        mediaType: String,
        tmdbId: String,
        season: String = "1",
        episode: String = "1"
    ): String? = runCatching {
        val endpoint = if (mediaType == "movie") {
            "$CINEPRO_BASE_URL/v1/movies/$tmdbId"
        } else {
            "$CINEPRO_BASE_URL/v1/tv/$tmdbId/seasons/$season/episodes/$episode"
        }
        val response = httpClient.get("$endpoint?platform=web") {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        }
        val raw = response.bodyAsText()
        val root = Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(raw).jsonObject
        val sources = root["sources"]?.jsonArray ?: return@runCatching null
        for (source in sources) {
            val obj = source.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
            if (url.isBlank()) continue
            // CinePro wraps real streams in a proxy URL — rewrite localhost to
            // the remote instance so the proxy is reachable from the TV.
            if (url.contains("/v1/proxy?data=")) {
                val rewritten = when {
                    url.startsWith("http://localhost:10000") -> url.replace("http://localhost:10000", CINEPRO_BASE_URL)
                    url.startsWith("/v1/proxy") -> "$CINEPRO_BASE_URL$url"
                    else -> url
                }
                return@runCatching rewritten
            }
            if (url.startsWith("http")) return@runCatching url
        }
        null
    }.getOrNull()
}

/** Normalized title match score for picking the best TMDB TV show for an anime query. */
private fun tvAnimeTitleMatchScore(query: String, title: String): Int {
    val q = query.lowercase()
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
    val t = title.lowercase()
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
    if (q.isBlank() || t.isBlank()) return 0

    val sequelPenalty = when {
        Regex("""\b(season\s+[2-9]|\d+(st|nd|rd|th)\s+season|s[2-9])\b""").containsMatchIn(t) -> 800
        Regex("""\b(movie|special|ova|ona|recap)\b""").containsMatchIn(t) -> 900
        else -> 0
    }
    return when {
        t == q -> 10_000
        t.startsWith("$q ") -> 8_000 - sequelPenalty
        t.contains(q) -> 5_000 - sequelPenalty
        else -> 0
    }
}

/** Strips season/part/cour suffixes so an alternate TMDB search query finds the base show. */
private fun String.removeAnimeSeasonSuffixForTv(): String =
    lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(
            Regex("""\b(season\s+[2-9]|\d+(st|nd|rd|th)\s+season|part\s+\d+|cour\s+\d+|ova|ona|special|recap)\b.*$"""),
            ""
        )
        .trim()

private data class TmdbPlaybackMarker(
    val tmdbId: String,
    val mediaType: String,
    val season: String = "1",
    val episode: String = "1"
)

private fun parseTmdbPlaybackMarker(
    chapterUrl: String?,
    detailUrl: String,
    chapterNumber: Int?
): TmdbPlaybackMarker? {
    val cleanChapter = chapterUrl.orEmpty().trim()

    Regex("""^tmdb-episode://(\d+)/(\d+)/(\d+)$""")
        .find(cleanChapter)
        ?.let { match ->
            return TmdbPlaybackMarker(
                tmdbId = match.groupValues[1],
                mediaType = "tv",
                season = match.groupValues[2],
                episode = match.groupValues[3]
            )
        }

    Regex("""^tmdb-movie://(\d+)$""")
        .find(cleanChapter)
        ?.let { match ->
            return TmdbPlaybackMarker(tmdbId = match.groupValues[1], mediaType = "movie")
        }

    Regex("""^(?:tmdb|tv):(\d+):(\d+):(\d+)$""")
        .find(cleanChapter)
        ?.let { match ->
            return TmdbPlaybackMarker(
                tmdbId = match.groupValues[1],
                mediaType = "tv",
                season = match.groupValues[2],
                episode = match.groupValues[3]
            )
        }

    val detailMatch = Regex("""^tmdb://(movie|tv)/(\d+)""").find(detailUrl.trim()) ?: return null
    val mediaType = detailMatch.groupValues[1]
    val tmdbId = detailMatch.groupValues[2]
    if (mediaType == "movie") return TmdbPlaybackMarker(tmdbId = tmdbId, mediaType = "movie")

    val episode = chapterNumber?.coerceAtLeast(1)?.toString() ?: "1"
    return TmdbPlaybackMarker(tmdbId = tmdbId, mediaType = "tv", season = "1", episode = episode)
}
