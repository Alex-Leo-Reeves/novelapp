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
     * [preferredAudio] selects the list when the provider offers both ("sub"
     * or "dub", mirroring AniVault's language toggle); falls back to whichever
     * list the provider actually has.
     * Tries the device's embedded residential nodebridge first, falling back to backend.
     */
    suspend fun fetchEpisodes(
        provider: String,
        anilistId: String,
        preferredAudio: String = "sub"
    ): List<AnimeEpisode> {
        val primaryUrl = baseUrl()
        val result = runCatching {
            fetchEpisodesFromUrl(primaryUrl, provider, anilistId, preferredAudio)
        }.getOrNull()

        if (!result.isNullOrEmpty()) {
            if (primaryUrl == embeddedBaseUrl) markEmbeddedHealthy()
            return result
        }

        // If embedded loopback failed or returned empty, retry via remote backend fallback
        if (primaryUrl == embeddedBaseUrl) {
            val fallbackUrl = AppReleaseConfig.API_BASE_URL + "/anivexa"
            val fallbackResult = runCatching {
                fetchEpisodesFromUrl(fallbackUrl, provider, anilistId, preferredAudio)
            }.getOrNull()
            if (!fallbackResult.isNullOrEmpty()) {
                return fallbackResult
            }
        }

        return emptyList()
    }

    private suspend fun fetchEpisodesFromUrl(
        base: String,
        provider: String,
        anilistId: String,
        preferredAudio: String
    ): List<AnimeEpisode> {
        val raw: String = client.get("$base/episodes/$provider/$anilistId").body()
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        val data = root["data"]?.jsonObject ?: return emptyList()

        val sub = data["sub"]?.jsonArray.orEmpty()
        val dub = data["dub"]?.jsonArray.orEmpty()
        // Preferred audio first (dub where the provider has it), fall back to
        // whichever list exists so a provider with sub-only coverage still plays.
        val episodes = when {
            preferredAudio.equals("dub", ignoreCase = true) && dub.isNotEmpty() -> dub
            sub.isNotEmpty() -> sub
            else -> dub
        }

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
        val payloadHeaders = data["headers"]?.jsonObject
        val payloadReferer = payloadHeaders?.get("Referer")?.jsonPrimitive?.contentOrNull
        val payloadUserAgent = payloadHeaders?.get("User-Agent")?.jsonPrimitive?.contentOrNull

        // Prefer a DIRECT media stream (active first) — ExoPlayer can attach the
        // provider Referer per request, which makes provider CDNs like anikoto's
        // kryntal host serve the real playlist instead of a 403 block page.
        val direct = streams.firstOrNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@firstOrNull false
            o.isActiveStream() && o.isDirectStream()
        } ?: streams.firstOrNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@firstOrNull false
            o.isDirectStream()
        }
        if (direct != null) {
            val streamObj = direct.jsonObject
            val url = streamObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isNotBlank()) {
                val referer = streamObj["referer"]?.jsonPrimitive?.contentOrNull ?: payloadReferer
                val userAgent = streamObj["userAgent"]?.jsonPrimitive?.contentOrNull ?: payloadUserAgent
                return AnivexaStream(
                    url = url,
                    type = streamObj["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    referer = referer,
                    userAgent = userAgent,
                    headersJson = buildHeadersJson(referer, userAgent),
                    subtitlesJson = buildSubtitlesJson(data["subtitles"]?.jsonArray)
                )
            }
        }

        // No direct media URL — fall back to the first entry that has an embed page.
        val embedStream = streams.firstOrNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull()
            o != null && o["embed"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .startsWith("http", ignoreCase = true)
        }
        val streamObj = embedStream?.jsonObject
            ?: streams.firstOrNull()?.runCatching { jsonObject }?.getOrNull()
            ?: return null
        val embed = streamObj["embed"]?.jsonPrimitive?.contentOrNull
            ?: streamObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (embed.isBlank()) return null
        return AnivexaStream(url = embed, type = "embed")
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

    /** True when the stream entry points at playable media (not an embed page). */
    private fun JsonObject.isDirectStream(): Boolean {
        val type = this["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type.equals("hls", ignoreCase = true) ||
            type.equals("dash", ignoreCase = true) ||
            type.equals("mp4", ignoreCase = true)
        ) return true
        val url = this["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".m3u", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".mpd", ignoreCase = true)
    }

    /**
     * Build the player-headers JSON for a resolved stream. The worker computes
     * the exact Referer/User-Agent each provider CDN demands (e.g. anikoto's
     * kryntal CDN requires `Referer: https://megaplay.buzz/` — without it the
     * CDN returns a 403 Cloudflare block page). Verified live: no Referer →
     * 403; payload Referer → 200 playlist + subtitle tracks.
     */
    private fun buildHeadersJson(referer: String?, userAgent: String?): String? {
        if (referer.isNullOrBlank() && userAgent.isNullOrBlank()) return null
        return buildJsonObject {
            if (!referer.isNullOrBlank()) {
                put("Referer", JsonPrimitive(referer))
                put("Origin", JsonPrimitive(referer.trimEnd('/')))
            }
            if (!userAgent.isNullOrBlank()) put("User-Agent", JsonPrimitive(userAgent))
        }.toString()
    }

    /**
     * Map the worker's `subtitles` array ([{url,label,srclang,format,default}])
     * to the player's subtitle JSON shape ({file,label,srclang,kind}).
     */
    private fun buildSubtitlesJson(subtitles: JsonArray?): String? {
        if (subtitles.isNullOrEmpty()) return null
        val tracks = subtitles.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null
            buildJsonObject {
                put("file", JsonPrimitive(url))
                obj["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { put("label", JsonPrimitive(it)) }
                obj["srclang"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { put("srclang", JsonPrimitive(it)) }
                put("kind", JsonPrimitive("subtitles"))
            }
        }
        if (tracks.isEmpty()) return null
        return buildJsonArray { tracks.forEach { add(it) } }.toString()
    }
}

/** A resolved Anivexa stream (direct HLS/MP4 or an embed page). */
data class AnivexaStream(
    val url: String,
    val type: String,
    /** Referer the provider CDN requires (stream entry or payload headers). */
    val referer: String? = null,
    /** User-Agent the provider CDN expects (payload headers). */
    val userAgent: String? = null,
    /** Ready-to-use JSON object of HTTP headers for the player, or null. */
    val headersJson: String? = null,
    /** JSON array of soft-subtitle tracks ({file,label,srclang,kind}), or null. */
    val subtitlesJson: String? = null
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
