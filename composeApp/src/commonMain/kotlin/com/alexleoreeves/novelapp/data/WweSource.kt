package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig

private val wweJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * A WWE event/show — can be live, upcoming, or past.
 */
data class WweEvent(
    val id: String,
    val title: String,           // e.g. "WWE Raw", "SmackDown", "WrestleMania 40"
    val subtitle: String = "",   // e.g. "Night 1", "January 7, 2026"
    val description: String = "",
    val coverUrl: String = "",
    val isLive: Boolean = false,
    val status: String = "",     // "LIVE", "UPCOMING", "PAST"
    val eventType: String = "",  // "raw", "smackdown", "ppv"
    val streamUrls: List<String> = emptyList(),
    val date: String = "",
    val matchCount: Int = 0
) {
    val isPast: Boolean get() = status == "PAST"
    val isUpcoming: Boolean get() = status == "UPCOMING"
}

/**
 * WWE data source — fetches events from the server proxy.
 */
class WweApiSource(private val httpClient: HttpClient) {

    /**
     * Fetch all WWE events. Live events are always at the top.
     */
    suspend fun fetchEvents(): List<WweEvent> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/events").bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
        val items = root["data"]?.jsonArray?.mapNotNull { it.jsonObject.toWweEvent() }.orEmpty()
        val curated = if (items.isEmpty()) curatedWweEvents() else items
        // Sort: live first, then upcoming, then past (by date descending for past)
        curated.sortedWith(compareByDescending<WweEvent> { it.isLive }.thenByDescending { it.isUpcoming }.thenByDescending { it.date })
    }.getOrElse { error ->
        println("[WweAPI] Events fetch failed: ${error.message}")
        curatedWweEvents().sortedWith(compareByDescending<WweEvent> { it.isLive }.thenByDescending { it.isUpcoming }.thenByDescending { it.date })
    }

    /**
     * Resolve stream URLs for a given WWE event.
     */
    suspend fun resolveStreamUrls(event: WweEvent): List<String> = runCatching {
        val response = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/stream") {
            parameter("id", event.id)
            parameter("title", event.title)
            parameter("eventType", event.eventType)
        }.bodyAsText()
        val root = wweJson.parseToJsonElement(response).jsonObject
        val data = root["data"]
        when {
            data?.jsonPrimitive?.contentOrNull != null -> {
                data.jsonPrimitive.content.split("|").map { it.trim() }.filter { it.isNotBlank() }
            }
            data?.jsonArray != null -> {
                data.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }.getOrElse { error ->
        println("[WweAPI] Stream resolve failed for ${event.id}: ${error.message}")
        buildWweStreamUrls(event)
    }
}

/**
 * Curated WWE events used as fallback when the server has no API.
 */
