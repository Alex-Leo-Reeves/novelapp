package com.alexleoreeves.novelapp.ui

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

private val streamJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Data class for a single CinePro stream source.
 */
data class CineProSource(
    val url: String,
    val provider: String = "",
    val quality: String = "",
    val headers: Map<String, String>? = null
)

/**
 * Response from the main NovelApp server's POST /api/content/cinepro/sources endpoint.
 */
@kotlinx.serialization.Serializable
data class CineProSubtitleTrack(
    val url: String = "",
    val language: String = "en",
    val label: String = ""
)

@kotlinx.serialization.Serializable
data class CineProSourcesResponse(
    val ok: Boolean = false,
    val data: CineProSourcesData? = null
)

@kotlinx.serialization.Serializable
data class CineProSourcesData(
    val sources: List<CineProSourceData> = emptyList(),
    val subtitles: List<CineProSubtitleTrack> = emptyList(),
    val cineproEnabled: Boolean = false,
    val message: String? = null
)

@kotlinx.serialization.Serializable
data class CineProSourceData(
    val url: String = "",
    val provider: String = "",
    val quality: String = "",
    val headers: Map<String, String>? = null
)

/**
 * Fetch ALL available stream sources from CinePro Core via the main NovelApp server.
 *
 * The main server proxies the request to the CinePro Core instance (configured via
 * CINEPRO_BASE_URL env var) and returns ALL stream URLs from all discovered providers.
 *
 * POST /api/content/cinepro/sources
 * Body: { type: "movie"|"tv", id: "12345", season: "1", episode: "1" }
 *
 * Returns a list of CineProSource ordered by quality (best first).
 */
/**
 * Result of fetching CinePro sources — both stream URLs and subtitle tracks.
 */
data class CineProSourcesResult(
    val sources: List<CineProSource>,
    val subtitlesJson: String? = null
)

suspend fun resolveAllCineProSources(
    client: HttpClient,
    serverBaseUrl: String,
    type: String,
    tmdbId: String,
    season: String = "1",
    episode: String = "1"
): CineProSourcesResult = runCatching {
    val body = buildString {
        append("{")
        append("\"type\":\"$type\",")
        append("\"id\":\"$tmdbId\",")
        append("\"season\":\"$season\",")
        append("\"episode\":\"$episode\"")
        append("}")
    }
    val raw = client.post("$serverBaseUrl/api/content/cinepro/sources") {
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        setBody(body)
    }.bodyAsText()
    if (raw.isBlockedOrErrorPage()) return@runCatching CineProSourcesResult(emptyList())

    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    val response = json.decodeFromString<CineProSourcesResponse>(raw)
    if (response.ok != true) return@runCatching CineProSourcesResult(emptyList())

    val sources = response.data?.sources?.map { src ->
        CineProSource(
            url = src.url,
            provider = src.provider,
            quality = src.quality,
            headers = src.headers
        )
    }?.filter { it.url.isNotBlank() } ?: emptyList()

    // Convert CinePro subtitles to the format ExoPlayer expects: [{"file":"data:...","label":"...","srclang":"en","kind":"captions"}]
    val subtitlesJson = buildCineProSubtitlesJson(response.data?.subtitles ?: emptyList())

    CineProSourcesResult(sources, subtitlesJson)
}.getOrDefault(CineProSourcesResult(emptyList()))

/**
 * Convert CinePro's subtitle tracks (url/language/label) into the standard JSON format
 * that ExoPlayer's buildMediaItemWithSubtitles expects.
 * Each valid subtitle URL becomes a base64 data: URI for inline playback.
 */
private fun buildCineProSubtitlesJson(tracks: List<CineProSubtitleTrack>): String? {
    if (tracks.isEmpty()) return null
    val validTracks = tracks.filter { it.url.isNotBlank() && it.language.isNotBlank() }
    if (validTracks.isEmpty()) return null
    // For direct URL subtitles, build standard [{file, label, srclang, kind}] JSON
    val jsonArray = org.json.JSONArray()
    for (track in validTracks.take(5)) {
        // If the URL is a full HTTP URL, use it directly
        val fileUrl = if (track.url.startsWith("http")) track.url else {
            // Relative URLs would need CINEPRO_BASE_URL prefix — skip for now
            if (track.url.startsWith("/")) null else null
        } ?: continue
        val obj = org.json.JSONObject()
        obj.put("file", fileUrl)
        obj.put("label", track.label.ifBlank { "CinePro ${track.language.uppercase()}" })
        obj.put("srclang", track.language)
        obj.put("kind", "captions")
        jsonArray.put(obj)
    }
    return if (jsonArray.length() > 0) jsonArray.toString() else null
}

/**
 * Resolve a direct HLS stream URL from CinePro API.
 * DEPRECATED: Use resolveAllCineProSources instead, which goes through the main server.
 * Kept for backward compatibility with MovieDetailScreen.
 */
suspend fun resolveCineProStream(
    client: HttpClient,
    type: String,
    tmdbId: String,
    season: String = "1",
    episode: String = "1"
): String? = runCatching {
    val cineproBase = "https://cinepro-core-esmh.onrender.com"
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
            // Rewrite localhost proxy URLs to the actual CinePro Core instance
            val resolved = if (sourceUrl.startsWith("http://localhost:10000") && sourceUrl.contains("/v1/proxy?data=")) {
                val rewritten = sourceUrl.replace("http://localhost:10000", cineproBase)
                // Resolve the proxy to get the actual .m3u8
                try {
                    val proxyResponse = client.get(rewritten).bodyAsText()
                    val proxyData = streamJson.parseToJsonElement(proxyResponse).jsonObject
                    proxyData["url"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { rewritten }
                } catch (_: Exception) { rewritten }
            } else if (sourceUrl.startsWith("http")) {
                sourceUrl
            } else if (sourceUrl.startsWith("/v1/proxy")) {
                val proxyUrl = "$cineproBase$sourceUrl"
                val proxyResponse = client.get(proxyUrl).bodyAsText()
                val proxyData = streamJson.parseToJsonElement(proxyResponse).jsonObject
                proxyData["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            } else null
            if (!resolved.isNullOrBlank()) return@runCatching resolved
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
 * Build a VidLink embed URL.
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
 * Build a VidSrc.me (vidsrcme.ru) embed URL.
 * Uses vidsrcme.ru directly to avoid the vidsrc.me redirect.
 *
 * Movie: https://vidsrcme.ru/embed/movie?tmdb={tmdbId}
 * TV:    https://vidsrcme.ru/embed/tv?tmdb={tmdbId}&season={s}&episode={e}
 */
fun buildVidSrcMeUrl(
    tmdbId: String,
    type: String,
    season: String = "1",
    episode: String = "1"
): String = if (type == "movie") {
    "https://vidsrcme.ru/embed/movie?tmdb=$tmdbId"
} else {
    "https://vidsrcme.ru/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
}

/**
 * Build a VidSrc.to embed URL (replacement for the defunct embed.su).
 *
 * Movie: https://vidsrc.to/embed/movie/{tmdbId}
 * TV:    https://vidsrc.to/embed/tv/{tmdbId}/{season}/{episode}
 */
fun buildEmbedSuUrl(
    tmdbId: String,
    type: String,
    season: String = "1",
    episode: String = "1"
): String = if (type == "movie") {
    "https://vidsrc.to/embed/movie/$tmdbId"
} else {
    "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
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
        clean.endsWith(".mkv") ||
        clean.endsWith(".mov") ||
        clean.endsWith(".ts") ||
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
