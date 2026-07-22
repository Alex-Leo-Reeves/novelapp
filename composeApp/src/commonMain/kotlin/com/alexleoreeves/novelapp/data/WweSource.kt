package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig

private val wweJson = Json { ignoreUnknownKeys = true; isLenient = true }

data class WweEvent(
    val eventId: String,
    val title: String,
    val brand: String = "",          // RAW, SmackDown, NXT
    val eventType: String = "",      // TV Show, PPV, Live Event
    val date: String = "",
    val time: String = "",
    val status: String = "",         // UPCOMING, LIVE, COMPLETED
    val venue: String = "",
    val description: String = "",
    val matches: List<WweMatch> = emptyList(),
    val posterUrl: String = "",
    val detailPageUrl: String = ""
)

data class WweMatch(
    val matchId: String,
    var eventId: String = "",
    val title: String,
    val participants: List<String> = emptyList(),
    val matchType: String = "",      // Singles, Tag Team, Triple Threat, etc.
    val stipulation: String = "",    // Hell in a Cell, Ladder, Steel Cage, etc.
    val isTitleMatch: Boolean = false,
    val titleName: String = "",      // WWE Championship, Intercontinental, etc.
    val status: String = "",
    val winner: String = "",
    val result: String = "",
    val detailUrl: String = "",
    val posterUrl: String = ""
) {
    val participantDisplay: String
        get() = participants.joinToString(" vs ")
    val isLive: Boolean get() = status == "LIVE"
    val isUpcoming: Boolean get() = status == "UPCOMING"
    val isFinished: Boolean get() = status == "COMPLETED"
    val hasResult: Boolean get() = result.isNotBlank()
}

data class WweBrand(
    val id: String,
    val name: String,
    val logo: String = ""
)

/**
 * Result of resolving a WWE event stream.
 * Option A: Embed URLs → MaServerPlayerScreen (WebView)
 * Option B: Direct HLS URLs → AnimePlayerScreen (ExoPlayer)
 */
sealed class WweStreamResult {
    /** Embed URL suitable for MaServerPlayerScreen WebView player */
    data class Embed(val url: String) : WweStreamResult()
    /** Direct HLS .m3u8 URL suitable for AnimePlayerScreen ExoPlayer */
    data class Direct(val url: String) : WweStreamResult()
}

class WweSource(private val httpClient: HttpClient) {

    private val baseUrl = "https://watchwrestling.ae"

    suspend fun fetchEvents(brand: String = "", status: String = ""): List<WweEvent> = runCatching {
        // Try server API first for better reliability
        val serverEvents = fetchEventsFromServer(brand)
        if (serverEvents.isNotEmpty()) {
            return@runCatching serverEvents
        }

        // Fallback: scrape watchwrestling.ae directly
        val url = if (brand.isNotBlank()) {
            when (brand.lowercase()) {
                "raw" -> "$baseUrl/category/wwe/raw/"
                "smackdown" -> "$baseUrl/category/wwe/smackdown/"
                "nxt" -> "$baseUrl/category/wwe/nxt/"
                "aew" -> "$baseUrl/category/aew/"
                else -> baseUrl
            }
        } else baseUrl

        val html = httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }.bodyAsText()

        val doc = Ksoup.parse(html)
        doc.select("div.post, article.post, div.item").mapNotNull { el ->
            val link = el.select("a.clip-link, h2.entry-title a, a[rel=bookmark]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            val img = el.select("img").firstOrNull()
            var cover = img?.attr("src") ?: ""
            if (cover.contains("dummy") || cover.contains("blank") || cover.contains("spacer")) {
                cover = img?.attr("data-src") ?: ""
            }
            val title = link.attr("title").ifBlank { link.text() }.ifBlank { img?.attr("alt").orEmpty() }.trim()

            if (href.isBlank() || title.isBlank()) return@mapNotNull null

            var eventBrand = "WWE"
            if (title.contains("Raw", ignoreCase = true)) eventBrand = "RAW"
            if (title.contains("SmackDown", ignoreCase = true)) eventBrand = "SmackDown"
            if (title.contains("NXT", ignoreCase = true)) eventBrand = "NXT"
            if (title.contains("AEW", ignoreCase = true)) eventBrand = "AEW"

            WweEvent(
                eventId = href.substringAfter("://").replace("/", "_"),
                title = title,
                brand = eventBrand,
                eventType = "TV Show",
                date = "Recent",
                status = "COMPLETED",
                posterUrl = cover,
                detailPageUrl = href
            )
        }.distinctBy { it.eventId }
    }.getOrElse { error ->
        println("[WWE] Events fetch failed: ${error.message}")
        emptyList()
    }

