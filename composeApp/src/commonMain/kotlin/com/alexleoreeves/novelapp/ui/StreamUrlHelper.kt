package com.alexleoreeves.novelapp.ui

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

private val streamJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Resolve a direct HLS stream URL from CinePro API.
 * CinePro is an OMSS-compliant backend that aggregates streaming providers.
 *
 * Movie: GET https://api.cinepro.cc/v1/movies/{tmdbId}
 * TV:    GET https://api.cinepro.cc/v1/tv/{tmdbId}/seasons/{s}/episodes/{e}
 *
 * Returns the first available HLS source URL, or null if none found.
 */
suspend fun resolveCineProStream(
    client: HttpClient,
    type: String,
    tmdbId: String,
    season: String = "1",
    episode: String = "1"
): String? = runCatching {
    val cineproBase = "https://api.cinepro.cc"
    val url = if (type == "movie") {
        "$cineproBase/v1/movies/$tmdbId"
    } else {
        "$cineproBase/v1/tv/$tmdbId/seasons/$season/episodes/$episode"
    }
    val raw = client.get(url) {
        header("Accept", "application/json")
        header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
    }.bodyAsText()
    if (raw.isBlockedOrErrorPage()) return@runCatching null

    val root = streamJson.parseToJsonElement(raw).jsonObject
    val sources = root["sources"]?.jsonArray ?: return@runCatching null
    for (source in sources) {
        val obj = source.jsonObject
        val sourceUrl = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sourceUrl.isNotBlank()) {
            if (sourceUrl.startsWith("http")) return@runCatching sourceUrl
            if (sourceUrl.startsWith("/v1/proxy")) {
                val proxyUrl = "$cineproBase$sourceUrl"
                val proxyResponse = client.get(proxyUrl).bodyAsText()
                val proxyData = streamJson.parseToJsonElement(proxyResponse).jsonObject
                val resolvedUrl = proxyData["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (resolvedUrl.isNotBlank()) return@runCatching resolvedUrl
            }
        }
    }
    null
}.getOrNull()

/**
 * Build a SuperEmbed (multiembed.mov) direct embed URL.
 * This URL embeds a video player that directly plays HLS streams.
 *
 * Movie: https://multiembed.mov/?video_id={tmdbId}&tmdb=1
 * TV:    https://multiembed.mov/?video_id={tmdbId}&tmdb=1&s={s}&e={e}
 */
fun buildSuperEmbedUrl(
    tmdbId: String,
    type: String,
    season: String = "1",
    episode: String = "1"
): String {
    return if (type == "movie") {
        "https://multiembed.mov/?video_id=$tmdbId&tmdb=1"
    } else {
        "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
    }
}

/**
 * Build a VidLink embed URL (fallback).
 */
fun buildVidLinkUrl(
    tmdbId: String,
    type: String,
    season: String = "1",
    episode: String = "1"
): String = if (type == "movie") {
    "https://vidlink.pro/movie/$tmdbId"
} else {
    "https://vidlink.pro/tv/$tmdbId/$season/$episode"
}

/**
 * Build an EmbedSu embed URL.
 *
 * Movie: https://embed.su/embed/movie/{tmdbId}
 * TV:    https://embed.su/embed/tv/{tmdbId}/{season}/{episode}
 */
fun buildEmbedSuUrl(
    tmdbId: String,
    type: String,
    season: String = "1",
    episode: String = "1"
): String = if (type == "movie") {
    "https://embed.su/embed/movie/$tmdbId"
} else {
    "https://embed.su/embed/tv/$tmdbId/$season/$episode"
}

/**
 * Check if a URL is a directly playable stream (HLS, MP4, DASH, etc.)
 */
fun String.isDirectPlayableStreamUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
        Regex("""/(playlist|manifest|hls|dash)(/|$)""").containsMatchIn(clean) ||
        startsWith("file:", ignoreCase = true)
}

/**
 * Check if a response body indicates a blocked, error, or rate-limited page
 * (e.g. Cloudflare challenge, 404 HTML page, or similar non-JSON response).
 * Always check the response body against this before parsing as JSON.
 */
fun String.isBlockedOrErrorPage(): Boolean {
    val lower = lowercase().take(500)
    return lower.contains("<!doctype html") ||
        lower.contains("<html") ||
        lower.contains("cloudflare") ||
        lower.contains("attention required") ||
        lower.contains("just a moment") ||
        lower.contains("access denied") ||
        lower.contains("403 forbidden") ||
        lower.contains("404 not found") ||
        lower.contains("502 bad gateway") ||
        lower.contains("503 service unavailable") ||
        lower.contains("captcha") ||
        lower.contains("blocked")
}
