package com.alexleoreeves.novelapp.data

/**
 * Streaming servers available for TMDB-based video playback.
 *
 * Servers 1-4 use a visible WebView (MaServerPlayerScreen) that loads
 * the embed URL directly — this handles anti-bot challenges and WASM.
 *
 * Server 5 (VIDLINK_EXO) uses the same VidLink embed URL but tries to
 * scrape a direct .m3u8 stream via a hidden WebView and plays it in
 * ExoPlayer (AnimePlayerScreen). May be blocked by WASM on some content.
 */
enum class StreamServer(
    val displayName: String,
    val serverOrder: Int,
    val buildEmbedUrl: (tmdbId: String, type: String, season: String, episode: String) -> String
) {
    VIDLINK(
        "Server 1",
        1,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    ),
    VIDSRC_TO(
        "Server 2 (VidSrc.to)",
        2,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.to/embed/movie/$id"
            else "https://vidsrc.to/embed/tv/$id/$s/$e"
        }
    ),
    AUTOEMBED(
        "Server 3 (AutoEmbed)",
        3,
        { id, type, s, e ->
            if (type == "movie") "https://autoembed.co/movie/tmdb/$id"
            else "https://autoembed.co/tv/tmdb/$id-$s-$e"
        }
    ),
    TWO_EMBED_ONLINE(
        "Server 4 (2Embed.online)",
        4,
        { id, type, s, e ->
            if (type == "movie") "https://www.2embed.online/embed/movie/$id"
            else "https://www.2embed.online/embed/tv/$id/$s/$e"
        }
    ),
    NONTONGO(
        "Server 5 (Nontongo)",
        5,
        { id, type, s, e ->
            if (type == "movie") "https://www.nontongo.win/embed/movie/$id"
            else "https://www.nontongo.win/embed/tv/$id/$s/$e"
        }
    ),
    MULTI_EMBED(
        "Server 6 (MultiEmbed)",
        6,
        { id, type, s, e ->
            if (type == "movie") "https://multiembed.mov/?video_id=$id&tmdb=1"
            else "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e"
        }
    ),
    VIDSRC_NET(
        "Server 7 (VidSrc Net)",
        7,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.net/embed/movie?tmdb=$id"
            else "https://vidsrc.net/embed/tv?tmdb=$id&season=$s&episode=$e"
        }
    ),
    SMASHY(
        "Server 8 (SmashyStream)",
        8,
        { id, type, s, e ->
            if (type == "movie") "https://embed.smashystream.com/playere.php?tmdb=$id"
            else "https://embed.smashystream.com/playere.php?tmdb=$id&season=$s&ep=$e"
        }
    ),
    CINEPRO(
        "Server 9 (CinePro)",
        9,
        { id, type, s, e ->
            if (type == "movie") "https://cinepro-core-esmh.onrender.com/v1/movies/$id"
            else "https://cinepro-core-esmh.onrender.com/v1/tv/$id/seasons/$s/episodes/$e"
        }
    ),
    VIDLINK_EXO(
        "Server 10 (ExoPlayer)",
        10,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    ),
    ANINEKO(
        "Server 11 (AniNeko)",
        11,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    );

    companion object {
        /** All servers in display order */
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }

        /** WebView servers that load the embed directly */
        val WEBVIEW_SERVERS = setOf(VIDLINK, VIDSRC_TO, AUTOEMBED, TWO_EMBED_ONLINE, NONTONGO, MULTI_EMBED, VIDSRC_NET, SMASHY)

        /** ExoPlayer servers that scrape the embed for a direct stream */
        val EXOPLAYER_SERVERS = setOf(VIDLINK_EXO)

        /** True when a StreamServer chip is the AniNeko (Anivexa) route. */
        fun isAninekoRoute(server: StreamServer?): Boolean = server == ANINEKO
    }
}

/**
 * Donghua-only servers.
 *
 * Movie Server 1/2 use TMDB-embed playback (VidLink / VidSrc.to).
 * Anime Server 5/3 use the Anivexa API (AniNeko / AniKoto providers).
 * AnimeXin is a dedicated Donghua scraper site.
 */
