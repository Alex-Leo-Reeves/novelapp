package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.*

/**
 * ASIAN dedicated servers client — Chinese movies/series, Indian, Filipino.
 *
 * Each region has its own working source behind `/api/asian/`, and all three
 * resolve to something the app can play natively:
 *
 *  * **Chinese** and **Indian** → `ikanbot.com`, which returns a DIRECT public
 *    HLS playlist (AES-128, zero request headers). Verified playable master,
 *    key and segment in ExoPlayer, LibVLC, AVPlayer and hls.js.
 *  * **Filipino** → the same source when it has the title, otherwise the
 *    official ABS-CBN Star Cinema / Viva / Regal uploads on YouTube, which the
 *    app's existing YouTube player handles ([AsianPlayback.youtubeVideoId]).
 *
 * The backend only advertises a stream after it has really pulled the playlist,
 * so the app can treat [AsianPlayback.primary] as playable. [AsianStream.proxyUrl]
 * is the same-origin fallback for networks that cannot reach the CDN directly.
 */
class AsianApi(private val client: HttpClient) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val base: String get() = AppReleaseConfig.API_BASE_URL.trimEnd('/') + "/asian"

        /** Regions the Asian tab exposes, in display order. */
        val REGIONS: List<AsianRegion> = listOf(
            AsianRegion("chinese", "Chinese Movies"),
            AsianRegion("indian", "Indian"),
            AsianRegion("filipino", "Filipino")
        )
    }

    private suspend fun getJson(url: String): JsonObject? {
        val raw: String = client.get(url).body()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val root = element as? JsonObject ?: return null
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return root["data"] as? JsonObject
    }

    /** Search the dedicated source for a title inside one region. */
    suspend fun search(query: String, region: AsianRegion): List<AsianSearchHit> {
        val data = getJson("$base/search?q=${query.encodeURLQueryComponent()}&region=${region.key}")
            ?: return emptyList()
        return (data["results"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (id.isBlank() || title.isBlank()) null
            else AsianSearchHit(id, title, obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    /**
     * Resolve a region title to a playable stream.
     *
     * @param title the catalog title (TMDB name)
     * @param region which dedicated server to use
     * @param tmdbId the TMDB id — the backend uses it to look the title up under
     *   its regional release name, which is what makes non-English catalogs work
     * @param mediaType "movie" or "tv"
     */
    suspend fun resolve(
        title: String,
        region: AsianRegion,
        tmdbId: String = "",
        mediaType: String = "movie"
    ): AsianPlayback? {
        val query = buildString {
            append("$base/play?title=").append(title.encodeURLQueryComponent())
            append("&region=").append(region.key)
            if (tmdbId.isNotBlank()) append("&tmdbId=").append(tmdbId.encodeURLQueryComponent())
            append("&type=").append(mediaType)
        }
        val data = getJson(query) ?: return null
        return parse(data, region)
    }

    /** Official Filipino full movies on YouTube (Star Cinema / Viva / Regal). */
    suspend fun youtubeFilipino(query: String): List<AsianYouTubeVideo> {
        val data = getJson("$base/youtube?q=${query.encodeURLQueryComponent()}") ?: return emptyList()
        return (data["results"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val videoId = obj["videoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (videoId.isBlank()) return@mapNotNull null
            AsianYouTubeVideo(
                videoId = videoId,
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                channel = obj["channel"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                duration = obj["duration"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                thumbnail = obj["thumbnail"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
    }

    private fun parse(data: JsonObject, region: AsianRegion): AsianPlayback? {
        val streams = (data["streams"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            AsianStream(
                url = url,
                proxyUrl = obj["proxyUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "hls" }
            )
        }
        val youtubeId = data["youtubeVideoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (streams.isEmpty() && youtubeId.isBlank()) return null
        return AsianPlayback(
            region = region,
            provider = data["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            matchedTitle = data["matchedTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            streams = streams,
            youtubeVideoId = youtubeId,
            youtubeChannel = data["channel"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }
}

/** One selectable region of the Asian tab. */
data class AsianRegion(val key: String, val label: String)

/** A single playable HLS stream for an Asian title. */
data class AsianStream(
    val url: String,
    val proxyUrl: String = "",
    val quality: String = "",
    val label: String = "",
    val type: String = "hls"
)

/** A dedicated-source search hit. */
data class AsianSearchHit(val id: String, val title: String, val url: String)

/** An official-channel YouTube upload (Filipino mainstream). */
data class AsianYouTubeVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String,
    val thumbnail: String
)

/**
 * A resolved Asian title. Exactly one of [streams] / [youtubeVideoId] is set:
 * a direct HLS stream, or an official YouTube upload for the YouTube player.
 */
data class AsianPlayback(
    val region: AsianRegion,
    val provider: String = "",
    val matchedTitle: String = "",
    val streams: List<AsianStream> = emptyList(),
    val youtubeVideoId: String = "",
    val youtubeChannel: String = ""
) {
    /** Preferred stream (the backend lists a verified playlist first). */
    val primary: AsianStream? get() = streams.firstOrNull()

    val playbackUrl: String get() = primary?.url.orEmpty()

    /** Same-origin fallback for [playbackUrl], or "" when the backend had none. */
    val playbackProxyUrl: String get() = primary?.proxyUrl.orEmpty()

    val isYouTube: Boolean get() = youtubeVideoId.isNotBlank() && streams.isEmpty()
}

