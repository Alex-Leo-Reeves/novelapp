package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** CinePro Core instance used for Server 10 playback. */
private const val CINEPRO_BASE_URL = "https://cinepro-core-esmh.onrender.com"

class TvMediaRepository {
    /**
     * Retries a suspend resolution call up to [times] attempts with a short
     * backoff (800ms, 1600ms). Provider CDNs / Anivexa rate limits / Render
     * cold starts fail transiently — one shot surfaces "cannot find content"
     * even though the content is fine and loads on a later retry.
     */
    private suspend fun <T> retryNullable(times: Int = 3, block: suspend () -> T?): T? {
        var lastError: Throwable? = null
        repeat(times) { attempt ->
            try {
                val result = block()
                if (result != null) return result
            } catch (e: Throwable) {
                lastError = e
            }
            if (attempt < times - 1) {
                kotlinx.coroutines.delay(800L * (attempt + 1))
            }
        }
        return null
    }

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
    private val animeHeavenScraper = AnimeHeavenScraper(httpClient)
    private val aniDaoScraper = AniDaoScraper(httpClient)
    private val consumetAnimeScraper = ConsumetAnimeScraper(httpClient)
    private val anivexaApi = AnivexaApi(httpClient)
    private val youtubeNollywoodScraper = YouTubeNollywoodScraper(httpClient)

    /**
     * Resolved AniList IDs memoized by item id (fallback: title) so switching
     * between the 13 Anivexa providers, or binge-NEXT across episodes, reuses
     * the same AniList ID instead of re-running the slow backend title bridge
     * on every server switch.
     */
    private val anilistIdCache = mutableMapOf<String, String?>()

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
    suspend fun fetchVideoEpisodes(
        item: UnifiedSearchResult,
        animeServer: AnimeServer? = null,
        donghuaServer: DonghuaServer? = null
    ): List<Chapter> {
        val kind = item.mediaKind.lowercase()
        val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)
        val isTmdb = item.detailPageUrl.startsWith("tmdb://")