enum class DonghuaServer(
    val displayName: String,
    val providerName: String,
    val serverOrder: Int,
    val isScraper: Boolean = false,
    val scraperKey: String? = null,
    val anivexaProviderKey: String? = null
) {
    MOVIE_SERVER_1("Movie Server 1", "VidLink (TMDB)", 1),
    MOVIE_SERVER_2("Movie Server 2", "VidSrc.to (TMDB)", 2),
    ANIME_SERVER_5("Anime Server 5", "AniNeko", 3, anivexaProviderKey = "anineko"),
    ANIME_SERVER_3("Anime Server 3", "AniKoto", 4, anivexaProviderKey = "anikoto"),
    ANIMEXIN("AnimeXin", "AnimeXin", 5, isScraper = true, scraperKey = "animexin");

    val isAnivexa: Boolean get() = anivexaProviderKey != null

    companion object {
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }
    }
}

fun DonghuaServer.toStreamServer(): StreamServer? = when (this) {
    DonghuaServer.MOVIE_SERVER_1 -> StreamServer.VIDLINK
    DonghuaServer.MOVIE_SERVER_2 -> StreamServer.VIDSRC_TO
    else -> null
}

fun DonghuaServer.toAnimeServer(): AnimeServer? = when (this) {
    DonghuaServer.ANIME_SERVER_5 -> AnimeServer.ANINEKO
    DonghuaServer.ANIME_SERVER_3 -> AnimeServer.ANIKOTO
    else -> null
}

/**
 * Anime-only servers — 19 servers.
 */
enum class AnimeServer(
    val displayName: String,
    val providerName: String,
    val usesTmdbEpisodes: Boolean,
    val serverOrder: Int,
    val anivexaProviderKey: String?,
    val clientScraperKey: String? = null
) {
    MKISSA("Server 1", "MKissa", false, 1, "mkissa"),
    REANIME("Server 2", "Reanime", false, 2, "reanime"),
    ANIKOTO("Server 3", "AniKoto", false, 3, "anikoto"),
    ANIMEGG("Server 4", "AnimeGG", false, 4, "animegg"),
    ANINEKO("Server 5", "AniNeko", false, 5, "anineko"),
    ANIDBAPP("Server 6", "AniDB App", false, 6, "anidbapp"),
    TWO_DHIVE("Server 7", "2DHive", false, 7, "2dhive"),
    ANIMENOSUB("Server 8", "AnimeNoSub", false, 8, "animenosub"),
    ANIZONE("Server 9", "AniZone", false, 9, "anizone"),
    ANIBD("Server 10", "AniBD", false, 10, "anibd"),
    SENSHI("Server 11", "Senshi", false, 11, "senshi"),
    KAA("Server 12", "KickAssAnime", false, 12, "kaa"),
    ANIMEDUNYA("Server 13", "AnimeDunya", false, 13, "animedunya"),
    ANIMEHEAVEN("Server 14", "AnimeHeaven", false, 14, null, "animeheaven"),
    ANIMEPAHE("Server 15", "AnimePahe", false, 15, null, "animepahe"),
    ANIDAO("Server 16", "AniDao", false, 16, null, "anidao"),
    VIDLINK("Server 17", "VidLink", true, 17, null),
    VIDSRC_TO("Server 18", "VidSrc.to", true, 18, null),
    AUTOEMBED("Server 19", "AutoEmbed", true, 19, null);

    val isAnivexa: Boolean get() = anivexaProviderKey != null
    val usesClientScraper: Boolean get() = clientScraperKey != null

    companion object {
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }
    }
}

/** TMDB-embed anime servers map to their StreamServer equivalents. */
fun AnimeServer.toStreamServer(): StreamServer? = when (this) {
    AnimeServer.VIDLINK -> StreamServer.VIDLINK
    AnimeServer.VIDSRC_TO -> StreamServer.VIDSRC_TO
    AnimeServer.AUTOEMBED -> StreamServer.AUTOEMBED
    else -> null
}

