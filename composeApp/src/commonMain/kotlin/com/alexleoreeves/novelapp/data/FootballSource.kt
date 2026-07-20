package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

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
    val isLive: Boolean get() = status == "LIVE" || status == "HT" || status == "1H" || status == "2H" || status == "PEN"
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

    private val espnBaseUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    suspend fun fetchFixtures(date: String = ""): List<FootballMatch> = runCatching {
        val url = "$espnBaseUrl/all/scoreboard${if (date.isNotBlank()) "?dates=${date.replace("-", "")}" else ""}"
        val raw = httpClient.get(url).bodyAsText()
        parseEspnResponse(raw)
    }.getOrElse { error ->
        println("[FootballAPI] Fixtures fetch failed: ${error.message}")
        emptyList()
    }

    suspend fun fetchUpcomingFixtures(): List<FootballMatch> = runCatching {
        // Fetch next 3 days
        val raw = httpClient.get("$espnBaseUrl/all/scoreboard?limit=100&dates=20240101-20301231").bodyAsText()
        parseEspnResponse(raw).filter { it.isNotStarted }.take(50)
    }.getOrElse { error ->
        println("[FootballAPI] Upcoming fixtures failed: ${error.message}")
        emptyList()
    }

    suspend fun fetchLiveFixtures(): List<FootballMatch> = runCatching {
        val raw = httpClient.get("$espnBaseUrl/all/scoreboard").bodyAsText()
        parseEspnResponse(raw).filter { it.isLive }
    }.getOrElse { error ->
        println("[FootballAPI] Live fixtures failed: ${error.message}")
        emptyList()
    }

    suspend fun fetchLeagues(): List<FootballLeague> = runCatching {
        // Just return some popular leagues manually since ESPN leagues endpoint is complex
        listOf(
            FootballLeague(1, "English Premier League", "https://a.espncdn.com/i/leaguelogos/soccer/500/23.png"),
            FootballLeague(2, "Spanish LALIGA", "https://a.espncdn.com/i/leaguelogos/soccer/500/15.png"),
            FootballLeague(3, "Italian Serie A", "https://a.espncdn.com/i/leaguelogos/soccer/500/12.png"),
            FootballLeague(4, "German Bundesliga", "https://a.espncdn.com/i/leaguelogos/soccer/500/10.png"),
            FootballLeague(5, "French Ligue 1", "https://a.espncdn.com/i/leaguelogos/soccer/500/9.png"),
            FootballLeague(6, "UEFA Champions League", "https://a.espncdn.com/i/leaguelogos/soccer/500/2.png")
        )
    }.getOrElse { emptyList() }

    suspend fun resolveStreamUrl(
        fixtureId: Int,
        homeTeam: String = "",
        awayTeam: String = "",
        leagueName: String = ""
    ): String? {
        return resolveStreamUrls(fixtureId, homeTeam, awayTeam, leagueName).firstOrNull()
    }

    suspend fun resolveStreamUrls(
        fixtureId: Int,
        homeTeam: String = "",
        awayTeam: String = "",
        leagueName: String = ""
    ): List<String> {
        // Build team-specific search queries so the WebView lands on the right match
        val searchQuery = buildString {
            if (homeTeam.isNotBlank()) append(homeTeam.take(20).replace(" ", "+"))
            if (awayTeam.isNotBlank()) {
                if (isNotEmpty()) append("+vs+")
                append(awayTeam.take(20).replace(" ", "+"))
            }
        }.ifBlank { "live+football" }

        return listOf(
            // Server 1: SportSurge — popular free sports streaming aggregator
            "https://v2.sportsurge.net/search?query=$searchQuery",
            // Server 2: ScoreBat — legal highlight embeds for most matches
            "https://www.scorebat.com/embed/livescore/?search=$searchQuery",
            // Server 3: Footybite — sports streaming directory
            "https://footybite.to/?s=$searchQuery",
            // Server 4: Reddit Soccer Streams alternative
            "https://redditsoccerstreams.tv/?s=$searchQuery"
        )
    }

    suspend fun searchFixtures(query: String): List<FootballMatch> = runCatching {
        val raw = httpClient.get("$espnBaseUrl/all/scoreboard").bodyAsText()
        val all = parseEspnResponse(raw)
        all.filter {
            it.homeTeam.contains(query, ignoreCase = true) ||
            it.awayTeam.contains(query, ignoreCase = true) ||
            it.leagueName.contains(query, ignoreCase = true)
        }
    }.getOrElse { emptyList() }

    private fun parseEspnResponse(raw: String): List<FootballMatch> {
        val root = footballJson.parseToJsonElement(raw).jsonObject
        val events = root["events"]?.jsonArray ?: return emptyList()
        val leaguesData = root["leagues"]?.jsonArray

        return events.mapNotNull { element ->
            runCatching {
                val event = element.jsonObject
                val id = event["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                val dateIso = event["date"]?.jsonPrimitive?.content ?: ""
                val shortName = event["shortName"]?.jsonPrimitive?.content ?: ""

                val competition = event["competitions"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
                val competitors = competition["competitors"]?.jsonArray ?: return@mapNotNull null
                
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
                    val score = comp["score"]?.jsonPrimitive?.content?.toIntOrNull()

                    if (isHome) {
                        homeTeamName = name
                        homeTeamLogo = logo
                        homeScore = score
                    } else {
                        awayTeamName = name
                        awayTeamLogo = logo
                        awayScore = score
                    }
                }

                val statusObj = competition["status"]?.jsonObject
                val clock = statusObj?.get("clock")?.jsonPrimitive?.intOrNull
                val statusType = statusObj?.get("type")?.jsonObject
                val state = statusType?.get("state")?.jsonPrimitive?.content ?: "pre"
                val detail = statusType?.get("detail")?.jsonPrimitive?.content ?: ""
                val shortDetail = statusType?.get("shortDetail")?.jsonPrimitive?.content ?: ""

                val mappedStatus = when (state) {
                    "in" -> {
                        if (shortDetail.contains("HT", ignoreCase = true) || shortDetail.contains("Half", ignoreCase = true)) "HT"
                        else if (detail.contains("Pen", ignoreCase = true)) "PEN"
                        else "LIVE"
                    }
                    "post" -> "FT"
                    else -> "NS"
                }

                val seasonObj = event["season"]?.jsonObject
                val leagueObj = leaguesData?.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == seasonObj?.get("type")?.jsonPrimitive?.content }?.jsonObject
                val leagueName = leagueObj?.get("name")?.jsonPrimitive?.content ?: seasonObj?.get("slug")?.jsonPrimitive?.content ?: "International"
                val leagueLogo = leagueObj?.get("logos")?.jsonArray?.firstOrNull()?.jsonObject?.get("href")?.jsonPrimitive?.content ?: ""

                val matchTimeStr = runCatching {
                    dateIso.substringAfter("T").substringBefore("Z").take(5)
                }.getOrDefault("00:00")

                FootballMatch(
                    fixtureId = id,
                    homeTeam = homeTeamName.ifBlank { shortName.split("@").lastOrNull()?.trim() ?: "Home" },
                    awayTeam = awayTeamName.ifBlank { shortName.split("@").firstOrNull()?.trim() ?: "Away" },
                    homeLogo = homeTeamLogo,
                    awayLogo = awayTeamLogo,
                    homeGoals = homeScore,
                    awayGoals = awayScore,
                    status = mappedStatus,
                    elapsed = clock?.let { it / 60 },
                    leagueName = leagueName,
                    leagueLogo = leagueLogo,
                    leagueSeason = 2024,
                    matchDate = dateIso,
                    matchTime = matchTimeStr
                )
            }.getOrNull()
        }
    }
}
