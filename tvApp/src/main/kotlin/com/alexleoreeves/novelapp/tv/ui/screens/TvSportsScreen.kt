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
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch

data class EspnMatch(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String = "-",
    val awayScore: String = "-",
    val status: String = "Scheduled",
    val leagueName: String = ""
)

data class WweEventItem(
    val id: String,
    val title: String,
    val brand: String = "WWE",
    val date: String = ""
)

@Composable
fun TvSportsScreen(
    account: Any? = null,
    onBack: () -> Unit
) {
    var activeTab by remember { mutableStateOf(0) }
    var matches by remember { mutableStateOf<List<EspnMatch>>(emptyList()) }
    var wweItems by remember { mutableStateOf<List<WweEventItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var selectedTitle by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            // ESPN Football — free API, no key
            val raw: String = client.get("https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard").body()
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject
            val events = root["events"]?.jsonArray ?: JsonArray(emptyList())
            matches = events.mapNotNull { evt ->
                val obj = evt.jsonObject
                val comps = obj["competitions"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
                val competitors = comps["competitors"]?.jsonArray ?: return@mapNotNull null
                var home = "", away = "", homeS = "-", awayS = "-"
                var status = "Scheduled"
                competitors.forEach { c ->
                    val co = c.jsonObject
                    val team = co["team"]?.jsonObject
                    val name = team?.get("displayName")?.jsonPrimitive?.contentOrNull ?: ""
                    val score = co["score"]?.jsonPrimitive?.contentOrNull ?: "-"
                    if (co["homeAway"]?.jsonPrimitive?.content == "home") { home = name; homeS = score }
                    else { away = name; awayS = score }
                }
                val st = comps["status"]?.jsonObject?.get("type")?.jsonObject
                val state = st?.get("state")?.jsonPrimitive?.contentOrNull ?: "pre"
                status = when (state) { "in" -> "LIVE"; "post" -> "FT"; else -> "Scheduled" }
                val league = obj["league"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                EspnMatch(evt["id"]?.jsonPrimitive?.contentOrNull ?: "", home, away, homeS, awayS, status, league)
            }
            // WWE — scrape wwe.com
            try {
                val html: String = client.get("https://www.wwe.com/events") {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                }.body()
                val titleRegex = Regex("""<h3[^>]*>([\s\S]*?)<\/h3>""", RegexOption.IGNORE_CASE)
                val dateRegex = Regex("""<time[^>]*datetime="([^"]+)"""", RegexOption.IGNORE_CASE)
                val allTitles = titleRegex.findAll(html).toList()
                wweItems = allTitles.mapIndexed { i, m ->
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
                }.filterNotNull().take(20)
            } catch (e: Exception) { wweItems = emptyList() }
        } catch (e: Exception) { e.printStackTrace() }
        isLoading = false
    }

    if (selectedUrl != null) {
        TvPlayerScreen(streamUrl = selectedUrl!!, title = selectedTitle, onBack = { selectedUrl = null })
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF06060A)).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 20.dp)) {
            val bi = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val bf by bi.collectIsFocusedAsState()
            Surface(onClick = onBack, shape = RoundedCornerShape(10.dp), color = if (bf) Color(0xFF1C1C2E) else Color.Transparent,
                border = if (bf) BorderStroke(2.dp, Color(0xFF8B5CF6)) else null, interactionSource = bi) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Back", color = Color.White)
                }
            }
            Text("Sports", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Black)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            listOf("Football", "WWE").forEachIndexed { index, label ->
                val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val foc by int.collectIsFocusedAsState()
                Button(onClick = { activeTab = index }, interactionSource = int,
                    colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == index) Color(0xFF8B5CF6) else Color(0xFF14141E)),
                    shape = RoundedCornerShape(8.dp)) {
                    Text(label, color = Color.White, fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF8B5CF6), modifier = Modifier.size(48.dp))
            }
        } else {
            when (activeTab) {
                0 -> {
                    Text("Football — ${matches.size} matches", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    if (matches.isEmpty()) {
                        Text("No fixtures right now. Try again later.", color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(matches) { m ->
                                val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                val foc by int.collectIsFocusedAsState()
                                val scale by animateFloatAsState(if (foc) 1.05f else 1f)
                                val isLive = m.status == "LIVE"
                                Card(onClick = {
                                    val query = "${m.homeTeam.replace(" ", "+")}+vs+${m.awayTeam.replace(" ", "+")}"
                                    selectedUrl = "https://v2.sportsurge.net/search?query=$query"
                                    selectedTitle = "${m.homeTeam} vs ${m.awayTeam}"
                                }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF1E1E2E) else Color(0xFF0C0C12)),
                                    border = if (foc) BorderStroke(2.dp, if (isLive) Color(0xFF4CAF50) else Color(0xFF8B5CF6)) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                                    interactionSource = int, modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        if (m.leagueName.isNotBlank()) {
                                            Text(m.leagueName, color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelSmall)
                                            Spacer(Modifier.height(8.dp))
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(m.homeTeam, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                                            Text("vs", color = Color.White.copy(0.5f), modifier = Modifier.padding(horizontal = 8.dp))
                                            Text(m.awayTeam, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${m.homeScore} : ${m.awayScore}", color = if (isLive) Color(0xFF4CAF50) else Color.White.copy(0.7f), fontWeight = FontWeight.Bold)
                                            Surface(shape = RoundedCornerShape(4.dp), color = if (isLive) Color(0xFF4CAF50) else Color(0xFF333333)) {
                                                Text(if (isLive) "● LIVE" else m.status, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Text("WWE — ${wweItems.size} events", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    if (wweItems.isEmpty()) {
                        Text("No WWE events loaded. Try again later.", color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(wweItems) { ev ->
                                val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                val foc by int.collectIsFocusedAsState()
                                val scale by animateFloatAsState(if (foc) 1.05f else 1f)
                                val brandColor = when (ev.brand) { "RAW" -> Color(0xFFFF1744); "SmackDown" -> Color(0xFF2196F3); "NXT" -> Color(0xFF9C27B0); else -> Color(0xFFE91E63) }
                                Card(onClick = {
                                    val q = ev.title.replace(" ", "+")
                                    selectedUrl = "https://watchwrestling.ae/search?q=$q"
                                    selectedTitle = ev.title
                                }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF1E1E2E) else Color(0xFF0C0C12)),
                                    border = if (foc) BorderStroke(2.dp, brandColor) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                                    interactionSource = int, modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }) {
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
