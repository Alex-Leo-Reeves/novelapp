package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

private val footballJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Result of resolving a football match stream.
 * Server 2: Direct .m3u8 URL from backend → AnimePlayerScreen (ExoPlayer).
 * Server 1/3/4/5: Embed URL → MaServerPlayerScreen (WebView).
 */
sealed class StreamResult {
    /** A direct HLS/MP4 URL from the backend scraper — route to AnimePlayerScreen */
    data class Direct(val url: String) : StreamResult()
    /** An embed page URL — route to MaServerPlayerScreen */
    data class Embed(val url: String) : StreamResult()
}

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
        // ScoreBat embed provides actual video highlights/replays in an embeddable player.
        // The embed endpoint renders a proper video player, not a search/results page.
        // Build a direct embed URL using the fixture ID when available.
        val embedUrls = mutableListOf<String>()

        // Server 1: ScoreBat with team search — shows match-specific highlights
        val searchQuery = buildString {
            if (homeTeam.isNotBlank()) append(homeTeam.take(20).replace(" ", "+"))
            if (awayTeam.isNotBlank()) {
                if (isNotEmpty()) append("+vs+")
                append(awayTeam.take(20).replace(" ", "+"))
            }
        }
        if (searchQuery.isNotBlank()) {
            embedUrls.add("https://www.scorebat.com/embed/livescore/?search=$searchQuery")
        }

        // Server 2: Generic ScoreBat embed (fallback — shows whatever is featured)
        embedUrls.add("https://www.scorebat.com/embed/")

        // Server 3: Footybite direct match search
        embedUrls.add("https://footybite.to/?s=$searchQuery".takeIf { searchQuery.isNotBlank() } ?: "https://footybite.to/")

        // Server 4: SportSurge
        embedUrls.add("https://v2.sportsurge.net/search?query=$searchQuery".takeIf { searchQuery.isNotBlank() } ?: "https://v2.sportsurge.net/")

        return embedUrls.distinct()
    }

    /**
     * Server 2: Cricfy-style backend direct-stream resolver.
     * Calls POST /api/football/direct-stream on the main server to scrape
     * streaming aggregators for a raw .m3u8 URL.
     *
     * Returns StreamResult.Direct if a direct .m3u8 was found,
     * or null if the server couldn't resolve one via HTTP scraping.
     */
    suspend fun resolveServerDirectStream(
        homeTeam: String,
        awayTeam: String,
        leagueName: String = ""
    ): StreamResult? = runCatching {
        val body = buildJsonObject {
            put("homeTeam", homeTeam)
            put("awayTeam", awayTeam)
            put("leagueName", leagueName)
        }.toString()
        val raw = httpClient.post("${AppReleaseConfig.API_BASE_URL}/football/direct-stream") {
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            setBody(body)
        }.bodyAsText()
        if (raw.isBlank()) return@runCatching null
        val root = footballJson.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) return@runCatching null
        val data = root["data"]?.jsonObject ?: return@runCatching null
        val url = data["url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val isDirect = data["direct"]?.jsonPrimitive?.booleanOrNull ?: false
        if (isDirect && url.isNotBlank()) {
            StreamResult.Direct(url)
        } else null
    }.getOrElse { error ->
        println("[FootballDirect] Server scrape failed: ${error.message}")
        null
    }

    /**
     * Resolve a match stream using the ladder approach:
     * 1. Try Server 2 (backend .m3u8 scraper) → StreamResult.Direct
     * 2. If that fails, return the first embed URL → StreamResult.Embed
     */
    suspend fun resolveStream(
        homeTeam: String,
        awayTeam: String,
        leagueName: String = ""
    ): StreamResult {
        // Step 1: Try Server 2 — backend direct-stream scraper
        val direct = resolveServerDirectStream(homeTeam, awayTeam, leagueName)
        if (direct != null) return direct

        // Step 2: Fall back to embed URLs (Servers 1, 3, 4, 5)
        val embedUrls = resolveStreamUrls(
            fixtureId = 0,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            leagueName = leagueName
        )
        val firstEmbed = embedUrls.firstOrNull()
        if (firstEmbed != null) return StreamResult.Embed(firstEmbed)

        // No stream available at all
        return StreamResult.Embed("")
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
