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
        "Nebula",
        1,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    ),
    VIDSRC_TO(
        "Quasar",
        2,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.to/embed/movie/$id"
            else "https://vidsrc.to/embed/tv/$id/$s/$e"
        }
    ),
    AUTOEMBED(
        "Pulsar",
        3,
        { id, type, s, e ->
            if (type == "movie") "https://autoembed.co/movie/tmdb/$id"
            else "https://autoembed.co/tv/tmdb/$id-$s-$e"
        }
    ),
    TWO_EMBED_ONLINE(
        "Comet",
        4,
        { id, type, s, e ->
            if (type == "movie") "https://www.2embed.online/embed/movie/$id"
            else "https://www.2embed.online/embed/tv/$id/$s/$e"
        }
    ),
    VIDSRC_SBS(
        "Astra",
        5,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.sbs/embed/movie/$id"
            else "https://vidsrc.sbs/embed/tv/$id/$s/$e"
        }
    ),
    NONTONGO(
        "Orion",
        6,
        { id, type, s, e ->
            if (type == "movie") "https://www.nontongo.win/embed/movie/$id"
            else "https://www.nontongo.win/embed/tv/$id/$s/$e"
        }
    ),
    MULTI_EMBED(
        "Lyra",
        7,
        { id, type, s, e ->
            if (type == "movie") "https://multiembed.mov/?video_id=$id&tmdb=1"
            else "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e"
        }
    ),
    VIDSRC_NET(
        "Vega",
        8,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.net/embed/movie?tmdb=$id"
            else "https://vidsrc.net/embed/tv?tmdb=$id&season=$s&episode=$e"
        }
    ),
    SMASHY(
        "Sirius",
        9,
        { id, type, s, e ->
            if (type == "movie") "https://embed.smashystream.com/playere.php?tmdb=$id"
            else "https://embed.smashystream.com/playere.php?tmdb=$id&season=$s&ep=$e"
        }
    ),
    CINEPRO(
        "CinePro",
        10,
        { id, type, s, e ->
            if (type == "movie") "https://cinepro-core-esmh.onrender.com/v1/movies/$id"
            else "https://cinepro-core-esmh.onrender.com/v1/tv/$id/seasons/$s/episodes/$e"
        }
    ),
    VIDLINK_EXO(
        "Eclipse",
        11,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    ),
    ANINEKO(
        "Kitsune",
        12,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    );

    /** Raw provider host behind this chip (e.g. "VidSrc.sbs"). */
    val providerName: String
        get() = when (this) {
            VIDLINK -> "VidLink"
            VIDSRC_TO -> "VidSrc.to"
            AUTOEMBED -> "AutoEmbed"
            TWO_EMBED_ONLINE -> "2Embed.online"
            VIDSRC_SBS -> "VidSrc.sbs"
            NONTONGO -> "Nontongo"
            MULTI_EMBED -> "MultiEmbed"
            VIDSRC_NET -> "VidSrc.net"
            SMASHY -> "SmashyStream"
            CINEPRO -> "CinePro"
            VIDLINK_EXO -> "VidLink (ExoPlayer)"
            ANINEKO -> "AniNeko"
        }

    companion object {
        /** All servers in display order */
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }

        /** Curated movie/TV selector chips shown in the UI. */
        val MOVIE_SELECTOR = listOf(VIDLINK, VIDSRC_TO, AUTOEMBED, TWO_EMBED_ONLINE, VIDSRC_SBS)

        /** WebView servers that load the embed directly */
        val WEBVIEW_SERVERS = setOf(VIDLINK, VIDSRC_TO, AUTOEMBED, TWO_EMBED_ONLINE, VIDSRC_SBS, NONTONGO, MULTI_EMBED, VIDSRC_NET, SMASHY)

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
    MOVIE_SERVER_1("Nebula", "VidLink", 1),
    MOVIE_SERVER_2("Quasar", "VidSrc.to", 2),
    ANIME_SERVER_5("Kitsune", "AniNeko", 3, anivexaProviderKey = "anineko"),
    ANIME_SERVER_3("Shogun", "AniKoto", 4, anivexaProviderKey = "anikoto"),
    ANIMEXIN("Loong", "AnimeXin", 5, isScraper = true, scraperKey = "animexin"),
    VIDSRC_SBS("Astra", "VidSrc.sbs", 6);

    val isAnivexa: Boolean get() = anivexaProviderKey != null

    companion object {
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }

        /** Curated donghua selector chips shown in the UI. */
        val DONGHUA_SELECTOR = listOf(
            MOVIE_SERVER_1, MOVIE_SERVER_2, ANIME_SERVER_5, ANIME_SERVER_3, ANIMEXIN, VIDSRC_SBS
        )
    }
}

