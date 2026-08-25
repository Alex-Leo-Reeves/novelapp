package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import com.alexleoreeves.novelapp.data.resolveFootballTvStreamList
import com.alexleoreeves.novelapp.data.fetchWweStream
import com.alexleoreeves.novelapp.platform.AppReleaseConfig

data class EspnMatch(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String = "-",
    val awayScore: String = "-",
    val status: String = "Scheduled",
    val leagueName: String = "",
    val matchTime: String = "",
    val streamHint: String = ""
) {
    val isLive: Boolean get() = status == "LIVE" || status == "HT" || status == "1H" || status == "2H"
    val isFinished: Boolean get() = status == "FT"
    val isNotStarted: Boolean get() = !isLive && !isFinished
}

data class WweEventItem(
    val id: String,
    val title: String,
    val brand: String = "WWE",
    val date: String = "",
    val detailUrl: String = ""
)

private val sportsJson = Json { ignoreUnknownKeys = true; isLenient = true }

// ─ Tab enum ───────────────────────────────────────────────────────────────────

private enum class MatchTab(val label: String) {
    LIVE("Live"), TODAY("Today"), UPCOMING("Upcoming"), FINISHED("Finished")
}

@Composable
fun TvSportsScreen(
    account: Any? = null,
    onPlay: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    var activeSection by remember { mutableStateOf(0) }   // 0 = Football, 1 = WWE
    var activeTab    by remember { mutableStateOf(MatchTab.LIVE) }
    var allMatches   by remember { mutableStateOf<List<EspnMatch>>(emptyList()) }
    var wweItems     by remember { mutableStateOf<List<WweEventItem>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var loadError    by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()

    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(sportsJson) }
        }
    }

    // ── Load football fixtures from our backend ─────────────────────────────
    fun loadFixtures() {
        scope.launch {
            isLoading = true; loadError = false
            try {
                val raw = client.get("${AppReleaseConfig.API_BASE_URL}/football/fixtures")
                    .bodyAsText()
                val root = sportsJson.parseToJsonElement(raw).jsonObject
                val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                if (ok) {
                    val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
                    allMatches = data.mapNotNull { el ->
                        runCatching {
                            val obj = el.jsonObject
                            EspnMatch(
                                id          = obj["fixtureId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                                homeTeam    = obj["homeTeam"]?.jsonPrimitive?.content?.ifBlank { null } ?: return@mapNotNull null,
                                awayTeam    = obj["awayTeam"]?.jsonPrimitive?.content?.ifBlank { null } ?: return@mapNotNull null,
                                homeScore   = obj["homeGoals"]?.jsonPrimitive?.content ?: "-",
                                awayScore   = obj["awayGoals"]?.jsonPrimitive?.content ?: "-",
                                status      = obj["status"]?.jsonPrimitive?.content ?: "NS",
                                leagueName  = obj["leagueName"]?.jsonPrimitive?.content ?: "",
                                matchTime   = obj["matchTime"]?.jsonPrimitive?.content ?: "",
                                streamHint  = obj["streamHint"]?.jsonPrimitive?.content ?: ""
                            )
                        }.getOrNull()
                    }
                } else {
                    // Fallback: ESPN direct
                    val espnRaw = client.get(
                        "https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard"
                    ).bodyAsText()
                    allMatches = parseEspnDirect(espnRaw)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loadError = true
                // Try ESPN as last resort
                try {
                    val espnRaw = client.get(
                        "https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard"
                    ).bodyAsText()
                    allMatches = parseEspnDirect(espnRaw)
                    loadError = allMatches.isEmpty()
                } catch (_: Exception) { }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadFixtures()
        // WWE
        try {
            // Check for backend WWE first
            val wweRaw = client.get("${AppReleaseConfig.API_BASE_URL}/wwe/events").bodyAsText()
            val wweRoot = sportsJson.parseToJsonElement(wweRaw).jsonObject
            val wweData = wweRoot["data"]?.jsonArray
            if (!wweData.isNullOrEmpty()) {
                wweItems = wweData.mapNotNull { el ->
                    val obj = el.jsonObject
                    WweEventItem(
                        id = obj["eventId"]?.jsonPrimitive?.content ?: "",
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        brand = obj["brand"]?.jsonPrimitive?.content ?: "WWE",
                        date = obj["date"]?.jsonPrimitive?.content ?: "",
                        detailUrl = obj["detailPageUrl"]?.jsonPrimitive?.content ?: ""
                    )
                }
            } else {
                val html = client.get("https://www.wwe.com/events") {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                }.bodyAsText()
                val titleRegex = Regex("""<h3[^>]*>([\s\S]*?)<\/h3>""", RegexOption.IGNORE_CASE)
                val dateRegex  = Regex("""<time[^>]*datetime="([^"]+)"""", RegexOption.IGNORE_CASE)
                wweItems = titleRegex.findAll(html).mapIndexed { i, m ->
                    val t = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                    if (t.isNotBlank()) {
                        val date = dateRegex.find(html, m.range.first)?.groupValues?.getOrNull(1) ?: ""
                        val brand = when {
                            t.contains("Raw", true) -> "RAW"
                            t.contains("SmackDown", true) -> "SmackDown"
                            t.contains("NXT", true) -> "NXT"
                            else -> "WWE"
                        }
                        WweEventItem("wwe_$i", t, brand, date)
                    } else null
                }.filterNotNull().take(20).toList()
            }
        } catch (_: Exception) { wweItems = emptyList() }
    }

    // ── Filtered match lists by tab ─────────────────────────────────────────
    val filteredMatches = remember(allMatches, activeTab) {
        when (activeTab) {
            MatchTab.LIVE     -> allMatches.filter { it.isLive }
            MatchTab.TODAY    -> allMatches  // all today's matches
            MatchTab.UPCOMING -> allMatches.filter { it.isNotStarted }
            MatchTab.FINISHED -> allMatches.filter { it.isFinished }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(24.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 20.dp)
        ) {
            val bi = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val bf by bi.collectIsFocusedAsState()
            Surface(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                color = if (bf) Color(0xFF1C1C2E) else Color.Transparent,
                border = if (bf) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                interactionSource = bi
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Back", color = Color.White)
                }
            }
            Text(
                "Sports",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.weight(1f))
            // Refresh button
            val ri = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val rf by ri.collectIsFocusedAsState()
            Surface(
                onClick = { loadFixtures() },
                shape = RoundedCornerShape(8.dp),
                color = if (rf) Color(0xFF1C1C2E) else Color.Transparent,
                border = if (rf) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                interactionSource = ri
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = if (rf) Color(0xFF00BFFF) else Color.White.copy(0.6f),
                    modifier = Modifier.padding(8.dp).size(22.dp)
                )
            }
        }

        // ── Section tabs (Football / WWE) ───────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            listOf("⚽  Football", "🏆  WWE").forEachIndexed { index, label ->
                val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val foc by int.collectIsFocusedAsState()
                Button(
                    onClick = { activeSection = index },
                    interactionSource = int,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeSection == index) Color(0xFF00BFFF) else Color(0xFF14141E)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(label, color = Color.White, fontWeight = if (activeSection == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        if (activeSection == 0) {
            // ── Match status filter tabs ────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                MatchTab.values().forEach { tab ->
                    val count = when (tab) {
                        MatchTab.LIVE     -> allMatches.count { it.isLive }
                        MatchTab.TODAY    -> allMatches.size
                        MatchTab.UPCOMING -> allMatches.count { it.isNotStarted }
                        MatchTab.FINISHED -> allMatches.count { it.isFinished }
                    }
                    val isActive = activeTab == tab
                    val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val foc by int.collectIsFocusedAsState()
                    val tabColor = when (tab) {
                        MatchTab.LIVE     -> Color(0xFF4CAF50)
                        MatchTab.TODAY    -> Color(0xFF00BFFF)
                        MatchTab.UPCOMING -> Color(0xFFFF9800)
                        MatchTab.FINISHED -> Color(0xFF9E9E9E)
                    }
                    Surface(
                        onClick = { activeTab = tab },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isActive) tabColor else Color(0xFF12121C),
                        border = BorderStroke(1.dp, if (foc) Color.White else if (isActive) tabColor else Color.White.copy(0.1f)),
                        interactionSource = int
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (tab == MatchTab.LIVE && count > 0) {
                                val pulse by rememberInfiniteTransition().animateFloat(
                                    initialValue = 0.4f, targetValue = 1f,
                                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
                                )
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(Color.White.copy(pulse), CircleShape)
                                )
                            }
                            Text(
                                "${tab.label} ($count)",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // ── Content ─────────────────────────────────────────────────────────
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color(0xFF00BFFF), modifier = Modifier.size(48.dp))
                    Text("Loading matches…", color = Color.White.copy(0.5f))
                }
            }
        } else {
            when (activeSection) {
                // ── Football ─────────────────────────────────────────────
                0 -> {
                    if (filteredMatches.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No ${activeTab.label} matches right now.", color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodyLarge)
                                if (loadError) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Could not reach server. Check your connection.", color = Color(0xFFFF5555).copy(0.8f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(320.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 32.dp)
                        ) {
                            items(filteredMatches, key = { it.id }) { m ->
                                FootballMatchCard(
                                    match = m,
                                    onPlay = { home, away, league, hint ->
                                        // Resolve the embed URL list in a coroutine, then launch the first one
                                        scope.launch {
                                            val urls = try {
                                                resolveFootballTvStreamList(home, away, league)
                                            } catch (_: Exception) {
                                                // Client-side fallback
                                                val h = home.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                                                val a = away.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                                                buildList {
                                                    if (hint.isNotBlank()) add(hint)
                                                    add("https://streamseast.asia/stream/football-$h-vs-$a")
                                                    add("https://streamseast.asia/football")
                                                    add("https://v2.sportsurge.net/search?q=${(home + " vs " + away).replace(" ", "+")}")
                                                }
                                            }
                                            val first = urls.firstOrNull()
                                            if (!first.isNullOrBlank()) {
                                                onPlay(first, "$home vs $away")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                // ── WWE ──────────────────────────────────────────────────
                1 -> {
                    Text(
                        "WWE — ${wweItems.size} events",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    if (wweItems.isEmpty()) {
                        Text("No WWE events loaded. Try again later.", color = Color.White.copy(0.5f))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(300.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 32.dp)
                        ) {
                            items(wweItems, key = { it.id }) { ev ->
                                val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                val foc by int.collectIsFocusedAsState()
                                val scale by animateFloatAsState(if (foc) 1.05f else 1f)
                                val brandColor = when (ev.brand) {
                                    "RAW" -> Color(0xFFFF1744)
                                    "SmackDown" -> Color(0xFF2196F3)
                                    "NXT" -> Color(0xFF9C27B0)
                                    else -> Color(0xFFE91E63)
                                }
                                Card(
                                    onClick = {
                                        scope.launch {
                                            val stream = fetchWweStream(ev.id, ev.title, ev.detailUrl)
                                            if (!stream.isNullOrBlank()) {
                                                onPlay(stream, ev.title)
                                            } else {
                                                val q = ev.title.replace(" ", "+")
                                                onPlay("https://watchwrestling.ae/?s=$q", ev.title)
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF1E1E2E) else Color(0xFF0C0C12)),
                                    border = if (foc) BorderStroke(2.dp, brandColor) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                                    interactionSource = int,
                                    modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(ev.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(8.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = brandColor.copy(0.2f)) {
                                            Text(ev.brand, color = brandColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                        if (ev.date.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(ev.date.take(10), color = Color.White.copy(0.4f), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Individual Match Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FootballMatchCard(
    match: EspnMatch,
    onPlay: (home: String, away: String, league: String, hint: String) -> Unit
) {
    val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val foc by int.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (foc) 1.04f else 1f)
    val isLive = match.isLive
    val borderColor = when {
        isLive && foc -> Color(0xFF4CAF50)
        foc           -> Color(0xFF00BFFF)
        isLive        -> Color(0xFF4CAF50).copy(0.4f)
        else          -> Color.White.copy(0.05f)
    }

    Card(
        onClick = { onPlay(match.homeTeam, match.awayTeam, match.leagueName, match.streamHint) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF1A1A2E) else Color(0xFF0D0D14)),
        border = BorderStroke(if (foc || isLive) 2.dp else 1.dp, borderColor),
        interactionSource = int,
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // League row
            if (match.leagueName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.SportsSoccer, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(12.dp))
                    Text(
                        match.leagueName,
                        color = Color.White.copy(0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            // Teams + score
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    match.homeTeam,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 10.dp)) {
                    if (match.isLive || match.isFinished) {
                        Text(
                            "${match.homeScore} : ${match.awayScore}",
                            color = if (isLive) Color(0xFF4CAF50) else Color.White.copy(0.7f),
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text("vs", color = Color.White.copy(0.4f), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(
                    match.awayTeam,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            // Status + time
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status badge
                val (statusLabel, statusBg) = when {
                    isLive && match.status == "HT" -> Pair("HT", Color(0xFFFF9800))
                    isLive                          -> Pair("● LIVE", Color(0xFF4CAF50))
                    match.isFinished                -> Pair("FT", Color(0xFF555566))
                    match.status == "TBD"           -> Pair("TBD", Color(0xFF444455))
                    else                            -> Pair("Upcoming", Color(0xFF2C2C3E))
                }
                Surface(shape = RoundedCornerShape(6.dp), color = statusBg) {
                    Text(
                        statusLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                // Kickoff / elapsed time
                if (match.matchTime.isNotBlank()) {
                    Text(
                        match.matchTime.take(10),
                        color = Color.White.copy(0.4f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ESPN direct fallback parser (used when backend is unreachable)
// ─────────────────────────────────────────────────────────────────────────────

private fun parseEspnDirect(raw: String): List<EspnMatch> = try {
    val root = sportsJson.parseToJsonElement(raw).jsonObject
    val events = root["events"]?.jsonArray ?: return emptyList()
    events.mapNotNull { el ->
        val obj = el.jsonObject
        val comp = obj["competitions"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
        val competitors = comp["competitors"]?.jsonArray ?: return@mapNotNull null
        var home = ""; var away = ""; var homeS = "-"; var awayS = "-"
        for (c in competitors) {
            val co = c.jsonObject
            val name = co["team"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull ?: ""
            val score = co["score"]?.jsonPrimitive?.contentOrNull ?: "-"
            if (co["homeAway"]?.jsonPrimitive?.content == "home") { home = name; homeS = score }
            else { away = name; awayS = score }
        }
        val state = comp["status"]?.jsonObject?.get("type")?.jsonObject?.get("state")?.jsonPrimitive?.contentOrNull ?: "pre"
        val status = when (state) { "in" -> "LIVE"; "post" -> "FT"; else -> "NS" }
        val league = obj["league"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val dateIso = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
        val time = if (dateIso.contains("T")) dateIso.substringAfter("T").take(5) + " UTC" else ""
        EspnMatch(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            homeTeam = home, awayTeam = away,
            homeScore = homeS, awayScore = awayS,
            status = status, leagueName = league, matchTime = time
        )
    }
} catch (e: Exception) {
    println("[Football] ESPN parse failed: ${e.message}")
    emptyList()
}