/** Convert a StreamServer into the anime server slot. */
fun StreamServer.toAnimeServer(): AnimeServer? = when (this) {
    StreamServer.VIDLINK -> AnimeServer.VIDLINK
    StreamServer.VIDSRC_TO -> AnimeServer.VIDSRC_TO
    StreamServer.AUTOEMBED -> AnimeServer.AUTOEMBED
    else -> null
}

/**
 * Build an embed URL for the given server, extracting parameters from
 * an existing embed URL or TMDB marker.
 */
fun buildEmbedUrlForServer(vidLinkUrl: String, server: StreamServer): String {
    val cleanUrl = vidLinkUrl.trim()
    val tmdbEpisodeMarkerMatch = Regex("""^tmdb-episode://(\d+)/(\d+)/(\d+)$""").find(cleanUrl)
    val tmdbMovieMarkerMatch2 = Regex("""^tmdb-movie://(\d+)$""").find(cleanUrl)
    val tmdbUriTvMatch = Regex("""^tmdb://tv/(\d+)(?:/(\d+)/(\d+))?$""").find(cleanUrl)
    val tmdbUriMovieMatch = Regex("""^tmdb://movie/(\d+)$""").find(cleanUrl)
    val movieMatch = Regex("""vidlink\.pro/movie/(\d+)""").find(cleanUrl)
    val tvMatch = Regex("""vidlink\.pro/tv/(\d+)/(\d+)/(\d+)""").find(cleanUrl)
    val tmdbTvMarkerMatch = Regex("""^(?:tmdb|tv):(\d+):(\d+):(\d+)$""").find(cleanUrl)
    val tmdbMovieMarkerMatch = Regex("""^(?:tmdb|movie):(\d+)$""").find(cleanUrl)
    val twoEmbedMovieMatch = Regex("""2embed\.(?:skin|online|cc)/embed/movie/([^/?&]+)""").find(cleanUrl)
    val twoEmbedTvMatch = Regex("""2embed\.(?:skin|online|cc)/embed/tv/([^/?&]+)/(\d+)/(\d+)""").find(cleanUrl)
    val autoembedMovieMatch = Regex("""autoembed\.(?:co|app|cc)/movie/tmdb/(\d+)""").find(cleanUrl)
    val autoembedTvMatch = Regex("""autoembed\.(?:co|app|cc)/tv/tmdb/(\d+)-(\d+)-(\d+)""").find(cleanUrl)

    return if (tmdbEpisodeMarkerMatch != null) {
        val id = tmdbEpisodeMarkerMatch.groupValues[1]
        val season = tmdbEpisodeMarkerMatch.groupValues[2]
        val episode = tmdbEpisodeMarkerMatch.groupValues[3]
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (tmdbMovieMarkerMatch2 != null) {
        val id = tmdbMovieMarkerMatch2.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else if (tmdbUriTvMatch != null) {
        val id = tmdbUriTvMatch.groupValues[1]
        val season = tmdbUriTvMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
        val episode = tmdbUriTvMatch.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "1"
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (tmdbUriMovieMatch != null) {
        val id = tmdbUriMovieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else if (tvMatch != null) {
        val id = tvMatch.groupValues[1]
        val season = tvMatch.groupValues[2]
        val episode = tvMatch.groupValues[3]
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (tmdbTvMarkerMatch != null) {
        val id = tmdbTvMarkerMatch.groupValues[1]
        val season = tmdbTvMarkerMatch.groupValues[2]
        val episode = tmdbTvMarkerMatch.groupValues[3]
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (twoEmbedTvMatch != null) {
        val id = twoEmbedTvMatch.groupValues[1]
        val season = twoEmbedTvMatch.groupValues[2]
        val episode = twoEmbedTvMatch.groupValues[3]
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (autoembedTvMatch != null) {
        val id = autoembedTvMatch.groupValues[1]
        val season = autoembedTvMatch.groupValues[2]
        val episode = autoembedTvMatch.groupValues[3]
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (movieMatch != null) {
        val id = movieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else if (tmdbMovieMarkerMatch != null) {
        val id = tmdbMovieMarkerMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else if (twoEmbedMovieMatch != null) {
        val id = twoEmbedMovieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else if (autoembedMovieMatch != null) {
        val id = autoembedMovieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else {
        cleanUrl
    }
}
