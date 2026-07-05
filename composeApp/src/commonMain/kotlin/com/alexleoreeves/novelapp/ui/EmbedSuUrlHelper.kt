package com.alexleoreeves.novelapp.ui

/**
 * Builds the embed.su direct embed URL for a given TMDB title.
 *
 * TV:    https://embed.su/embed/tv/{tmdbId}/{season}/{episode}
 * Movie: https://embed.su/embed/movie/{tmdbId}
 *
 * The resulting URL is intended to be passed to the platform video player, which
 * on Android will silently extract the underlying .m3u8 via EmbedSuStreamScraper
 * before handing it to ExoPlayer.
 */
fun buildEmbedSuUrl(
    tmdbId: String,
    mediaType: String,
    season: String = "1",
    episode: String = "1"
): String = if (mediaType == "movie") {
    "https://embed.su/embed/movie/$tmdbId"
} else {
    "https://embed.su/embed/tv/$tmdbId/$season/$episode"
}