fun curatedWweEvents(): List<WweEvent> {
    return listOf(
        // — Live / most recent first —
        WweEvent(
            id = "wwe-raw-live",
            title = "WWE Raw",
            subtitle = "Live Now — Monday Night Raw",
            description = "The flagship show of WWE. Watch Raw live.",
            coverUrl = "",
            isLive = true,
            status = "LIVE",
            eventType = "raw",
            date = "",
            matchCount = 0
        ),
        WweEvent(
            id = "wwe-smackdown-live",
            title = "WWE SmackDown",
            subtitle = "Live This Friday",
            description = "Friday night showdown. SmackDown live stream.",
            coverUrl = "",
            isLive = true,
            status = "LIVE",
            eventType = "smackdown",
            date = "",
            matchCount = 0
        ),
        // — Upcoming —
        WweEvent(
            id = "wwe-raw-next",
            title = "WWE Raw",
            subtitle = "Next Monday",
            description = "The next episode of Monday Night Raw.",
            coverUrl = "",
            status = "UPCOMING",
            eventType = "raw",
            date = "",
            matchCount = 5
        ),
        WweEvent(
            id = "wwe-smackdown-next",
            title = "WWE SmackDown",
            subtitle = "This Friday",
            description = "The next episode of Friday Night SmackDown.",
            coverUrl = "",
            status = "UPCOMING",
            eventType = "smackdown",
            date = "",
            matchCount = 5
        ),
        // — PPV —
        WweEvent(
            id = "wwe-ppv-40",
            title = "WrestleMania 41",
            subtitle = "PPV — Latest Edition",
            description = "The grandest stage of them all. Watch WrestleMania 41 replays.",
            coverUrl = "",
            status = "PAST",
            eventType = "ppv",
            date = "2026-04-06",
            matchCount = 14
        ),
        WweEvent(
            id = "wwe-ppv-39",
            title = "SummerSlam 2026",
            subtitle = "PPV — Replay Available",
            description = "The biggest party of the summer. Replays available.",
            coverUrl = "",
            status = "PAST",
            eventType = "ppv",
            date = "2026-08-03",
            matchCount = 12
        ),
        WweEvent(
            id = "wwe-ppv-38",
            title = "Royal Rumble 2026",
            subtitle = "PPV — Replay Available",
            description = "30-man over-the-top-rope Royal Rumble match. Relive the action.",
            coverUrl = "",
            status = "PAST",
            eventType = "ppv",
            date = "2026-01-27",
            matchCount = 8
        ),
        WweEvent(
            id = "wwe-ppv-37",
            title = "Survivor Series 2025",
            subtitle = "PPV — Replay Available",
            description = "Traditional 5-on-5 elimination matches. Watch the replays.",
            coverUrl = "",
            status = "PAST",
            eventType = "ppv",
            date = "2025-11-30",
            matchCount = 10
        ),
        WweEvent(
            id = "wwe-ppv-36",
            title = "Money in the Bank 2025",
            subtitle = "PPV — Replay Available",
            description = "The briefcase awaits. Relive the Money in the Bank ladder matches.",
            coverUrl = "",
            status = "PAST",
            eventType = "ppv",
            date = "2025-07-06",
            matchCount = 9
        ),
        // — Past episodes —
        WweEvent(
            id = "wwe-raw-past-1",
            title = "WWE Raw",
            subtitle = "March 31, 2026",
            description = "Monday Night Raw replay from March 31, 2026.",
            coverUrl = "",
            status = "PAST",
            eventType = "raw",
            date = "2026-03-31",
            matchCount = 6
        ),
        WweEvent(
            id = "wwe-smackdown-past-1",
            title = "WWE SmackDown",
            subtitle = "April 4, 2026",
            description = "Friday Night SmackDown replay from April 4, 2026.",
            coverUrl = "",
            status = "PAST",
            eventType = "smackdown",
            date = "2026-04-04",
            matchCount = 5
        ),
    )
}

/**
 * Build streaming URLs for a WWE event (used as fallback).
 */
fun buildWweStreamUrls(event: WweEvent): List<String> {
    val slug = event.title.lowercase()
        .replace("wwe ", "")
        .replace(" ", "-")
        .replace(Regex("[^a-z0-9-]"), "")
    val dateSlug = if (event.date.isNotBlank()) event.date else ""
    val urls = mutableListOf<String>()

    // Streaming sources
    urls.add("https://streamed.su/embed/wwe/$slug-live")
    urls.add("https://crackstreams.biz/stream/embed-wwe/${slug}-live")
    urls.add("https://sportshub.stream/embed/wwe/$slug")
    urls.add("https://embed.su/embed/sports?q=${event.title.replace(" ", "+")}+live+stream")

    if (dateSlug.isNotBlank()) {
        urls.add("https://watchwrestling.ch/watch/$slug-$dateSlug")
    }

    return urls
}

private fun JsonObject.toWweEvent(): WweEvent? {
    return try {
        WweEvent(
            id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            title = this["title"]?.jsonPrimitive?.contentOrNull ?: "",
            subtitle = this["subtitle"]?.jsonPrimitive?.contentOrNull ?: "",
            description = this["description"]?.jsonPrimitive?.contentOrNull ?: "",
            coverUrl = this["cover_url"]?.jsonPrimitive?.contentOrNull
                ?: this["coverUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            isLive = this["is_live"]?.jsonPrimitive?.booleanOrNull
                ?: this["isLive"]?.jsonPrimitive?.booleanOrNull ?: false,
            status = this["status"]?.jsonPrimitive?.contentOrNull ?: "PAST",
            eventType = this["event_type"]?.jsonPrimitive?.contentOrNull
                ?: this["eventType"]?.jsonPrimitive?.contentOrNull ?: "",
            streamUrls = emptyList(),
            date = this["date"]?.jsonPrimitive?.contentOrNull ?: "",
            matchCount = this["match_count"]?.jsonPrimitive?.intOrNull
                ?: this["matchCount"]?.jsonPrimitive?.intOrNull ?: 0
        )
    } catch (e: Exception) {
        null
    }
}
