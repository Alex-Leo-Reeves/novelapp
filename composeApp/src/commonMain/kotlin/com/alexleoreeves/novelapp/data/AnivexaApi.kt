package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*

/**
 * Anivexa Anime API client — talks to the app's own backend
 * (https://novelapp1.onrender.com/api/anivexa/) which mounts the Anivexa-API
 * worker in-process (server/anivexa). The worker aggregates 13 anime scraping
 * providers keyed by AniList ID.
 *
 * Providers: mkissa, reanime, anikoto, animegg, anineko, anidbapp, 2dhive,
 * animenosub, anizone, anibd, senshi, kaa, animedunya.
 *
 * Episode URLs are stored as `anivexa://{episodeId}` where episodeId is the
 * Anivexa episode `id` (e.g. `watch/anineko/151807/sub/anineko-1`). That same
 * id is the path suffix for the watch route, so stream resolution is just a
 * URL rewrite — no re-parsing.
 */
class AnivexaApi(private val client: HttpClient) {

    companion object {
        private const val MARKER_PREFIX = "anivexa://"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun isAnivexaEpisodeUrl(url: String): Boolean =
            url.startsWith(MARKER_PREFIX, ignoreCase = true)
    }

    /**
     * Fetch the (sub/dub) episode list for a provider + AniList ID.
     * Returns AnimeEpisode with url = `anivexa://{episodeId}`.
     */
    suspend fun fetchEpisodes(provider: String, anilistId: String): List<AnimeEpisode> {
        return runCatching {
            val raw: String = client.get("${AppReleaseConfig.API_BASE_URL}/anivexa/episodes/$provider/$anilistId").body()
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
            val data = root["data"]?.jsonObject ?: return emptyList()

            val sub = data["sub"]?.jsonArray.orEmpty()
            val dub = data["dub"]?.jsonArray.orEmpty()
            // Prefer sub, fall back to dub.
            val episodes = if (sub.isNotEmpty()) sub else dub

            episodes.mapNotNull { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank()) return@mapNotNull null
                val number = parseEpisodeNumber(item["number"])
                    ?: return@mapNotNull null
                AnimeEpisode(
                    episodeNumber = number,
                    title = item["title"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: "Episode $number",
                    url = MARKER_PREFIX + id,
                    thumbnail = item["image"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }
                .distinctBy { it.url }
                .sortedBy { it.episodeNumber }
        }.getOrElse { error ->
            println("[Anivexa] Episode fetch failed for $provider/$anilistId: ${error.message}")
            emptyList()
        }
    }

    /**
     * Resolve an anivexa:// episode marker to a playable stream URL.
     * Prefers an active HLS stream; falls back to the first URL (embed or direct).
     * Returns the stream URL and whether it's a direct media URL.
     */
    suspend fun resolveStream(episodeUrl: String): AnivexaStream? {
        if (!isAnivexaEpisodeUrl(episodeUrl)) return null
        val episodeId = episodeUrl.removePrefix(MARKER_PREFIX).trimStart('/')
        if (episodeId.isBlank()) return null

        return runCatching {
            val raw: String = client.get("${AppReleaseConfig.API_BASE_URL}/anivexa/$episodeId").body()
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
            val data = root["data"]?.jsonObject ?: return null
            val streams = data["streams"]?.jsonArray.orEmpty()

            // Prefer active HLS, then active embed, then any first URL.
            val preferred = streams.firstOrNull { it.jsonObject.isActiveStream() }
                ?: streams.firstOrNull()
                ?: return null
            val streamObj = preferred.jsonObject
            val url = streamObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return null
            val type = streamObj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            AnivexaStream(url = url, type = type)
        }.getOrElse { error ->
            println("[Anivexa] Stream resolve failed for $episodeId: ${error.message}")
            null
        }
    }

    /** Resolve the VidLink (TMDB) embed reference for the LAST anime server. */
    suspend fun resolveVidLinkEmbed(anilistId: String, episode: Int): VidLinkEmbedRef? {
        return runCatching {
            val raw: String = client.get("${AppReleaseConfig.API_BASE_URL}/anivexa/embed/$anilistId") {
                parameter("ep", episode)
            }.body()
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
            val data = root["data"]?.jsonObject ?: return null
            VidLinkEmbedRef(
                tmdbId = data["tmdbId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = data["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                season = data["season"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                episode = data["episode"]?.jsonPrimitive?.intOrNull ?: episode
            )
        }.getOrElse { error ->
            println("[Anivexa] Embed resolve failed for $anilistId/$episode: ${error.message}")
            null
        }
    }

    /** Bridge a TMDB-sourced anime item → AniList ID via backend title search. */
    suspend fun searchAnilistId(title: String): String? {
        val query = title.trim()
        if (query.isBlank()) return null
        return runCatching {
            val raw: String = client.get("${AppReleaseConfig.API_BASE_URL}/anivexa/search") {
                parameter("q", query)
            }.body()
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
            root["data"]?.jsonObject?.get("anilistId")?.jsonPrimitive?.contentOrNull
        }.getOrElse { error ->
            println("[Anivexa] Search failed for '$query': ${error.message}")
            null
        }
    }

    private fun parseEpisodeNumber(value: JsonElement?): Int? {
        val raw = when {
            value == null -> return null
            value is JsonPrimitive && value.isString -> value.contentOrNull
            else -> value.toString()
        } ?: return null
        val cleaned = raw.trim()
        val number = cleaned.toDoubleOrNull() ?: return null
        // Specials like "1.5" round down to their base episode.
        return number.toInt().takeIf { it > 0 }
    }

    private fun JsonObject.isActiveStream(): Boolean =
        this["isActive"]?.jsonPrimitive?.booleanOrNull == true ||
            this["priority"]?.jsonPrimitive?.intOrNull?.let { it >= 5 } == true
}

/** A resolved Anivexa stream (direct HLS/MP4 or an embed page). */
data class AnivexaStream(
    val url: String,
    val type: String
) {
    val isDirect: Boolean
        get() = type.equals("hls", ignoreCase = true) ||
            type.equals("dash", ignoreCase = true) ||
            type.equals("mp4", ignoreCase = true) ||
            (url.endsWith(".m3u8", ignoreCase = true) || url.endsWith(".m3u", ignoreCase = true) ||
                url.endsWith(".mp4", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true))
}

/** VidLink embed reference resolved from AniList → TMDB mapping. */
data class VidLinkEmbedRef(
    val tmdbId: String,
    val type: String,
    val season: String,
    val episode: Int
) {
    fun buildEmbedUrl(): String =
        if (type == "movie") "https://vidlink.pro/movie/$tmdbId"
        else "https://vidlink.pro/tv/$tmdbId/$season/$episode"
}
