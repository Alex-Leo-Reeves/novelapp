package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.*

/**
 * Anivexa Anime API client — talks to a loopback Node.js worker running
 * INSIDE the app on the device's residential IP (nodejs-mobile), falling back
 * to the app backend (which mounts the same Anivexa worker server-side).
 *
 * The worker aggregates 13 anime scraping providers keyed by AniList ID:
 * mkissa, reanime, anikoto, animegg, anineko, anidbapp, 2dhive, animenosub,
 * anizone, anibd, senshi, kaa, animedunya.
 *
 * Why the loopback worker matters: the provider CDNs (anineko, animegg,
 * anikoto, senshi, mkissa, ...) block DATACENTER egress (Render/Vercel →
 * streams=0 for popular anime like Dragon Ball), but allow residential IPs.
 * The repo owner's site works because its browser scrapes from the user's own
 * network. Running the same unmodified worker on-device replicates that exact
 * behavior — zero porting drift, all 13 providers.
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

        /**
         * Base URL of the in-app Anivexa worker when the embedded Node.js
         * runtime is available. The Android/TV platform layer calls
         * [setEmbeddedBaseUrl] once the nodebridge background server boots.
         */
        @Volatile
        var embeddedBaseUrl: String? = null
            private set

        /** Flag indicating if the embedded loopback worker is healthy and reachable. */
        @Volatile
        private var isEmbeddedHealthy: Boolean = false

        /** Called by the Android/TV nodebridge starter after the worker boots. */
        fun setEmbeddedBaseUrl(url: String) {
            val clean = url.trimEnd('/').takeIf { it.isNotBlank() }
            if (clean != null) {
                embeddedBaseUrl = clean
                isEmbeddedHealthy = true
                println("[Anivexa] Embedded nodebridge active at $clean on device residential IP")
            }
        }

        /** Notify that the embedded server failed (called on connection error). */
        fun markEmbeddedUnhealthy() {
            isEmbeddedHealthy = false
            println("[Anivexa] Embedded nodebridge marked unhealthy; falling back to backend")
        }

        /** Notify that the embedded server responded successfully. */
        fun markEmbeddedHealthy() {
            isEmbeddedHealthy = true
        }

        /**
         * Loopback URL when the embedded worker is active and healthy (residential IP),
         * else the app backend.
         */
        internal fun baseUrl(): String {
            val embedded = embeddedBaseUrl
            if (embedded != null && isEmbeddedHealthy) {
                return embedded
            }
            return AppReleaseConfig.API_BASE_URL + "/anivexa"
        }
    }

    /**
     * Fetch the (sub/dub) episode list for a provider + AniList ID.
     * Returns AnimeEpisode with url = `anivexa://{episodeId}`.
     * Tries the device's embedded residential nodebridge first, falling back to backend.
     */
    suspend fun fetchEpisodes(provider: String, anilistId: String): List<AnimeEpisode> {
        val primaryUrl = baseUrl()
        val result = runCatching {
            fetchEpisodesFromUrl(primaryUrl, provider, anilistId)
        }.getOrNull()

        if (!result.isNullOrEmpty()) {
            if (primaryUrl == embeddedBaseUrl) markEmbeddedHealthy()
            return result
        }

        // If embedded loopback failed or returned empty, retry via remote backend fallback
        if (primaryUrl == embeddedBaseUrl) {
            val fallbackUrl = AppReleaseConfig.API_BASE_URL + "/anivexa"
            val fallbackResult = runCatching {
                fetchEpisodesFromUrl(fallbackUrl, provider, anilistId)
            }.getOrNull()
            if (!fallbackResult.isNullOrEmpty()) {
                return fallbackResult
            }
        }

        return emptyList()
    }

    private suspend fun fetchEpisodesFromUrl(base: String, provider: String, anilistId: String): List<AnimeEpisode> {
        val raw: String = client.get("$base/episodes/$provider/$anilistId").body()
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        val data = root["data"]?.jsonObject ?: return emptyList()

        val sub = data["sub"]?.jsonArray.orEmpty()
        val dub = data["dub"]?.jsonArray.orEmpty()
        // Prefer sub, fall back to dub.
        val episodes = if (sub.isNotEmpty()) sub else dub

        return episodes.mapNotNull { element ->
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
        }.distinctBy { it.url }.sortedBy { it.episodeNumber }
    }

    /**
     * Resolve an anivexa:// episode marker to a playable stream URL.
     * Prefers an active HLS stream; falls back to the first URL (embed or direct).
     * Returns the stream URL and whether it's a direct media URL.
     * Tries the device's embedded residential nodebridge first, falling back to backend.
     */
    suspend fun resolveStream(episodeUrl: String): AnivexaStream? {
        if (!isAnivexaEpisodeUrl(episodeUrl)) return null
        val episodeId = episodeUrl.removePrefix(MARKER_PREFIX).trimStart('/')
        if (episodeId.isBlank()) return null

        val primaryUrl = baseUrl()
        val result = runCatching {
            resolveStreamFromUrl(primaryUrl, episodeId)
        }.getOrNull()

        if (result != null) {
            if (primaryUrl == embeddedBaseUrl) markEmbeddedHealthy()
            return result
        }

        // If embedded loopback failed or returned no stream, retry via remote backend fallback
        if (primaryUrl == embeddedBaseUrl) {
            val fallbackUrl = AppReleaseConfig.API_BASE_URL + "/anivexa"
            val fallbackResult = runCatching {
                resolveStreamFromUrl(fallbackUrl, episodeId)
            }.getOrNull()
            if (fallbackResult != null) {
                return fallbackResult
            }
        }

        return null
    }

    private suspend fun resolveStreamFromUrl(base: String, episodeId: String): AnivexaStream? {
        val raw: String = client.get("$base/$episodeId").body()
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
        val embed = streamObj["embed"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (embed.startsWith("http", ignoreCase = true) && requiresProviderPlaybackContext(url)) {
            return AnivexaStream(url = embed, type = "embed")
        }
        return AnivexaStream(url = url, type = type)
    }

    /** Resolve the VidLink (TMDB) embed reference for the LAST anime server. */
    suspend fun resolveVidLinkEmbed(anilistId: String, episode: Int): VidLinkEmbedRef? {
        return runCatching {
            val raw: String = client.get("${baseUrl()}/embed/$anilistId") {
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

    /**
     * Resolve the TMDB id mapped to an AniList id via the worker's `/map` route.
     *
     * Exists on BOTH the embedded loopback worker and the backend fallback, so
     * it works regardless of which Anivexa egress path is active. Used to VERIFY
     * a title-bridged AniList id for a `tmdb://` item: if the mapped TMDB id
     * differs from the item's own TMDB id, the bridge matched the WRONG show —
     * the caller must reject the id instead of keying the 13 providers off it.
     */
    suspend fun resolveTmdbIdForAnilist(anilistId: String): String? {
        return runCatching {
            val raw: String = client.get("${baseUrl()}/map/$anilistId").body()
            val root = json.parseToJsonElement(raw).jsonObject
            val mappings = root["mappings"]?.jsonObject
                ?: root["data"]?.jsonObject?.get("mappings")?.jsonObject
                ?: return null
            mappings["themoviedbId"]?.jsonPrimitive?.contentOrNull
                ?: mappings["tmdbId"]?.jsonPrimitive?.contentOrNull
        }.getOrElse { error ->
            println("[Anivexa] TMDB mapping resolve failed for $anilistId: ${error.message}")
            null
        }
    }

    /** Bridge a TMDB-sourced anime item → AniList ID via backend title search. */
    suspend fun searchAnilistId(title: String): String? {
        val query = normalizeSearchTitle(title)
        if (query.isBlank()) return null
        return runCatching {
            val raw: String = client.get("${baseUrl()}/search") {
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

    /**
     * Strips parenthetical qualifiers like `(TV)`, `(Dub)`, `(Sub)` and year
     * suffixes from a TMDB-style title so the AniList title search finds the
     * base show instead of failing on the qualifier.
     */
    private fun normalizeSearchTitle(title: String): String {
        val clean = title
            .replace(Regex("""\s*\((TV|Dub|Sub|Dubs|Subs|Movie|Film)\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)$"""), "")
            .trim()
        return clean.ifBlank { title.trim() }
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

    private fun requiresProviderPlaybackContext(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("workers.dev") ||
            lower.contains("vivibebe") ||
            lower.contains("bibiemb") ||
            lower.contains("vibevibe") ||
            lower.contains("otakuhg") ||
            lower.contains("otakuvid")
    }
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
