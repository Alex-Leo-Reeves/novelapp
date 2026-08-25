package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.encodeURLPath
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
    val matchTime: String = "",   // kickoff time
    val streamHint: String = ""  // StreamEast match-page URL scraped by server
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
        // Primary: fetch from server (which scrapes StreamEast Asia + ESPN fallback)
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/fixtures").bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            parseServerFixtures(root["data"]?.jsonArray)
        } else {
            // Secondary fallback: ESPN direct
            val espnUrl = "$espnBaseUrl/all/scoreboard${if (date.isNotBlank()) "?dates=${date.replace("-", "")}" else ""}"
            parseEspnResponse(httpClient.get(espnUrl).bodyAsText())
        }
    }.getOrElse { error ->
        println("[FootballAPI] Fixtures fetch failed: ${error.message}")
        emptyList()
    }

    suspend fun fetchUpcomingFixtures(): List<FootballMatch> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/fixtures?upcoming=true").bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            parseServerFixtures(root["data"]?.jsonArray).filter { it.isNotStarted }.take(50)
        } else {
            val raw2 = httpClient.get("$espnBaseUrl/all/scoreboard?limit=100").bodyAsText()
            parseEspnResponse(raw2).filter { it.isNotStarted }.take(50)
        }
    }.getOrElse { error ->
        println("[FootballAPI] Upcoming fixtures failed: ${error.message}")
        emptyList()
    }

    suspend fun fetchLiveFixtures(): List<FootballMatch> = runCatching {
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/fixtures?live=all").bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            parseServerFixtures(root["data"]?.jsonArray).filter { it.isLive }
        } else {
            val raw2 = httpClient.get("$espnBaseUrl/all/scoreboard").bodyAsText()
            parseEspnResponse(raw2).filter { it.isLive }
        }
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
        leagueName: String = "",
        streamHint: String = ""
    ): String? {
        return resolveStreamUrls(fixtureId, homeTeam, awayTeam, leagueName, streamHint).firstOrNull()
    }

    suspend fun resolveStreamUrls(
        fixtureId: Int,
        homeTeam: String = "",
        awayTeam: String = "",
        leagueName: String = "",
        streamHint: String = ""
    ): List<String> {
        val embedUrls = mutableListOf<String>()

        // Step 1: Ask the server for its full prioritised embed URL list
        // (StreamEast Asia first, using the scraped streamHint if available)
        val serverUrls = runCatching {
            val resp = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/stream") {
                parameter("home", homeTeam)
                parameter("away", awayTeam)
                parameter("league", leagueName)
                if (streamHint.isNotBlank()) parameter("hint", streamHint)
            }
            val root = footballJson.parseToJsonElement(resp.bodyAsText()).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!ok) return@runCatching emptyList()
            val raw = root["data"]?.jsonPrimitive?.contentOrNull ?: ""
            raw.split("|").map { it.trim() }.filter { it.isNotBlank() }
        }.getOrElse { emptyList() }

        embedUrls.addAll(serverUrls)

        // Step 2: Client-side fallbacks if server returned nothing
        if (embedUrls.isEmpty()) {
            val homeSlug = homeTeam.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val awaySlug = awayTeam.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val searchQuery = buildString {
                if (homeTeam.isNotBlank()) append(homeTeam.take(20).replace(" ", "+"))
                if (awayTeam.isNotBlank()) { if (isNotEmpty()) append("+vs+"); append(awayTeam.take(20).replace(" ", "+")) }
            }

            // StreamEast domains
            val streamEastDomains = listOf(
                "https://streamseast.ws",
                "https://www.streamseast.ws",
                "https://streamseast.asia",
                "https://streamseast.me"
            )
            if (streamHint.isNotBlank()) embedUrls.add(streamHint)
            for (domain in streamEastDomains.take(2)) {
                if (fixtureId > 0 && homeSlug.isNotBlank() && awaySlug.isNotBlank()) {
                    embedUrls.add("$domain/soccer/$fixtureId/$homeSlug-vs-$awaySlug")
                }
                if (homeSlug.isNotBlank() && awaySlug.isNotBlank()) {
                    embedUrls.add("$domain/soccer/$homeSlug-vs-$awaySlug")
                    embedUrls.add("$domain/stream/football-$homeSlug-vs-$awaySlug")
                    embedUrls.add("$domain/football/$homeSlug-vs-$awaySlug-live")
                }
                embedUrls.add("$domain/soccer")
                embedUrls.add("$domain/football")
            }

            // ScoreBat Direct Match API
            val scorebatUrl = runCatching {
                val feed = httpClient.get("https://www.scorebat.com/video-api/v3/feed/").bodyAsText()
                val root = footballJson.parseToJsonElement(feed).jsonObject
                val matches = root["response"]?.jsonArray
                val exactMatch = matches?.firstOrNull { el ->
                    val title = el.jsonObject["title"]?.jsonPrimitive?.content ?: ""
                    homeTeam.isNotBlank() && title.contains(homeTeam, ignoreCase = true) &&
                    awayTeam.isNotBlank() && title.contains(awayTeam, ignoreCase = true)
                }
                exactMatch?.jsonObject?.get("matchviewUrl")?.jsonPrimitive?.content
            }.getOrNull()

            if (!scorebatUrl.isNullOrBlank()) {
                embedUrls.add(scorebatUrl)
            } else if (searchQuery.isNotBlank()) {
                embedUrls.add("https://www.scorebat.com/embed/livescore/?search=$searchQuery")
            }

            if (searchQuery.isNotBlank()) {
                embedUrls.add("https://v2.sportsurge.net/search?q=${searchQuery.replace("+", "%20")}")
            }
        }

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
        leagueName: String = "",
        fixtureId: Int = 0,
        streamHint: String = ""
    ): StreamResult {
        // Step 1: Try Server 2 — backend direct-stream scraper
        val direct = resolveServerDirectStream(homeTeam, awayTeam, leagueName)
        if (direct != null) return direct

        // Step 2: Fall back to embed URLs (StreamEast exact ID/hint primary, then fallbacks)
        val embedUrls = resolveStreamUrls(
            fixtureId = fixtureId,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            leagueName = leagueName,
            streamHint = streamHint
        )
        val firstEmbed = embedUrls.firstOrNull()
        if (firstEmbed != null) return StreamResult.Embed(firstEmbed)

        // No stream available at all
        return StreamResult.Embed("")
    }

    suspend fun searchFixtures(query: String): List<FootballMatch> = runCatching {
        // Try server search (StreamEast-backed) first
        val raw = httpClient.get("${AppReleaseConfig.API_BASE_URL}/football/search?q=${query.encodeURLPath()}").bodyAsText()
        val root = footballJson.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            parseServerFixtures(root["data"]?.jsonArray)
        } else {
            // Fallback: local filter on cached ESPN data
            val all = parseEspnResponse(httpClient.get("$espnBaseUrl/all/scoreboard").bodyAsText())
            all.filter {
                it.homeTeam.contains(query, ignoreCase = true) ||
                it.awayTeam.contains(query, ignoreCase = true) ||
                it.leagueName.contains(query, ignoreCase = true)
            }
        }
    }.getOrElse { emptyList() }

    /**
     * Parse the flat fixture objects returned by our server endpoints
     * (scraped from StreamEast Asia or built from ESPN JSON by the server).
     * The server normalises them so all fields are top-level strings/numbers.
     */
    private fun parseServerFixtures(data: JsonArray?): List<FootballMatch> {
        if (data == null) return emptyList()
        return data.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                FootballMatch(
                    fixtureId = obj["fixtureId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    homeTeam = obj["homeTeam"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null,
                    awayTeam = obj["awayTeam"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null,
                    homeLogo = obj["homeLogo"]?.jsonPrimitive?.contentOrNull ?: "",
                    awayLogo = obj["awayLogo"]?.jsonPrimitive?.contentOrNull ?: "",
                    homeGoals = obj["homeGoals"]?.jsonPrimitive?.intOrNull,
                    awayGoals = obj["awayGoals"]?.jsonPrimitive?.intOrNull,
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "NS",
                    elapsed = obj["elapsed"]?.jsonPrimitive?.intOrNull,
                    leagueName = obj["leagueName"]?.jsonPrimitive?.contentOrNull ?: "Football",
                    leagueLogo = obj["leagueLogo"]?.jsonPrimitive?.contentOrNull ?: "",
                    leagueSeason = obj["leagueSeason"]?.jsonPrimitive?.intOrNull ?: 0,
                    matchDate = obj["matchDate"]?.jsonPrimitive?.contentOrNull ?: "",
                    matchTime = obj["matchTime"]?.jsonPrimitive?.contentOrNull ?: "",
                    streamHint = obj["streamHint"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }.getOrNull()
        }
    }

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
                    val rawTime = dateIso.substringAfter("T").substringBefore("Z").take(5)
                    "$rawTime UTC"
                }.getOrDefault("00:00 UTC")

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
