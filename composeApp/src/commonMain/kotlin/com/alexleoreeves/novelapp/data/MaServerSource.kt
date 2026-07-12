package com.alexleoreeves.novelapp.data

/**
 * Streaming servers available for TMDB-based video playback.
 *
 * All four servers use the same pipeline:
 *   1. Build an embed URL (VidLink, Videasy, VidFast, or 111Movies)
 *   2. Scrape via hidden WebView (extractStreamFromEmbed) for a direct HLS .m3u8 URL
 *   3. Play the HLS URL in ExoPlayer (AnimePlayerScreen)
 *
 * Server 1 — VidLink (original, default)
 * Server 2 — Videasy
 * Server 3 — VidFast
 * Server 4 — 111Movies
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
    VIDEASY(
        "Server 2",
        2,
        { id, type, s, e ->
            if (type == "movie") "https://videasy.net/movie/$id?color=3B82F6"
            else "https://videasy.net/tv/$id/$s/$e?color=3B82F6"
        }
    ),
    VIDFAST(
        "Server 3",
        3,
        { id, type, s, e ->
            if (type == "movie") "https://vidfast.pro/movie/$id?theme=2980B9&autoPlay=true"
            else "https://vidfast.pro/tv/$id/$s/$e?theme=2980B9&autoPlay=true&nextButton=true"
        }
    ),
    MOVIES_111(
        "Server 4",
        4,
        { id, type, s, e ->
            if (type == "movie") "https://111movies.com/movie/$id"
            else "https://111movies.com/tv/$id/$s/$e"
        }
    );

    companion object {
        /** All servers in display order */
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
    
    return if (tvMatch != null) {
        val id = tvMatch.groupValues[1]
        val season = tvMatch.groupValues[2]
        val episode = tvMatch.groupValues[3]
        server.buildEmbedUrl(id, "tv", season, episode)
    } else if (movieMatch != null) {
        val id = movieMatch.groupValues[1]
        server.buildEmbedUrl(id, "movie", "1", "1")
    } else {
        cleanUrl
    }
}
