package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

class WweSource(private val httpClient: HttpClient) {

    /**
     * Fetch upcoming/live/completed WWE events.
     * Delegates to our server proxy which scrapes WWE data from various sources
     * (same architecture as FootballApiSource).
     */
    suspend fun fetchEvents(brand: String = "", status: String = ""): List<WweEvent> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/events") {
            if (brand.isNotBlank()) parameter("brand", brand)
            if (status.isNotBlank()) parameter("status", status)
        }.bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toWweEvent() }
            .orEmpty()
    }.getOrElse { error ->
        println("[WWE] Events fetch failed: ${error.message}")
        emptyList()
    }

    /**
     * Fetch matches for a specific event.
     */
    suspend fun fetchMatches(eventId: String): List<WweMatch> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/matches") {
            parameter("eventId", eventId)
        }.bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toWweMatch() }
            .orEmpty()
    }.getOrElse { error ->
        println("[WWE] Matches fetch failed: ${error.message}")
        emptyList()
    }

    /**
     * Fetch available brands (RAW, SmackDown, NXT, PPV).
     */
    suspend fun fetchBrands(): List<WweBrand> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/brands").bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toWweBrand() }
            .orEmpty()
    }.getOrElse { error ->
        println("[WWE] Brands fetch failed: ${error.message}")
        emptyList()
    }

    /**
     * Search events by title, brand, or match participant.
     */
    suspend fun searchEvents(query: String): List<WweEvent> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/search") {
            parameter("q", query)
        }.bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toWweEvent() }
            .orEmpty()
    }.getOrElse { error ->
        println("[WWE] Search failed: ${error.message}")
        emptyList()
    }

    /**
     * Returns a playable stream URL for a given event/match.
     * Our server returns URLs to live streams or replay sources.
     */
    suspend fun resolveStreamUrl(eventId: String): String? = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/stream") {
            parameter("event", eventId)
        }.bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
        val data = root["data"]
        when {
            data?.jsonPrimitive?.contentOrNull != null -> data.jsonPrimitive.content
            data?.jsonArray != null -> {
                data.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .firstOrNull { it.isNotBlank() }
            }
            else -> null
        }
    }.getOrElse { error ->
        println("[WWE] Stream resolve failed for $eventId: ${error.message}")
        null
    }

    /**
     * Returns all available stream URLs for this event.
     */
    suspend fun resolveStreamUrls(eventId: String): List<String> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/wwe/stream") {
            parameter("event", eventId)
        }.bodyAsText()
        val root = wweJson.parseToJsonElement(raw).jsonObject
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
        println("[WWE] Stream URLs resolve failed: ${error.message}")
        emptyList()
    }

    private fun JsonObject.toWweEvent(): WweEvent? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null
        return WweEvent(
            eventId = id,
            title = title,
            brand = this["brand"]?.jsonPrimitive?.contentOrNull ?: "",
            eventType = this["eventType"]?.jsonPrimitive?.contentOrNull ?: "",
            date = this["date"]?.jsonPrimitive?.contentOrNull ?: "",
            time = this["time"]?.jsonPrimitive?.contentOrNull ?: "",
            status = this["status"]?.jsonPrimitive?.contentOrNull ?: "UPCOMING",
            venue = this["venue"]?.jsonPrimitive?.contentOrNull ?: "",
            description = this["description"]?.jsonPrimitive?.contentOrNull ?: "",
            matches = this["matches"]?.jsonArray?.mapNotNull { it.jsonObject.toWweMatch() } ?: emptyList(),
            posterUrl = this["posterUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            detailPageUrl = this["detailPageUrl"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun JsonObject.toWweMatch(): WweMatch? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull
            ?: this["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val participants = this["participants"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        return WweMatch(
            matchId = id,
            eventId = this["eventId"]?.jsonPrimitive?.contentOrNull ?: "",
            title = title,
            participants = participants,
            matchType = this["matchType"]?.jsonPrimitive?.contentOrNull ?: "",
            stipulation = this["stipulation"]?.jsonPrimitive?.contentOrNull ?: "",
            isTitleMatch = this["isTitleMatch"]?.jsonPrimitive?.booleanOrNull ?: false,
            titleName = this["titleName"]?.jsonPrimitive?.contentOrNull ?: "",
            status = this["status"]?.jsonPrimitive?.contentOrNull ?: "UPCOMING",
            winner = this["winner"]?.jsonPrimitive?.contentOrNull ?: "",
            result = this["result"]?.jsonPrimitive?.contentOrNull ?: "",
            detailUrl = this["detailUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            posterUrl = this["posterUrl"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun JsonObject.toWweBrand(): WweBrand? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = this["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return WweBrand(
            id = id,
            name = name,
            logo = this["logo"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }
}
