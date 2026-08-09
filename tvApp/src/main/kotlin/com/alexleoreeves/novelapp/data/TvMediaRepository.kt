package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount

class TvMediaRepository {
    private val httpClient = platformHttpClient()
    private val tmdbScraper = TMDBMovieScraper(httpClient)
    private val dramaScraper = DramaCoolScraper(httpClient)
    private val cartoonScraper = KimCartoonScraper(httpClient)
    private val wcoStreamScraper = WcoStreamScraper(httpClient)
    private val donghuaStreamScraper = DonghuaSiteScraper.donghuaStream(httpClient)
    private val aninekoScraper = AninekoScraper(httpClient)
    private val animePaheScraper = AnimePaheScraper(httpClient)
    private val consumetAnimeScraper = ConsumetAnimeScraper(httpClient)
    private val youtubeNollywoodScraper = YouTubeNollywoodScraper(httpClient)

    suspend fun fetchVideoEpisodes(item: UnifiedSearchResult): List<Chapter> {
        val kind = item.mediaKind.lowercase()
        val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)
        val isTmdb = item.detailPageUrl.startsWith("tmdb://")

        return try {
            val episodes = when {
                isDonghua -> donghuaStreamScraper.fetchEpisodes(
                    titleQuery = item.title,
                    alternateQueries = listOf(item.title.substringBefore(":")),
                    maxEpisodes = 300
                ).map { ep ->
                    Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber)
                }
                item.isAnime -> {
                    // Try Anineko first
                    val aninekoEps = aninekoScraper.fetchEpisodes(item.title, 300)
                    if (aninekoEps.isNotEmpty()) {
                        aninekoEps.map { ep -> Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber) }
                    } else {
                        // Fallback to Consumet/HiAnime
                        consumetAnimeScraper.fetchEpisodes("hianime", item.title, emptyList(), 300)
                            .map { ep -> Chapter(title = ep.title, url = ep.url, chapterNumber = ep.episodeNumber) }
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

            val isMovie = kind == "movie" || kind == "movies" || (item.isVideo && !item.isAnime && kind == "movie")
            val isEpisodic = item.isAnime || (item.isVideo && !isMovie)
            if (episodes.isEmpty() && isEpisodic) {
                if (item.detailPageUrl.startsWith("tmdb://")) {
                    val tmdbId = item.detailPageUrl.removePrefix("tmdb://").split("/").getOrNull(1) ?: ""
                    (1..24).map { epNum ->
                        Chapter(
                            title = "Episode $epNum",
                            url = "tmdb:$tmdbId:1:$epNum",
                            chapterNumber = epNum
                        )
                    }
                } else {
                    emptyList()
                }
            } else {
                episodes
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun resolveStreamUrl(
        item: UnifiedSearchResult,
        chapter: Chapter?,
        server: StreamServer?,
        donghuaServer: DonghuaServer?
    ): String? {
        val kind = item.mediaKind.lowercase()
        val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)
        val isTmdb = item.detailPageUrl.startsWith("tmdb://")
        
        // nollywood direct
        if (item.id.startsWith("youtube_nollywood_")) {
            val videoId = item.id.removePrefix("youtube_nollywood_")
            return youtubeNollywoodScraper.extractStreamUrl(videoId)
        }

        if (isDonghua && chapter != null) {
            val targetServer = donghuaServer ?: DonghuaServer.DONGHUA_STREAM
            return when (targetServer) {
                DonghuaServer.NONTONGO -> {
                    val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                    val detailType = detailParts.getOrNull(0).orEmpty()
                    val detailTmdbId = detailParts.getOrNull(1).orEmpty()
                    if (item.detailPageUrl.startsWith("tmdb://") && detailTmdbId.isNotBlank()) {
                        val urlParts = chapter.url.split(":")
                        val s = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
                        val e = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: chapter.chapterNumber.coerceAtLeast(1).toString()
                        if (detailType == "movie") "https://nontongo.win/embed/movie/$detailTmdbId"
                        else "https://nontongo.win/embed/tv/$detailTmdbId/$s/$e"
                    } else null
                }
                DonghuaServer.AUTOEMBED -> {
                    val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                    val detailType = detailParts.getOrNull(0).orEmpty()
                    val detailTmdbId = detailParts.getOrNull(1).orEmpty()
                    if (item.detailPageUrl.startsWith("tmdb://") && detailTmdbId.isNotBlank()) {
                        val urlParts = chapter.url.split(":")
                        val s = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
                        val e = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: chapter.chapterNumber.coerceAtLeast(1).toString()
                        if (detailType == "movie") "https://player.autoembed.cc/embed/movie/$detailTmdbId"
                        else "https://player.autoembed.cc/embed/tv/$detailTmdbId/$s/$e"
                    } else chapter.url
                }
                DonghuaServer.DONGHUA_STREAM -> chapter.url
                DonghuaServer.EMBEDSU -> {
                    val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                    val detailType = detailParts.getOrNull(0).orEmpty()
                    val detailTmdbId = detailParts.getOrNull(1).orEmpty()
                    if (item.detailPageUrl.startsWith("tmdb://") && detailTmdbId.isNotBlank()) {
                        val urlParts = chapter.url.split(":")
                        val s = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
                        val e = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: chapter.chapterNumber.coerceAtLeast(1).toString()
                        if (detailType == "movie") "https://embed.su/embed/movie/$detailTmdbId"
                        else "https://embed.su/embed/tv/$detailTmdbId/$s/$e"
                    } else chapter.url
                }
            }
        }

        if (item.isAnime && chapter != null) {
            val epUrl = chapter.url
            if (!epUrl.startsWith("tmdb:")) {
                if (ConsumetAnimeScraper.isConsumetEpisodeUrl(epUrl)) {
                    return consumetAnimeScraper.extractStreamUrl(epUrl)
                }
                return aninekoScraper.extractStreamUrl(epUrl) ?: animePaheScraper.extractStreamUrl(epUrl) ?: epUrl
            }
        }

        val tmdbParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
        val tmdbType = tmdbParts.getOrNull(0) ?: "movie"
        val tmdbId = tmdbParts.getOrNull(1) ?: ""

        val targetServer = server ?: StreamServer.VIDLINK

        // The Movies tab serves TMDB "tv" IDs (theatrical releases are catalogued
        // by TMDB as tv) with kind="movie" and NO episodes. Treat any movie-kind
        // item as a movie regardless of the tmdb:// type prefix, so "Watch Now"
        // builds a movie embed URL instead of falling through to null, which
        // surfaced as "Stream unavailable. Try another server."
        val isMovieKind = kind == "movie" || kind == "movies" ||
            (item.isVideo && !item.isAnime && !isDonghua)

        if (isTmdb) {
            if (tmdbType == "movie" || isMovieKind) {
                return targetServer.buildEmbedUrl(tmdbId, "movie", "1", "1")
            } else if (chapter != null) {
                val urlParts = chapter.url.split(":")
                val tvId = urlParts.getOrNull(1) ?: tmdbId
                val s = urlParts.getOrNull(2) ?: "1"
                val e = urlParts.getOrNull(3) ?: "1"
                return targetServer.buildEmbedUrl(tvId, "tv", s, e)
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
}
