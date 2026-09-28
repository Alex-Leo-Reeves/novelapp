package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.*

/**
 * Dedicated Donghua server client — `donghuaworld.com`.
 *
 * Every episode page hosts its own player whose config embeds a PLAIN PUBLIC
 * Rumble HLS master (`https://rumble.com/hls-vod/{id}/playlist.m3u8`). That URL
 * returns 200 with no request headers at all and sends
 * `access-control-allow-origin: *`, so it plays unchanged in ExoPlayer, LibVLC,
 * AVPlayer and hls.js — no WebView, no embed page and no redirect to the host
 * site. Eighteen public VTT subtitle tracks (incl. English) come with it.
 *
 * The backend (`/api/donghua/`) does the scraping and only ever advertises a
 * stream after probing that the playlist really loads, so the app can treat
 * [DonghuaResolved.primary] as playable. [DonghuaStream.proxyUrl] is the
 * same-origin fallback through our own HLS proxy for networks that cannot reach
 * the CDN directly.
 */
class DonghuaApi(private val client: HttpClient) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val base: String get() = AppReleaseConfig.API_BASE_URL.trimEnd('/') + "/donghua"

        /** True when a URL came from this dedicated donghua source. */
        fun isDonghuaworldUrl(url: String): Boolean =
            url.contains("donghuaworld.com", ignoreCase = true) ||
                url.contains("playing.donghuaworld", ignoreCase = true)
    }

    private suspend fun getJson(url: String): JsonObject? {
        val raw: String = client.get(url).body()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val root = element as? JsonObject ?: return null
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return root["data"] as? JsonObject
    }

    /** Search donghuaworld for a series by title. */
    suspend fun searchSeries(title: String): List<DonghuaSeriesRef> {
        val target = "$base/search?q=${title.encodeURLQueryComponent()}"
        val data = getJson(target) ?: return emptyList()
        return (data["results"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank() || name.isBlank()) null
            else DonghuaSeriesRef(obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), name, url)
        }
    }

    /**
     * Episode list for a series, ascending by episode number.
     *
     * Returned as [MediaEpisode] so the existing detail screens can consume it
     * without a parallel model; `url` is the episode page the resolver expects.
     */
    suspend fun fetchEpisodes(seriesUrl: String, seriesTitle: String = ""): List<MediaEpisode> {
        val target = if (seriesUrl.isNotBlank()) {
            "$base/series?url=${seriesUrl.encodeURLQueryComponent()}"
        } else if (seriesTitle.isNotBlank()) {
            "$base/series?title=${seriesTitle.encodeURLQueryComponent()}"
        } else {
            return emptyList()
        }
        val data = getJson(target) ?: return emptyList()
        return (data["episodes"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0
            MediaEpisode(
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .ifBlank { "Episode ${number.coerceAtLeast(1)}" },
                url = url,
                episodeNumber = number.coerceAtLeast(1)
            )
        }
    }

    /** Episode list found by title (used when the app only has a TMDB title). */
    suspend fun fetchEpisodesForTitle(title: String): List<MediaEpisode> {
        val series = searchSeries(title).firstOrNull() ?: return emptyList()
        return fetchEpisodes(series.url, series.title)
    }

    /** Resolve a donghua episode page into a directly playable stream. */
    suspend fun resolveEpisodeStream(episodeUrl: String): DonghuaResolved? {
        if (episodeUrl.isBlank()) return null
        val data = getJson("$base/watch?url=${episodeUrl.encodeURLQueryComponent()}") ?: return null
        return parseResolved(data)
    }

    /**
     * One-call resolution from just a title + episode number — the path used
     * when a donghua title came from TMDB and carries no episode URLs.
     */
    suspend fun resolveByTitle(title: String, episodeNumber: Int): DonghuaResolved? {
        if (title.isBlank()) return null
        val data = getJson(
            "$base/play?title=${title.encodeURLQueryComponent()}&ep=$episodeNumber"
        ) ?: return null
        return parseResolved(data)
    }

    private fun parseResolved(data: JsonObject): DonghuaResolved? {
        val streams = (data["streams"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            DonghuaStream(
                url = url,
                quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                proxyUrl = obj["proxyUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "hls" }
            )
        }
        if (streams.isEmpty()) return null
        val subtitles = (data["subtitles"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            DonghuaSubtitle(
                url = url,
                label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lang = obj["lang"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
        return DonghuaResolved(
            title = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            streams = streams,
            subtitles = subtitles
        )
    }
}

/** One playable stream for a donghua episode. */
data class DonghuaStream(
    val url: String,
    val quality: String = "",
    val label: String = "",
    val proxyUrl: String = "",
    val type: String = "hls"
)

/** One subtitle track shipped alongside a donghua episode. */
data class DonghuaSubtitle(
    val url: String,
    val label: String = "",
    val lang: String = ""
)

/** A donghuaworld series entry from search. */
data class DonghuaSeriesRef(
    val id: String,
    val title: String,
    val url: String
)

/** A resolved donghua episode: playable streams + subtitle tracks. */
data class DonghuaResolved(
    val title: String,
    val streams: List<DonghuaStream>,
    val subtitles: List<DonghuaSubtitle> = emptyList()
) {
    /** Preferred stream (the backend lists the verified master first). */
    val primary: DonghuaStream? get() = streams.firstOrNull()

    val playbackUrl: String get() = primary?.url.orEmpty()

    /**
     * Same-origin fallback for the primary stream, or "" when the backend could
     * not build one. Used when a device's network blocks the CDN directly.
     */
    val playbackProxyUrl: String get() = primary?.proxyUrl.orEmpty()

    /**
     * Subtitle tracks in the shape the players already consume:
     * `[{"file": "...", "label": "...", "srclang": "..."}]`, English first so
     * the players' default selection lands on it.
     */
    fun subtitlesJson(): String? {
        if (subtitles.isEmpty()) return null
        val ordered = subtitles.sortedBy { if (it.lang.equals("en", true)) 0 else 1 }
        val array = buildJsonArray {
            ordered.forEach { track ->
                addJsonObject {
                    put("file", track.url)
                    put("label", track.label.ifBlank { track.lang })
                    put("srclang", track.lang)
                }
            }
        }
        return array.toString()
    }
}