    /**
     * Fetch events from the server API for more reliable results.
     */
    private suspend fun fetchEventsFromServer(brand: String): List<WweEvent> = runCatching {
        val url = "${AppReleaseConfig.API_BASE_URL}/wwe/events"
        val response = httpClient.get(url) {
            header("Accept", "application/json")
            header("User-Agent", "NovelApp/1.0")
        }.bodyAsText()

        if (response.isBlank() || response.contains("<!doctype", ignoreCase = true)) return@runCatching emptyList()

        val root = wweJson.parseToJsonElement(response).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching emptyList()
        val data = root["data"]?.jsonArray ?: return@runCatching emptyList()

        data.mapNotNull { el ->
            val obj = el.jsonObject
            val eventId = obj["eventId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val brandVal = obj["brand"]?.jsonPrimitive?.contentOrNull ?: ""
            val poster = obj["posterUrl"]?.jsonPrimitive?.contentOrNull ?: ""
            val detailPageUrl = obj["detailPageUrl"]?.jsonPrimitive?.contentOrNull ?: ""
            val eventType = obj["eventType"]?.jsonPrimitive?.contentOrNull ?: "TV Show"
            val date = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "COMPLETED"

            WweEvent(
                eventId = eventId,
                title = title,
                brand = brandVal,
                eventType = eventType,
                date = date,
                status = status,
                posterUrl = poster,
                detailPageUrl = detailPageUrl
            )
        }
    }.getOrElse { emptyList() }

    suspend fun fetchMatches(eventId: String): List<WweMatch> = runCatching {
        // Try server API first
        val serverMatches = fetchMatchesFromServer(eventId)
        if (serverMatches.isNotEmpty()) return@runCatching serverMatches

        // Fallback: scrape directly
        val href = eventId.replace("_", "/").let { "https://$it" }
        val html = httpClient.get(href).bodyAsText()
        val doc = Ksoup.parse(html)

        // Extract wrestler names from page
        val wrestlerNames = doc.select("strong").mapNotNull { el ->
            val text = el.text().trim()
            text.takeIf { it.length > 3 && it.matches(Regex("^[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*$")) && !text.contains(":") }
        }.distinct()

        if (wrestlerNames.size >= 2) {
            wrestlerNames.chunked(2).mapIndexed { i, chunk ->
                val w1 = chunk.getOrNull(0) ?: "TBA"
                val w2 = chunk.getOrNull(1) ?: "TBA"
                WweMatch(
                    matchId = "${eventId}_match_$i",
                    eventId = eventId,
                    title = "$w1 vs $w2",
                    participants = listOf(w1, w2),
                    matchType = "Singles",
                    status = "COMPLETED"
                )
            }
        } else {
            // Fallback: return full show replay match
            listOf(
                WweMatch(
                    matchId = "${eventId}_full",
                    eventId = eventId,
                    title = "Full Show Replay / Live Stream",
                    status = "COMPLETED",
                    detailUrl = href
                )
            )
        }
    }.getOrElse { error ->
        println("[WWE] Matches fetch failed: ${error.message}")
        emptyList()
    }

    private suspend fun fetchMatchesFromServer(eventId: String): List<WweMatch> = runCatching {
        val url = "${AppReleaseConfig.API_BASE_URL}/wwe/matches?eventId=${java.net.URLEncoder.encode(eventId, "UTF-8")}"
        val response = httpClient.get(url) {
            header("Accept", "application/json")
        }.bodyAsText()

        if (response.isBlank() || response.contains("<!doctype", ignoreCase = true)) return@runCatching emptyList()

        val root = wweJson.parseToJsonElement(response).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching emptyList()
        val data = root["data"]?.jsonArray ?: return@runCatching emptyList()

        data.mapNotNull { el ->
            val obj = el.jsonObject
            WweMatch(
                matchId = obj["matchId"]?.jsonPrimitive?.contentOrNull ?: "",
                eventId = obj["eventId"]?.jsonPrimitive?.contentOrNull ?: eventId,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                participants = obj["participants"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                matchType = obj["matchType"]?.jsonPrimitive?.contentOrNull ?: "",
                stipulation = obj["stipulation"]?.jsonPrimitive?.contentOrNull ?: "",
                isTitleMatch = obj["isTitleMatch"]?.jsonPrimitive?.booleanOrNull ?: false,
                titleName = obj["titleName"]?.jsonPrimitive?.contentOrNull ?: "",
                status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
                winner = obj["winner"]?.jsonPrimitive?.contentOrNull ?: "",
                result = obj["result"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }.getOrElse { emptyList() }

    suspend fun fetchBrands(): List<WweBrand> = runCatching {
        listOf(
            WweBrand("raw", "RAW", "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1f/WWE_Raw_logo_%282019%29.svg/1200px-WWE_Raw_logo_%282019%29.svg.png"),
            WweBrand("smackdown", "SmackDown", "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cf/WWE_SmackDown_2019_logo.svg/1200px-WWE_SmackDown_2019_logo.svg.png"),
            WweBrand("nxt", "NXT", "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c9/WWE_NXT_logo_%282019-2021%29.svg/1200px-WWE_NXT_logo_%282019-2021%29.svg.png"),
            WweBrand("aew", "AEW", "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/All_Elite_Wrestling_Logo.svg/1200px-All_Elite_Wrestling_Logo.svg.png")
        )
    }.getOrElse { emptyList() }

    suspend fun searchEvents(query: String): List<WweEvent> = runCatching {
        val url = "${AppReleaseConfig.API_BASE_URL}/wwe/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val response = httpClient.get(url) {
            header("Accept", "application/json")
        }.bodyAsText()

        if (response.isNotBlank() && !response.contains("<!doctype", ignoreCase = true)) {
            val root = wweJson.parseToJsonElement(response).jsonObject
            if (root["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                val data = root["data"]?.jsonArray ?: return@runCatching emptyList()
                val results = data.mapNotNull { obj ->
                    val el = obj.jsonObject
                    WweEvent(
                        eventId = el["eventId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        title = el["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        brand = el["brand"]?.jsonPrimitive?.contentOrNull ?: "",
                        posterUrl = el["posterUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                        detailPageUrl = el["detailPageUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
                if (results.isNotEmpty()) return@runCatching results
            }
        }

        // Fallback: scrape watchwrestling
        val html = httpClient.get("$baseUrl/?s=${query.replace(" ", "+")}") {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }.bodyAsText()

        val doc = Ksoup.parse(html)
        doc.select("div.post, article.post, div.item").mapNotNull { el ->
            val link = el.select("a.clip-link, h2.entry-title a, a[rel=bookmark]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            val img = el.select("img").firstOrNull()
            var cover = img?.attr("src") ?: ""
            if (cover.contains("dummy") || cover.contains("blank") || cover.contains("spacer")) {
                cover = img?.attr("data-src") ?: ""
            }
            val title = link.attr("title").ifBlank { link.text() }.trim()

            if (href.isBlank() || title.isBlank()) return@mapNotNull null

            WweEvent(
                eventId = href.substringAfter("://").replace("/", "_"),
                title = title,
                brand = "WWE",
                status = "COMPLETED",
                posterUrl = cover,
                detailPageUrl = href
            )
        }.distinctBy { it.eventId }
    }.getOrElse { emptyList() }

    /**
     * Option A: Resolve embed URLs from the server.
     * Returns embed URLs suitable for MaServerPlayerScreen (WebView).
     */
    suspend fun resolveEmbedUrls(eventId: String, eventTitle: String = ""): List<String> = runCatching {
        val url = "${AppReleaseConfig.API_BASE_URL}/wwe/stream?event=${java.net.URLEncoder.encode(eventId, "UTF-8")}&title=${java.net.URLEncoder.encode(eventTitle, "UTF-8")}"
        val response = httpClient.get(url) {
            header("Accept", "application/json")
            header("User-Agent", "NovelApp/1.0")
        }.bodyAsText()

        if (response.isBlank() || response.contains("<!doctype", ignoreCase = true)) return@runCatching emptyList()

        val root = wweJson.parseToJsonElement(response).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching emptyList()
        val data = root["data"]?.jsonArray ?: return@runCatching emptyList()

        data.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrElse {
        println("[WWE] Embed URL resolution failed: ${it.message}")
        emptyList()
    }

    /**
     * Option B: Resolve direct .m3u8 stream URLs from the server.
     * Returns WweStreamResult.Direct with the HLS URL.
     */
    suspend fun resolveDirectStreamUrls(eventId: String, eventTitle: String = ""): List<WweStreamResult> = runCatching {
        val url = "${AppReleaseConfig.API_BASE_URL}/wwe/direct-stream"
        val body = buildJsonObject {
            put("eventId", eventId)
            put("eventTitle", eventTitle)
        }.toString()

        val response = httpClient.post(url) {
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("User-Agent", "NovelApp/1.0")
            setBody(body)
        }.bodyAsText()

        if (response.isBlank() || response.contains("<!doctype", ignoreCase = true)) return@runCatching emptyList()

        val root = wweJson.parseToJsonElement(response).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching emptyList()
        val data = root["data"]?.jsonObject ?: return@runCatching emptyList()

        val results = mutableListOf<WweStreamResult>()

        // Direct stream URLs
        val urls = data["urls"]?.jsonArray
        if (urls != null) {
            for (el in urls) {
                val urlStr = el.jsonPrimitive.contentOrNull
                if (urlStr != null) {
                    results.add(WweStreamResult.Direct(urlStr))
                }
            }
        }

        // Also return embed URLs (from the same endpoint)
        val embedUrls = data["embedUrls"]?.jsonArray
        if (embedUrls != null && results.isEmpty()) {
            for (el in embedUrls) {
                val urlStr = el.jsonPrimitive.contentOrNull
                if (urlStr != null) {
                    results.add(WweStreamResult.Embed(urlStr))
                }
            }
        }

        results
    }.getOrElse {
        println("[WWE] Direct stream resolution failed: ${it.message}")
        emptyList()
    }

    /**
     * Full resolution pipeline:
     * 1. Try Option B (server-side direct .m3u8 extraction) first — returns Direct stream
     * 2. Fall back to Option A (server-side embed extraction) — returns Embed
     * 3. Last resort: reconstruct the watchwrestling page URL as an embed fallback
     */
    suspend fun resolveStream(eventId: String, eventTitle: String = ""): WweStreamResult? {
        // Try Option B first — direct .m3u8
        val directResults = resolveDirectStreamUrls(eventId, eventTitle)
        val directUrl = directResults.firstOrNull { it is WweStreamResult.Direct }
        if (directUrl != null) return directUrl

        // Try Option A — embed URLs
        val embedUrls = resolveEmbedUrls(eventId, eventTitle)
        if (embedUrls.isNotEmpty()) return WweStreamResult.Embed(embedUrls.first())

        // Last resort: return the watchwrestling page itself as an embed
        val pageUrl = eventId.replace("_", "/").let { "https://$it" }.takeIf { it.startsWith("https://") }
        return if (pageUrl != null) WweStreamResult.Embed(pageUrl) else null
    }

    suspend fun resolveStreamUrl(eventId: String): String? {
        val result = resolveStream(eventId)
        return when (result) {
            is WweStreamResult.Direct -> result.url
            is WweStreamResult.Embed -> result.url
            else -> null
        }
    }

    suspend fun resolveStreamUrls(eventId: String): List<String> {
        val embedUrls = resolveEmbedUrls(eventId)
        return embedUrls.ifEmpty {
            val directUrls = resolveDirectStreamUrls(eventId)
            directUrls.mapNotNull {
                when (it) {
                    is WweStreamResult.Direct -> it.url
                    is WweStreamResult.Embed -> it.url
                }
            }
        }
    }
}
