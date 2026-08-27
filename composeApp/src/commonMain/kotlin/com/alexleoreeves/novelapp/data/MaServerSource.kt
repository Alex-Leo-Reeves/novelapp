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
    NONTONGO(
        "Server 3 (Nontongo)",
        3,
        { id, type, s, e ->
            if (type == "movie") "https://nontongo.win/embed/movie/$id"
            else "https://nontongo.win/embed/tv/$id/$s/$e"
        }
    ),
    TWO_EMBED(
        "Server 4 (2Embed)",
        4,
        { id, type, s, e ->
            if (type == "movie") "https://www.2embed.cc/embed/$id"
            else "https://www.2embed.cc/embedtv/$id&s=$s&e=$e"
        }
    ),
    VIDLINK_EXO(
        "Server 5 (ExoPlayer)",
        5,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
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
    AUTOEMBED(
        "Server 7 (AutoEmbed)",
        7,
        { id, type, s, e ->
            if (type == "movie") "https://autoembed.co/movie/tmdb/$id"
            else "https://autoembed.co/tv/tmdb/$id-$s-$e"
        }
    ),
    VIDSRC_NET(
        "Server 8 (VidSrc Net)",
        8,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.net/embed/movie?tmdb=$id"
            else "https://vidsrc.net/embed/tv?tmdb=$id&season=$s&episode=$e"
        }
    ),
    SMASHY(
        "Server 9 (SmashyStream)",
        9,
        { id, type, s, e ->
            if (type == "movie") "https://embed.smashystream.com/playere.php?tmdb=$id"
            else "https://embed.smashystream.com/playere.php?tmdb=$id&season=$s&ep=$e"
        }
    ),
    CINEPRO(
        "Server 10 (CinePro)",
        10,
        { id, type, s, e ->
            if (type == "movie") "https://cinepro-core-esmh.onrender.com/v1/movies/$id"
            else "https://cinepro-core-esmh.onrender.com/v1/tv/$id/seasons/$s/episodes/$e"
        }
    ),
    // LAST server in the normal movie/TV row. AniNeko is normally an anime-only
    // Anivexa provider, but it also hosts Western series (Henry Danger, FROM…)
    // and the user wants it reachable for series/movies/kdrama/cartoon too.
    // The episode list in the StreamServer row is TMDB-keyed, so the embed URL
    // maps to VidLink (Server 1) as the fallback; TvMediaRepository handles the
    // AniNeko-first resolution via the device-side title scraper.
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

        /** WebView servers that load the embed directly (Servers 1-4, 6-9) */
        val WEBVIEW_SERVERS = setOf(VIDLINK, VIDSRC_TO, NONTONGO, TWO_EMBED, MULTI_EMBED, AUTOEMBED, VIDSRC_NET, SMASHY)

        /** ExoPlayer servers that scrape the embed for a direct stream (Server 5) */
        val EXOPLAYER_SERVERS = setOf(VIDLINK_EXO)

        /** True when a StreamServer chip is the AniNeko (Anivexa) route. */
        fun isAninekoRoute(server: StreamServer?): Boolean = server == ANINEKO
    }
}

/**
 * Donghua-only servers. Intentionally separate from [StreamServer] so the
 * movie/anime/K-drama/cartoon/classic/Nigerian tabs keep their normal Server 1
 * and Server 2 behavior. Only AnimeXin is kept — the other TMDB-embed donghua
 * servers (Nontongo/AutoEmbed/DonghuaStream/EmbedSu/LuciferDonghua/VidSrc)
 * were removed per user request; donghua plays through AnimeXin's device-side
 * scraper, which extracts embed/direct streams that load in the WebView player
 * (same trick as AnimeHeaven/AniDao for anime).
 */
enum class DonghuaServer(
    val displayName: String,
    val providerName: String,
    val serverOrder: Int,
    val isScraper: Boolean = false,
    val scraperKey: String? = null
) {
    DONGHUA_STREAM("Server 1", "DonghuaStream", 1, isScraper = true, scraperKey = "donghuastream"),
    LUCIFER_DONGHUA("Server 2", "Lucifer Donghua", 2, isScraper = true, scraperKey = "luciferdonghua"),
    ANIMEXIN("Server 3", "AnimeXin", 3, isScraper = true, scraperKey = "animexin"),
    VIDLINK("Server 4", "VidLink (TMDB)", 4, isScraper = false, scraperKey = null),
    VIDSRC_TO("Server 5", "VidSrc.to (TMDB)", 5, isScraper = false, scraperKey = null),
    AUTOEMBED("Server 6", "AutoEmbed (TMDB)", 6, isScraper = false, scraperKey = null);

    companion object {
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }
    }
}

