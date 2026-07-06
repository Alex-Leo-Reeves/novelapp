package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig

private val footballJson = Json { ignoreUnknownKeys = true; isLenient = true }

data class FootballMatch(
    val fixtureId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogo: String = "",
    val awayLogo: String = "",
    val homeGoals: Int? = null,
    val awayGoals: Int? = null,
    val status: String = "",      // "LIVE", "HT", "FT", "NS", etc.
    val elapsed: Int? = null,     // minutes elapsed
    val leagueName: String = "",
    val leagueLogo: String = "",
    val leagueSeason: Int = 0,
    val matchDate: String = "",   // ISO date
    val matchTime: String = ""    // kickoff time
) {
    val isLive: Boolean get() = status == "LIVE"
    val isFinished: Boolean get() = status == "FT"
    val isNotStarted: Boolean get() = status == "NS" || status == "TBD"
    val scoreDisplay: String
        get() = "${homeGoals?.toString() ?: "-"} : ${awayGoals?.toString() ?: "-"}"
    val statusDisplay: String
        get() = when (status) {
            "LIVE" -> "LIVE"
            "HT" -> "HT"
            "FT" -> "FT"
            "NS" -> matchTime.take(5)
            "TBD" -> "TBD"
            "1H" -> "1H"
            "2H" -> "2H"
            "PEN" -> "Pen"
            else -> status
        }
    val statusColor: Long
        get() = when {
            isLive -> 0xFF4CAF50
            isFinished -> 0xFF9E9E9E
            else -> 0xFFFF9800
        }
}

data class FootballLeague(
    val id: Int,
    val name: String,
    val logo: String = "",
    val season: Int = 0,
    val country: String = ""
)

class FootballApiSource(private val httpClient: HttpClient) {

    /**
     * Fetch today's live fixtures and scheduled matches.
     * Delegates to our server proxy so the SPORTS_API_KEY stays server-side.
     */
    suspend fun fetchFixtures(date: String = ""): List<FootballMatch> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/fixtures") {
            if (date.isNotBlank()) parameter("date", date)
        }.bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toFootballMatch() }
            .orEmpty()
    }.getOrElse { error ->
        println("[FootballAPI] Fixtures fetch failed: ${error.message}")
        emptyList()
    }

    /**
     * Fetch live matches only (for auto-refresh).
     */
    suspend fun fetchLiveFixtures(): List<FootballMatch> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/fixtures") {
            parameter("live", "all")
        }.bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toFootballMatch() }
            .orEmpty()
    }.getOrElse { error ->
        println("[FootballAPI] Live fixtures failed: ${error.message}")
        emptyList()
    }

    /**
     * Fetch available leagues for the league filter UI.
     */
    suspend fun fetchLeagues(): List<FootballLeague> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/leagues").bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toFootballLeague() }
            .orEmpty()
    }.getOrElse { error ->
        println("[FootballAPI] Leagues fetch failed: ${error.message}")
        emptyList()
    }

    /**
     * Returns a playable stream URL (or embed URL) for the given fixture.
     * Our server resolves the match → league → broadcast channel → stream.
     */
    suspend fun resolveStreamUrl(fixtureId: Int): String? = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/stream") {
            parameter("fixture", fixtureId)
        }.bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        root["data"]?.jsonPrimitive?.contentOrNull
    }.getOrElse { error ->
        println("[FootballAPI] Stream resolve failed for fixture $fixtureId: ${error.message}")
        null
    }

    /**
     * Search fixtures by team or league name.
     */
    suspend fun searchFixtures(query: String): List<FootballMatch> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/search") {
            parameter("q", query)
        }.bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toFootballMatch() }
            .orEmpty()
    }.getOrElse { error ->
        println("[FootballAPI] Search failed: ${error.message}")
        emptyList()
    }

    private fun JsonObject.toFootballMatch(): FootballMatch? {
        val fixture = this["fixture"]?.jsonObject ?: return null
        val teams = this["teams"]?.jsonObject ?: return null
        val league = this["league"]?.jsonObject ?: return null
        val goals = this["goals"]?.jsonObject ?: return null
        val status = fixture["status"]?.jsonObject ?: return null

        val homeTeam = teams["home"]?.jsonObject ?: return null
        val awayTeam = teams["away"]?.jsonObject ?: return null

        return FootballMatch(
            fixtureId = fixture["id"]?.jsonPrimitive?.intOrNull ?: return null,
            homeTeam = homeTeam["name"]?.jsonPrimitive?.contentOrNull ?: "",
            awayTeam = awayTeam["name"]?.jsonPrimitive?.contentOrNull ?: "",
            homeLogo = homeTeam["logo"]?.jsonPrimitive?.contentOrNull ?: "",
            awayLogo = awayTeam["logo"]?.jsonPrimitive?.contentOrNull ?: "",
            homeGoals = goals["home"]?.jsonPrimitive?.intOrNull,
            awayGoals = goals["away"]?.jsonPrimitive?.intOrNull,
            status = status["short"]?.jsonPrimitive?.contentOrNull ?: "NS",
            elapsed = status["elapsed"]?.jsonPrimitive?.intOrNull,
            leagueName = league["name"]?.jsonPrimitive?.contentOrNull ?: "",
            leagueLogo = league["logo"]?.jsonPrimitive?.contentOrNull ?: "",
            leagueSeason = league["season"]?.jsonPrimitive?.intOrNull ?: 0,
            matchDate = fixture["date"]?.jsonPrimitive?.contentOrNull ?: "",
            matchTime = (fixture["date"]?.jsonPrimitive?.contentOrNull ?: "").let { dateStr ->
                runCatching {
                    dateStr.substringAfter("T").substringBefore("+").take(5)
                }.getOrDefault("")
            }
        )
    }

    private fun JsonObject.toFootballLeague(): FootballLeague? {
        return FootballLeague(
            id = this["id"]?.jsonPrimitive?.intOrNull ?: return null,
            name = this["name"]?.jsonPrimitive?.contentOrNull ?: "",
            logo = this["logo"]?.jsonPrimitive?.contentOrNull ?: "",
            season = this["season"]?.jsonPrimitive?.intOrNull ?: 0,
            country = this["country"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }
}
