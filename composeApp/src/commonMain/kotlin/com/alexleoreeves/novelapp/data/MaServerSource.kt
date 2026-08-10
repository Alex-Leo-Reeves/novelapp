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
    VIDSRC_CC(
        "Server 2 (VidSrc)",
        2,
        { id, type, s, e ->
            if (type == "movie") "https://vidsrc.cc/v2/embed/movie/$id"
            else "https://vidsrc.cc/v2/embed/tv/$id/$s/$e"
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
    );

    companion object {
        /** All servers in display order */
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }

        /** WebView servers that load the embed directly (Servers 1-4, 6-9) */
        val WEBVIEW_SERVERS = setOf(VIDLINK, VIDSRC_CC, NONTONGO, TWO_EMBED, MULTI_EMBED, AUTOEMBED, VIDSRC_NET, SMASHY)

        /** ExoPlayer servers that scrape the embed for a direct stream (Server 5) */
        val EXOPLAYER_SERVERS = setOf(VIDLINK_EXO)
    }
}

/**
 * Donghua-only servers. These are intentionally separate from [StreamServer]
 * so the movie/anime/K-drama/cartoon/classic/Nigerian tabs keep their normal
 * Server 1 and Server 2 behavior.
 */
enum class DonghuaServer(
    val displayName: String,
    val providerName: String,
    val serverOrder: Int
) {
    NONTONGO("Server 1", "Nontongo", 1),
    AUTOEMBED("Server 2", "AutoEmbed (Donghua & TMDB)", 2),
    DONGHUA_STREAM("Server 3", "DonghuaStream", 3),
    EMBEDSU("Server 4", "EmbedSu (Donghua & TMDB)", 4),
    LUCIFER_DONGHUA("Server 5", "LuciferDonghua", 5);

    companion object {
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }
    }
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