        return try {
            // When an Anivexa provider (Servers 1-13) or one of the Anivault
            // client-scraper servers (14-16) is selected, load that server's own
            // episode list — never the backend/TMDB chapters, whose numbered
            // markers can't resolve through these providers. This gate applies
            // to donghua too: a backend TMDB chapter list must NOT short-circuit
            // when an Anivexa/Anivault server is active for a donghua title.
            val useBackendForAnime = animeServer?.isAnivexa != true && animeServer?.usesClientScraper != true
            val backendChapters = if (isDonghua) {
                if (useBackendForAnime) {
                    fetchChapters(
                        kind.ifBlank { "movie" },
                        item.detailPageUrl.ifBlank { item.url },
                        item.title,
                        item.sourceName
                    )
                } else {
                    emptyList()
                }
            } else if (item.isAnime && useBackendForAnime || isTmdb && !item.isAnime) {
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
                isDonghua -> {
                    val effectiveDonghua = donghuaServer ?: DonghuaServer.DONGHUA_STREAM
                    when (effectiveDonghua) {
                        DonghuaServer.DONGHUA_STREAM -> {
                            val eps = donghuaStreamScraper.fetchEpisodes(item.title, maxEpisodes = 300)
                            if (eps.isNotEmpty()) eps.map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                            else animeXinScraper.fetchEpisodes(item.title, maxEpisodes = 300).map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                        }
                        DonghuaServer.LUCIFER_DONGHUA -> {
                            val eps = luciferDonghuaScraper.fetchEpisodes(item.title, maxEpisodes = 300)
                            if (eps.isNotEmpty()) eps.map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                            else animeXinScraper.fetchEpisodes(item.title, maxEpisodes = 300).map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                        }
                        DonghuaServer.ANIMEXIN -> {
                            val eps = animeXinScraper.fetchEpisodes(item.title, maxEpisodes = 300)
                            if (eps.isNotEmpty()) eps.map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                            else donghuaStreamScraper.fetchEpisodes(item.title, maxEpisodes = 300).map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                        }
                        DonghuaServer.VIDLINK, DonghuaServer.VIDSRC_TO, DonghuaServer.AUTOEMBED -> {
                            val tmdbEps = fetchTmdbChaptersForAnime(item)
                            if (tmdbEps.isNotEmpty()) tmdbEps
                            else donghuaStreamScraper.fetchEpisodes(item.title, maxEpisodes = 300).map { Chapter(title = it.title, url = it.url, chapterNumber = it.episodeNumber) }
                        }
                    }
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
                                // resolution degrades to VidLink (Server 17).
                                fetchTmdbChaptersForAnime(item)
                            }
                        }
                    } else if (effectiveAnimeServer.usesClientScraper) {
                        // Anivault trio (Servers 14-16: AnimeHeaven / AnimePahe /
                        // AniDao): episode lists are scraped DEVICE-SIDE so the
                        // request originates from the TV's residential IP — the repo
                        // owner's trick. Datacenter egress (Render) is blocked by
                        // these CDNs, which is why Servers 1-13 return 0 streams for
                        // popular anime like Dragon Ball.
                        val queries = listOf(
                            item.title,
                            item.title.substringBefore(":").trim(),
                            item.title.removeAnimeSeasonSuffixForTv()
                        ).filter { it.isNotBlank() }.distinctBy { it.lowercase() }

                        val providerKey = effectiveAnimeServer.clientScraperKey.orEmpty()
                        val clientEpisodes = queries.firstNotNullOfOrNull { query ->
                            when (providerKey) {
                                "animeheaven" -> animeHeavenScraper.fetchEpisodes(query, maxEpisodes = 300)
                                "animepahe" -> animePaheScraper.fetchEpisodes(query)
                                "anidao" -> aniDaoScraper.fetchEpisodes(query, maxEpisodes = 300)
                                else -> emptyList()
                            }.takeIf { it.isNotEmpty() }
                        }.orEmpty().map { ep ->
                            Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                        }
                        if (clientEpisodes.isNotEmpty()) {
                            clientEpisodes
                        } else {
                            // The site returned nothing for this server — fall back to
                            // TMDB chapters so the user still gets a playable list
                            // (resolution degrades to VidLink).
                            fetchTmdbChaptersForAnime(item)
                        }
                    } else {
                        // VIDLINK (Server 17) / VIDSRC_TO (Server 18, LAST): reload
                        // episodes from TMDB so the numbered markers resolve via the
                        // respective TMDB-embed server.
                        fetchTmdbChaptersForAnime(item)
                    }
                }
                isTmdb -> {
                    val parts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                    val tmdbType = parts.getOrNull(0) ?: "movie"
                    val tmdbId = parts.getOrNull(1) ?: ""
                    if (tmdbType == "tv") {
                        tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId).map { ep ->
                            Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber, seasonNumber = ep.seasonNumber)
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
                Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber, seasonNumber = ep.seasonNumber)
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
                    Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber, seasonNumber = ep.seasonNumber)
                }
            }
        }
        return emptyList()
    }

    /**
     * Resolve the AniList ID for an anime/donghua item.
     *  - `anilist_<digits>` ids (e.g. `anilist_151807`) return the id directly.
     *  - `anilist:{id}` detail URLs return the id directly.
     *  - `item.url` with `anilist:` prefix returns the id directly.
     *  - `animeResult` (AniList-sourced search results) provides it as a fallback.
     *  - TMDB-sourced titles bridge through the backend AniList title search.
     *
     * Resolved IDs are memoized per item so switching between the 13 Anivexa
     * providers (or binge-NEXT across episodes) reuses the same AniList ID
     * instead of re-running the slow backend title bridge on every switch.
     */
    private suspend fun resolveAnilistId(item: UnifiedSearchResult): String? {
        val cacheKey = item.id.ifBlank { item.title.lowercase() }
        anilistIdCache[cacheKey]?.let { return it }

        val resolved = resolveAnilistIdUncached(item)
        anilistIdCache[cacheKey] = resolved
        return resolved
    }

    private suspend fun resolveAnilistIdUncached(item: UnifiedSearchResult): String? {
        // Fast path 1: `anilist_<digits>` item id (e.g. fetched from the
        // Android/backend AniList feed).
        val idPrefix = "anilist_"
        if (item.id.startsWith(idPrefix)) {
            val digits = item.id.removePrefix(idPrefix).trim()
            if (digits.isNotBlank() && digits.all { it.isDigit() }) return digits
        }

        val detailUrl = item.detailPageUrl.ifBlank { item.url }
        if (detailUrl.startsWith("anilist:")) {
            val id = detailUrl.removePrefix("anilist:").trim()
            if (id.isNotBlank() && id.all { it.isDigit() }) return id
        }

        // Fast path 2: `item.url` (the convenience alias) carrying an anilist: ref.
        if (!item.url.startsWith("anilist:")) {
            val urlRef = item.url.trim()
            if (urlRef.startsWith("anilist:")) {
                val id = urlRef.removePrefix("anilist:").trim()
                if (id.isNotBlank() && id.all { it.isDigit() }) return id
            }
        }

        item.animeResult?.let {
            val directId = it.id.trim()
            if (directId.isNotBlank() && directId.all { c -> c.isDigit() }) return directId
        }

        // TMDB-sourced item (donghua feed, TMDB anime): the title bridge is the
        // only way to reach an AniList id. VERIFY the bridged id actually maps
        // back to the SAME TMDB title — the worker's /map route is authoritative.
        // If the mapped TMDB id differs, the bridge matched the WRONG show; we
        // reject the id so the 13 Anivexa providers never key off another show's
        // episode list (the mis-keyed-episodes bug). Non-TMDB items are trusted.
        val tmdbMatch = Regex("""^tmdb://(?:movie|tv)/(\d+)""").find(detailUrl)
        if (tmdbMatch != null) {
            val expectedTmdbId = tmdbMatch.groupValues[1]
            val bridged = runCatching { anivexaApi.searchAnilistId(item.title) }.getOrNull()
            if (bridged.isNullOrBlank()) return null
            val mappedTmdbId = anivexaApi.resolveTmdbIdForAnilist(bridged)
            val mappedMatch = mappedTmdbId
                ?.trim()
                ?.let { it.ifBlank { null } }
            if (mappedMatch != null && mappedMatch != expectedTmdbId) {
                println("[Anivexa] Rejected mis-matched AniList id $bridged for TMDB $expectedTmdbId (mapped $mappedMatch)")
                return null
            }
            return bridged
        }

        return runCatching { anivexaApi.searchAnilistId(item.title) }.getOrNull()
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

        // AniNeko as a normal StreamServer-row chip (Server 11): for NON-anime
        // series/movies/kdrama/cartoon, AniNeko resolves the episode page via
        // its device-side scraper first; if the title isn't on AniNeko, fall
        // back to the normal TMDB embed (VidLink-equivalent) so the chip
        // always has a working route. This replicates "Server 5 for Henry
        // Danger" the user verified — AniNeko hosts Western series too.
        if (!isDonghua && StreamServer.isAninekoRoute(targetServer)) {
            val aninekoStream = runCatching {
                val episodes = aninekoScraper.fetchEpisodes(item.title, maxEpisodes = 500)
                val targetEp = episodes.firstOrNull { ep ->
                    chapter == null || ep.episodeNumber == chapter.chapterNumber
                } ?: episodes.firstOrNull()
                targetEp?.let { aninekoScraper.extractStreamUrl(it.url) }
            }.getOrNull()
            if (!aninekoStream.isNullOrBlank()) return aninekoStream

            // Fallback: normal TMDB embed for this title (VidLink URL shape).
            val fallbackMarker = parseTmdbPlaybackMarker(chapter?.url, item.detailPageUrl, chapter?.chapterNumber)
            if (fallbackMarker != null) {
                return StreamServer.VIDLINK.buildEmbedUrl(fallbackMarker.tmdbId, fallbackMarker.mediaType, fallbackMarker.season, fallbackMarker.episode)
            }
            if (isTmdb && item.detailPageUrl.contains("/tv/")) {
                val parts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                val tmdbId = parts.getOrNull(1).orEmpty()
                val epNum = chapter?.chapterNumber?.coerceAtLeast(1)?.toString() ?: "1"
                return StreamServer.VIDLINK.buildEmbedUrl(tmdbId, "tv", "1", epNum)
            }
        }

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
            val effectiveDonghua = donghuaServer ?: DonghuaServer.DONGHUA_STREAM
            return when (effectiveDonghua) {
                DonghuaServer.DONGHUA_STREAM -> {
                    donghuaStreamScraper.resolveEpisodePlayerUrl(chapter.url) ?: chapter.url
                }
                DonghuaServer.LUCIFER_DONGHUA -> {
                    luciferDonghuaScraper.resolveEpisodePlayerUrl(chapter.url) ?: chapter.url
                }
                DonghuaServer.ANIMEXIN -> {
                    animeXinScraper.resolveEpisodePlayerUrl(chapter.url) ?: chapter.url
                }
                DonghuaServer.VIDLINK -> {
                    parseTmdbPlaybackMarker(chapter.url, item.detailPageUrl, chapter.chapterNumber)?.let { marker ->
                        StreamServer.VIDLINK.buildEmbedUrl(marker.tmdbId, marker.mediaType, marker.season, marker.episode)
                    } ?: StreamServer.VIDLINK.buildEmbedUrl(item.id, "tv", "1", chapter.chapterNumber.toString())
                }
                DonghuaServer.VIDSRC_TO -> {
                    parseTmdbPlaybackMarker(chapter.url, item.detailPageUrl, chapter.chapterNumber)?.let { marker ->
                        StreamServer.VIDSRC_TO.buildEmbedUrl(marker.tmdbId, marker.mediaType, marker.season, marker.episode)
                    } ?: StreamServer.VIDSRC_TO.buildEmbedUrl(item.id, "tv", "1", chapter.chapterNumber.toString())
                }
                DonghuaServer.AUTOEMBED -> {
                    parseTmdbPlaybackMarker(chapter.url, item.detailPageUrl, chapter.chapterNumber)?.let { marker ->
                        StreamServer.AUTOEMBED.buildEmbedUrl(marker.tmdbId, marker.mediaType, marker.season, marker.episode)
                    } ?: StreamServer.AUTOEMBED.buildEmbedUrl(item.id, "tv", "1", chapter.chapterNumber.toString())
                }
            }
        }

        if (item.isAnime && chapter != null) {
            val effectiveAnimeServer = animeServer ?: AnimeServer.ANINEKO
            if (effectiveAnimeServer.isAnivexa && AnivexaApi.isAnivexaEpisodeUrl(chapter.url)) {
                // Anivexa-API provider: resolve the direct HLS/MP4/embed via the
                // app backend. Direct URLs play in LibVLC (TvPlayerScreen); embed
                // pages open in the visible WebView (TvEmbedPlayerScreen).
                // Retried: provider CDNs rate-limit / cold-start transiently.
                return retryNullable { anivexaApi.resolveStream(chapter.url)?.url }
            }
            if (effectiveAnimeServer.usesClientScraper) {
                // Anivault trio (Servers 14-16: AnimeHeaven / AnimePahe / AniDao).
                // Resolution runs DEVICE-SIDE on the TV's residential IP — the repo
                // owner's trick. AnimeHeaven gate.php and AniDao watch-online pages
                // load fine in the visible WebView (TvEmbedPlayerScreen); AnimePahe
                // returns a direct .m3u8/.mp4 that plays in LibVLC (TvPlayerScreen),
                // falling back to the play page for the WebView when kwik embeds only.
                return retryNullable {
                    when (effectiveAnimeServer.clientScraperKey) {
                        "animeheaven" -> animeHeavenScraper.resolvePlayerUrl(chapter.url)
                        "anidao" -> aniDaoScraper.resolvePlayerUrl(chapter.url)
                        "animepahe" -> animePaheScraper.extractStreamUrl(chapter.url)
                            ?: chapter.url
                        else -> null
                    }
                }
            }
            // TMDB-embed anime servers (VIDLINK Server 17, VIDSRC_TO Server 18):
            // map the anime server to its StreamServer equivalent so the TMDB
            // block below uses the correct embed URL (not the default VidLink).
            val animeStreamServer = effectiveAnimeServer.toStreamServer()
            if (animeStreamServer != null) {
                val urlParts = chapter.url.split(":")
                val tvId = urlParts.getOrNull(1)?.ifBlank { null }
                    ?: item.detailPageUrl.removePrefix("tmdb://tv/").substringBefore("/").ifBlank { null }
                    ?: item.detailPageUrl.removePrefix("tmdb://movie/").substringBefore("/")
                val s = urlParts.getOrNull(2)?.ifBlank { null } ?: "1"
                val e = urlParts.getOrNull(3)?.ifBlank { null }
                    ?: chapter.chapterNumber.coerceAtLeast(1).toString()
                val mediaT = if (item.detailPageUrl.contains("/movie/")) "movie" else "tv"
                return animeStreamServer.buildEmbedUrl(tvId.orEmpty().ifBlank { item.id }, mediaT, s, e)
            }
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
