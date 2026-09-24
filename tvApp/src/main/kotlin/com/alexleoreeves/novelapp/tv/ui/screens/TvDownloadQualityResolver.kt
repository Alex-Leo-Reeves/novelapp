package com.alexleoreeves.novelapp.tv.ui.screens

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── CinePro models (TV-local copy so tvApp doesn't depend on composeApp/ui) ──

data class TvCineProSource(
    val url: String,
    val provider: String = "",
    val quality: String = "",
    val headers: Map<String, String>? = null,
    val headersJson: String? = null
)

@kotlinx.serialization.Serializable
private data class CineProSubtitleTrack(
    val url: String = "",
    val language: String = "en",
    val label: String = ""
)

@kotlinx.serialization.Serializable
private data class CineProSourcesResponse(
    val ok: Boolean = false,
    val data: CineProSourcesData? = null
)

@kotlinx.serialization.Serializable
private data class CineProSourcesData(
    val sources: List<CineProSourceData> = emptyList(),
    val subtitles: List<CineProSubtitleTrack> = emptyList(),
    val cineproEnabled: Boolean = false,
    val message: String? = null
)

@kotlinx.serialization.Serializable
private data class CineProSourceData(
    val url: String = "",
    val provider: String = "",
    val quality: String = "",
    val headers: Map<String, String>? = null
)

private data class CineProSourcesResult(
    val sources: List<TvCineProSource>,
    val subtitlesJson: String? = null
)

// ── Extension helpers ────────────────────────────────────────────────────────

fun String.isTvDirectPlayableStreamUrl(): Boolean {
    val clean = substringBefore("#").lowercase()
    if (contains("/v1/proxy?") || contains("/proxy?data=")) return true
    val stripped = clean.substringBefore("?").substringBefore("#")
    return stripped.endsWith(".m3u8") ||
        stripped.endsWith(".mp4") ||
        stripped.endsWith(".mpd") ||
        stripped.endsWith(".webm") ||
        stripped.endsWith(".mkv") ||
        stripped.endsWith(".mov") ||
        stripped.endsWith(".ts") ||
        startsWith("file:", ignoreCase = true)
}

private fun String.isBlockedOrErrorPage(): Boolean {
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

// ── CinePro multi-source resolver ────────────────────────────────────────────

private suspend fun resolveAllCineProSourcesTv(
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

    val cineproBase = "https://cinepro-core-esmh.onrender.com"
    val sources = response.data?.sources?.flatMap { src ->
        val results = mutableListOf<TvCineProSource>()
        val rawUrl = src.url

        if (rawUrl.contains("/v1/proxy?data=")) {
            val rewritten = if (rawUrl.startsWith("http://localhost:10000")) {
                rawUrl.replace("http://localhost:10000", cineproBase)
            } else if (rawUrl.startsWith("/v1/proxy")) {
                "$cineproBase$rawUrl"
            } else {
                rawUrl
            }
            val headersJson = src.headers?.let { h ->
                val entries = h.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                "{$entries}"
            }
            results.add(TvCineProSource(
                url = rewritten,
                provider = src.provider.ifBlank { "" },
                quality = src.quality,
                headers = src.headers,
                headersJson = headersJson
            ))
        } else if (rawUrl.isNotBlank()) {
            val headersJson = src.headers?.let { h ->
                val entries = h.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                "{$entries}"
            }
            results.add(TvCineProSource(
                url = rawUrl,
                provider = src.provider,
                quality = src.quality,
                headers = src.headers,
                headersJson = headersJson
            ))
        }
        results
    }?.filter { it.url.isNotBlank() }?.distinctBy {
        if (it.url.contains("/v1/proxy?data=")) it.url else it.url.substringBefore("?")
    } ?: emptyList()

    val subtitlesJson = buildCineProSubtitlesJsonTv(response.data?.subtitles ?: emptyList())
    CineProSourcesResult(sources, subtitlesJson)
}.getOrDefault(CineProSourcesResult(emptyList()))

private fun buildCineProSubtitlesJsonTv(tracks: List<CineProSubtitleTrack>): String? {
    if (tracks.isEmpty()) return null
    val validTracks = tracks.filter { it.url.isNotBlank() && it.language.isNotBlank() }
    if (validTracks.isEmpty()) return null
    val jsonArray = buildJsonArray {
        for (track in validTracks.take(5)) {
            val fileUrl = if (track.url.startsWith("http")) track.url else continue
            add(
                buildJsonObject {
                    put("file", fileUrl)
                    put("label", track.label.ifBlank { "CinePro ${track.language.uppercase()}" })
                    put("srclang", track.language)
                    put("kind", "captions")
                }
            )
        }
    }
    return if (jsonArray.isNotEmpty()) jsonArray.toString() else null
}

// ── Public entry point: resolve best download qualities for TV ────────────────

suspend fun tvResolveDownloadableQualities(
    httpClient: HttpClient,
    sourceUrl: String,
    tmdbContext: Triple<String, String, String>? = null,
    onStatus: ((String) -> Unit)? = null
): List<TvCineProSource> {
    // Phase 1: Try CinePro Core for any TMDB-based content
    if (tmdbContext != null) {
        val (tmdbIdCtx, mediaTypeCtx, seasonEpisode) = tmdbContext
        val parts = seasonEpisode.split(":")
        val season = parts.getOrNull(0) ?: "1"
        val episode = parts.getOrNull(1) ?: "1"
        onStatus?.invoke("CinePro: searching 10+ providers for download...")
        val result = resolveAllCineProSourcesTv(httpClient, AppReleaseConfig.SERVER_BASE_URL, mediaTypeCtx, tmdbIdCtx, season, episode)
        val directSources = result.sources.filter { it.url.isTvDirectPlayableStreamUrl() }
        if (directSources.isNotEmpty()) {
            onStatus?.invoke("CinePro: found direct stream.")
            return directSources
        }
        onStatus?.invoke("CinePro: no sources. Trying embed fallback...")
    }

    // Phase 2: Check if the source itself is a direct stream URL
    val trimmed = sourceUrl.trim()
    if (trimmed.isNotBlank() && trimmed.isTvDirectPlayableStreamUrl()) {
        return listOf(TvCineProSource(url = trimmed, quality = "Direct"))
    }

    // Phase 3: TV does not support hidden WebView scraping — return empty
    return emptyList()
}