fun DonghuaServer.toStreamServer(): StreamServer? = when (this) {
    DonghuaServer.VIDLINK -> StreamServer.VIDLINK
    DonghuaServer.VIDSRC_TO -> StreamServer.VIDSRC_TO
    DonghuaServer.AUTOEMBED -> StreamServer.AUTOEMBED
    else -> null
}

/**
 * Anime-only servers — the content-aware anime selector.
 *
 * When the app detects anime content it shows ONLY this list, never the
 * generic [StreamServer] movie/TV list. Servers 1-13 are Anivexa-API providers
 * (backed by server/anivexa on the app backend, keyed by AniList ID). Servers
 * 14-16 (AnimeHeaven / AnimePahe / AniDao) are the three Anivault servers —
 * they are DESCRIBED in the Anivault-Scraper repo but here run through the
 * DEVICE-SIDE scrapers (AnimeScrapers.kt), which is exactly how the repo
 * owner's site works: the scraping runs on the user's residential IP inside
 * the app, so CDNs/Cloudflare that block datacenter egress (Render/Vercel
 * probes) let these play — Dragon Ball Super included. VIDLINK (Server 17)
 * is the TMDB-embed fallback and is intentionally the LAST server.
 *
 * [usesTmdbEpisodes] is true only for VIDLINK: its episode list is reloaded from
 * TMDB so the embed URL resolves via the tmdb marker → `StreamServer.buildEmbedUrl`
 * flow. The Anivexa providers use AniList ID-based episode lists instead.
 *
 * [isAnivexa] is true for the 13 backend providers (resolved via server/anivexa).
 * [usesClientScraper] is true for the 3 Anivault servers — resolved via the
 * device-side scrapers that run on the user's IP (AnimeHeavenScraper,
 * AnimePaheScraper, AniDaoScraper). [clientScraperKey] maps to the provider key
 * used by NovelSearchRepository.fetchEpisodesFromAnimeProvider /
 * resolveAnimeServerStream.
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
    // ── Anivault trio (device-side scrapers → residential IP, mirrors the
    //    repo owner's working play-by-page-in-browser trick) ──────────────
    ANIMEHEAVEN("Server 14", "AnimeHeaven", false, 14, null, "animeheaven"),
    ANIMEPAHE("Server 15", "AnimePahe", false, 15, null, "animepahe"),
    ANIDAO("Server 16", "AniDao", false, 16, null, "anidao"),
    VIDLINK("Server 17", "VidLink", true, 17, null),
    VIDSRC_TO("Server 18", "VidSrc.to", true, 18, null);

    /** True for the 13 Anivexa-API provider servers (backend, AniList-keyed). */
    val isAnivexa: Boolean get() = anivexaProviderKey != null

    /** True for the 3 Anivault servers resolved by device-side scrapers. */
    val usesClientScraper: Boolean get() = clientScraperKey != null

    companion object {
        /** All anime servers in display order: Anivexa first, then Anivault, VidLink last. */
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }
    }
}

/** TMDB-embed anime servers map to their StreamServer equivalents. */
fun AnimeServer.toStreamServer(): StreamServer? = when (this) {
    AnimeServer.VIDLINK -> StreamServer.VIDLINK
    AnimeServer.VIDSRC_TO -> StreamServer.VIDSRC_TO
    else -> null
}

/** Convert a StreamServer into the anime server slot (VidLink and VidSrc.to map). */
fun StreamServer.toAnimeServer(): AnimeServer? = when (this) {
    StreamServer.VIDLINK -> AnimeServer.VIDLINK
    StreamServer.VIDSRC_TO -> AnimeServer.VIDSRC_TO
    else -> null
}

/**
 * Build an embed URL for the given server, extracting parameters from
 * an existing VidLink embed URL (used by the episode playback flow).
 *
 * VidLink format: https://vidlink.pro/movie/{id} or https://vidlink.pro/tv/{id}/{s}/{e}
 * Other servers: same id/type/season/episode mapped to their own URL structure.
 */
fun buildEmbedUrlForServer(vidLinkUrl: String, server: StreamServer): String {
    val cleanUrl = vidLinkUrl.trim()
    val movieMatch = Regex("""vidlink\.pro/movie/(\d+)""").find(cleanUrl)
    val tvMatch = Regex("""vidlink\.pro/tv/(\d+)/(\d+)/(\d+)""").find(cleanUrl)
    val tmdbTvMarkerMatch = Regex("""^tv:(\d+):(\d+):(\d+)$""").find(cleanUrl)
    val twoEmbedMovieMatch = Regex("""2embed\.skin/embed/movie/(\d+)""").find(cleanUrl)
    val twoEmbedTvMatch = Regex("""2embed\.skin/embed/tv/(\d+)/(\d+)/(\d+)""").find(cleanUrl)

    return if (tvMatch != null) {
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
    } else if (movieMatch != null) {
        val id = movieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else if (twoEmbedMovieMatch != null) {
        val id = twoEmbedMovieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else {
        cleanUrl
    }
}