fun DonghuaServer.toStreamServer(): StreamServer? = when (this) {
    DonghuaServer.MOVIE_SERVER_1 -> StreamServer.VIDLINK
    DonghuaServer.MOVIE_SERVER_2 -> StreamServer.VIDSRC_TO
    DonghuaServer.VIDSRC_SBS -> StreamServer.VIDSRC_SBS
    else -> null
}

fun DonghuaServer.toAnimeServer(): AnimeServer? = when (this) {
    DonghuaServer.ANIME_SERVER_5 -> AnimeServer.ANINEKO
    DonghuaServer.ANIME_SERVER_3 -> AnimeServer.ANIKOTO
    else -> null
}

/**
 * Anime-only servers — 20 servers. The curated UI subset lives in
 * `AnimeServer.ANIME_SELECTOR`.
 */
enum class AnimeServer(
    val displayName: String,
    val providerName: String,
    val usesTmdbEpisodes: Boolean,
    val serverOrder: Int,
    val anivexaProviderKey: String?,
    val clientScraperKey: String? = null
) {
    MKISSA("Kaiju", "MKissa", false, 1, "mkissa"),
    REANIME("Sakura", "Reanime", false, 2, "reanime"),
    ANIKOTO("Shogun", "AniKoto", false, 3, "anikoto"),
    ANIMEGG("Ronin", "AnimeGG", false, 4, "animegg"),
    ANINEKO("Kitsune", "AniNeko", false, 5, "anineko"),
    ANIDBAPP("Sensei", "AniDB App", false, 6, "anidbapp"),
    TWO_DHIVE("Torii", "2DHive", false, 7, "2dhive"),
    ANIMENOSUB("Neko", "AnimeNoSub", false, 8, "animenosub"),
    ANIZONE("Zen", "AniZone", false, 9, "anizone"),
    ANIBD("Bushido", "AniBD", false, 10, "anibd"),
    SENSHI("Samurai", "Senshi", false, 11, "senshi"),
    KAA("Kaze", "KickAssAnime", false, 12, "kaa"),
    ANIMEDUNYA("Tanuki", "AnimeDunya", false, 13, "animedunya"),
    ANIMEHEAVEN("Tenjin", "AnimeHeaven", false, 14, null, "animeheaven"),
    ANIMEPAHE("Raijin", "AnimePahe", false, 15, null, "animepahe"),
    ANIDAO("Susanoo", "AniDao", false, 16, null, "anidao"),
    VIDLINK("Nebula", "VidLink", true, 17, null),
    VIDSRC_TO("Quasar", "VidSrc.to", true, 18, null),
    AUTOEMBED("Pulsar", "AutoEmbed", true, 19, null),
    VIDSRC_SBS("Astra", "VidSrc.sbs", true, 20, null);

    val isAnivexa: Boolean get() = anivexaProviderKey != null
    val usesClientScraper: Boolean get() = clientScraperKey != null

    companion object {
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }

        /**
         * Curated anime selector chips shown in the UI (order = preference).
         * Anivexa providers first (AniNeko, AnimeGG, Reanime, MKissa), then the
         * TMDB-embed hosts (VidLink, VidSrc.to, VidSrc.sbs).
         */
        val ANIME_SELECTOR = listOf(ANINEKO, ANIMEGG, REANIME, MKISSA, VIDLINK, VIDSRC_TO, VIDSRC_SBS)
    }
}

/** TMDB-embed anime servers map to their StreamServer equivalents. */
fun AnimeServer.toStreamServer(): StreamServer? = when (this) {
    AnimeServer.VIDLINK -> StreamServer.VIDLINK
    AnimeServer.VIDSRC_TO -> StreamServer.VIDSRC_TO
    AnimeServer.AUTOEMBED -> StreamServer.AUTOEMBED
    AnimeServer.VIDSRC_SBS -> StreamServer.VIDSRC_SBS
    else -> null
}

/** Convert a StreamServer into the anime server slot. */
fun StreamServer.toAnimeServer(): AnimeServer? = when (this) {
    StreamServer.VIDLINK -> AnimeServer.VIDLINK
    StreamServer.VIDSRC_TO -> AnimeServer.VIDSRC_TO
    StreamServer.AUTOEMBED -> AnimeServer.AUTOEMBED
    StreamServer.VIDSRC_SBS -> AnimeServer.VIDSRC_SBS
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
