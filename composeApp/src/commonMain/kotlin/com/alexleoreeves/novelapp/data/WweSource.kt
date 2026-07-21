package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.*

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

class WweSource(private val httpClient: HttpClient) {

    private val baseUrl = "https://watchwrestling.ae"

    suspend fun fetchEvents(brand: String = "", status: String = ""): List<WweEvent> = runCatching {
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

    suspend fun fetchMatches(eventId: String): List<WweMatch> = runCatching {
        // Since we scrape posts, we don't have individual matches upfront.
        // We return a single dummy match that represents the full show replay.
        val href = eventId.replace("_", "/").let { "https://$it" }
        listOf(
            WweMatch(
                matchId = "${eventId}_full",
                eventId = eventId,
                title = "Full Show Replay / Live Stream",
                status = "COMPLETED",
                detailUrl = href
            )
        )
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
        val url = "$baseUrl/?s=${query.replace(" ", "+")}"
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

    suspend fun resolveStreamUrl(eventId: String): String? {
        val urls = resolveStreamUrls(eventId)
        return urls.firstOrNull()
    }

    suspend fun resolveStreamUrls(eventId: String): List<String> = runCatching {
        val pageUrl = eventId.replace("_", "/").let { "https://$it" }
        val html = httpClient.get(pageUrl) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            header("Referer", "https://watchwrestling.ae/")
        }.bodyAsText()

        val doc = Ksoup.parse(html)
        val embedUrls = mutableListOf<String>()
        
        // 1. Extract iframe src with video host patterns
        doc.select("iframe").mapNotNull { it.attr("src") }
            .filter { src ->
                src.isNotBlank() && (
                    src.contains("dailymotion") || src.contains("vidmoly") ||
                    src.contains("dood") || src.contains("embed") ||
                    src.contains("voe") || src.contains("stream") ||
                    src.contains("player") || src.contains("watch") ||
                    src.contains("video")
                )
            }
            .forEach { embedUrls.add(it) }
        
        // 2. Links pointing to video hosters or embed pages
        doc.select("a[href]").mapNotNull { el ->
            val link = el.attr("href")
            val text = el.text().lowercase()
            link.takeIf {
                it.isNotBlank() && (
                    it.contains("embed") || it.contains("dailymotion") ||
                    it.contains("vidmoly") || it.contains("dood") ||
                    it.contains("voe.sx") || it.contains("streamtape") ||
                    it.contains("watchwrestling") || it.contains("play") ||
                    text.contains("server") || text.contains("watch") ||
                    text.contains("stream")
                )
            }
        }.forEach { embedUrls.add(it) }

        // 3. Any link with known video host domains
        doc.select("a[href*='dailymotion'], a[href*='vidmoly'], a[href*='dood'], a[href*='voe'], a[href*='streamtape'], a[href*='watchwrestling']")
            .mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() } }
            .forEach { embedUrls.add(it) }

        val distinct = embedUrls.distinct()
        
        // If we found real video embeds, return them.
        // Otherwise fall back to the post page URL — watchwrestling uses AJAX
        // to load video players dynamically, so the WebView loading the page
        // will execute JS and show the video player.
        distinct.ifEmpty { listOf(pageUrl) }
    }.getOrElse { e ->
        println("[WWE] Stream resolve failed: ${e.message}")
        emptyList()
    }
}
