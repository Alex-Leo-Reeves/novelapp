package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount

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
    private val youtubeNollywoodScraper = YouTubeNollywoodScraper(httpClient)

    suspend fun fetchVideoEpisodes(item: UnifiedSearchResult): List<Chapter> {
        val kind = item.mediaKind.lowercase()
        val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)
        val isTmdb = item.detailPageUrl.startsWith("tmdb://")

        return try {
            val backendChapters = if (item.isAnime || isTmdb) {
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

            episodes
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
        val targetServer = server ?: StreamServer.VIDLINK

        if (!isDonghua) {
            parseTmdbPlaybackMarker(chapter?.url, item.detailPageUrl, chapter?.chapterNumber)?.let { marker ->
                return targetServer.buildEmbedUrl(marker.tmdbId, marker.mediaType, marker.season, marker.episode)
            }
        }
        
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
                DonghuaServer.LUCIFER_DONGHUA -> {
                    luciferDonghuaScraper.resolveEpisodePlayerUrl(chapter.url)
                        ?: chapter.url
                }
                DonghuaServer.VIDSRC -> {
                    val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                    val detailType = detailParts.getOrNull(0).orEmpty()
                    val detailTmdbId = detailParts.getOrNull(1).orEmpty()
                    if (item.detailPageUrl.startsWith("tmdb://") && detailTmdbId.isNotBlank()) {
                        val urlParts = chapter.url.split(":")
                        val s = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
                        val e = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: chapter.chapterNumber.coerceAtLeast(1).toString()
                        if (detailType == "movie") "https://vidsrc.to/embed/movie/$detailTmdbId"
                        else "https://vidsrc.to/embed/tv/$detailTmdbId/$s/$e"
                    } else chapter.url
                }
                DonghuaServer.ANIMEXIN -> {
                    // AnimeXin: resolve episode page URL to embed player URL.
                    animeXinScraper.resolveEpisodePlayerUrl(chapter.url) ?: chapter.url
                }
            }
        }

        if (item.isAnime && chapter != null) {
            val epUrl = chapter.url
            if (!epUrl.startsWith("tmdb:") && !epUrl.startsWith("tmdb-", ignoreCase = true)) {
                if (ConsumetAnimeScraper.isConsumetEpisodeUrl(epUrl)) {
                    return consumetAnimeScraper.extractStreamUrl(epUrl)
                }
                return aninekoScraper.extractStreamUrl(epUrl) ?: animePaheScraper.extractStreamUrl(epUrl) ?: epUrl
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
                return targetServer.buildEmbedUrl(tvId, "tv", s, e)
            } else {
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
}

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
