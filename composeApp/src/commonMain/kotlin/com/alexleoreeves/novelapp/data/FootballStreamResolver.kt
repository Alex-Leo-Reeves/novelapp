package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val resolverJson = Json { ignoreUnknownKeys = true }

/**
 * Client-side football stream resolver.
 * Fetches fixtures from ESPN (free API, no key needed) and resolves
 * stream URLs from free sports streaming aggregators.
 */
class FootballStreamResolver(private val httpClient: HttpClient) {

    private val espnBaseUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    /**
     * Fetch live and upcoming football fixtures from ESPN.
     */
    suspend fun fetchFixtures(): List<FootballMatch> = runCatching {
        val raw = httpClient.get("$espnBaseUrl/all/scoreboard").bodyAsText()
        parseEspnResponse(raw)
    }.getOrElse {
        println("[Football] ESPN fetch failed: ${it.message}")
        emptyList()
    }

    /**
     * Resolve a free stream URL for a given match.
     * These are embed URLs for WebView-based playback.
     */
    suspend fun resolveStreamUrls(
        homeTeam: String,
        awayTeam: String,
        leagueName: String = ""
    ): List<String> {
        val query = buildString {
            append(homeTeam.replace(" ", "+"))
            append("+vs+")
            append(awayTeam.replace(" ", "+"))
        }.ifBlank { "live+football" }

        return listOf(
            "https://v2.sportsurge.net/search?query=$query",
            "https://www.scorebat.com/embed/livescore/?search=$query",
            "https://footybite.to/?s=$query",
            "https://redditsoccerstreams.tv/?s=$query",
            "https://free-football.tv/?s=$query"
        )
    }

    suspend fun resolveStreamUrl(
        homeTeam: String,
        awayTeam: String,
        leagueName: String = ""
    ): String? = resolveStreamUrls(homeTeam, awayTeam, leagueName).firstOrNull()

    private fun parseEspnResponse(raw: String): List<FootballMatch> {
        return try {
            val root = resolverJson.parseToJsonElement(raw).jsonObject
            val events = root["events"]?.jsonArray ?: return emptyList()
            events.mapNotNull { element ->
                val event = element.jsonObject
                val id = event["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val dateIso = event["date"]?.jsonPrimitive?.content ?: ""
                val competition = event["competitions"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@mapNotNull null
                val competitors = competition["competitors"]?.jsonArray
                    ?: return@mapNotNull null

                var homeTeamName = ""
                var homeTeamLogo = ""
                var homeScore: Int? = null
                var awayTeamName = ""
                var awayTeamLogo = ""
                var awayScore: Int? = null

                competitors.forEach { compElem ->
                    val comp = compElem.jsonObject
                    val team = comp["team"]?.jsonObject
                    val isHome = comp["homeAway"]?.jsonPrimitive?.content == "home"
                    val name = team?.get("displayName")?.jsonPrimitive?.content ?: ""
                    val logo = team?.get("logo")?.jsonPrimitive?.content ?: ""
                    val score = comp["score"]?.jsonPrimitive?.intOrNull
                    if (isHome) {
                        homeTeamName = name; homeTeamLogo = logo; homeScore = score
                    } else {
                        awayTeamName = name; awayTeamLogo = logo; awayScore = score
                    }
                }

                val statusObj = competition["status"]?.jsonObject
                val state = statusObj?.get("type")?.jsonObject?.get("state")?.jsonPrimitive?.content ?: "pre"
                val detail = statusObj?.get("details")?.jsonPrimitive?.content
                    ?: statusObj?.get("type")?.jsonObject?.get("shortDetail")?.jsonPrimitive?.content
                    ?: ""

                val mappedStatus = when (state) {
                    "in" -> if (detail.contains("HT", ignoreCase = true) || detail.contains("Half", ignoreCase = true)) "HT" else "LIVE"
                    "post" -> "FT"
                    else -> "NS"
                }

                val leagueObj = event["league"]?.jsonObject
                val leagueName = leagueObj?.get("name")?.jsonPrimitive?.content ?: "International"

                FootballMatch(
                    fixtureId = id,
                    homeTeam = homeTeamName.ifBlank { "Home" },
                    awayTeam = awayTeamName.ifBlank { "Away" },
                    homeLogo = homeTeamLogo,
                    awayLogo = awayTeamLogo,
                    homeGoals = homeScore,
                    awayGoals = awayScore,
                    status = mappedStatus,
                    leagueName = leagueName,
                    matchDate = dateIso,
                    matchTime = dateIso.substringAfter("T").take(5)
                )
            }
        } catch (e: Exception) {
            println("[Football] Parse failed: ${e.message}")
            emptyList()
        }
    }
}
