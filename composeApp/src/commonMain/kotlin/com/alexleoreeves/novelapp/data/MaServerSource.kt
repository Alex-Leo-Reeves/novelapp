package com.alexleoreeves.novelapp.data

/**
 * Streaming servers available for TMDB-based video playback.
 *
 * All servers use the same pipeline:
 *   1. Build an embed URL (VidLink or 2embed)
 *   2. Scrape via hidden WebView (extractStreamFromEmbed) for a direct HLS .m3u8 URL
 *   3. Play the HLS URL in ExoPlayer (AnimePlayerScreen)
 *
 * Server 1 — VidLink (original, default)
 * Server 2 — 2embed
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
    TWOEMBED(
        "Server 2",
        2,
        { id, type, s, e ->
            if (type == "movie") "https://www.2embed.skin/embed/movie/$id"
            else "https://www.2embed.skin/embed/tv/$id/$s/$e"
        }
    );

    companion object {
        /** All servers in display order */
        val ALL_IN_ORDER = values().sortedBy { it.serverOrder }
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
    TWOEMBED("Server 3", "2embed", 3);

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
