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
    VIDSRC(
        "Server 2 (VidSrc)",
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
    CINEPRO(
        "Server 4 (Auto-Link)",
        4,
        { _, _, _, _ -> "" }
    ),
    VIDLINK_EXO(
        "Server 5 (ExoPlayer)",
        5,
        { id, type, s, e ->
            if (type == "movie") "https://vidlink.pro/movie/$id"
            else "https://vidlink.pro/tv/$id/$s/$e"
        }
    );

    companion object {
        /** All servers in display order */
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }

        /** WebView servers that load the embed directly (Servers 1-4) */
        val WEBVIEW_SERVERS = setOf(VIDLINK, VIDSRC, NONTONGO, CINEPRO)

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
    DONGHUA_STREAM("Server 1", "DonghuaStream", 1),
    LUCIFER_DONGHUA("Server 2", "Lucifer Donghua", 2),
    TWOEMBED("Server 3", "2embed (Exo)", 3),
    CINEPRO("Server 4", "CinePro", 4),
    LUCIFER_EXO("Server 5", "Lucifer Exo", 5);

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
