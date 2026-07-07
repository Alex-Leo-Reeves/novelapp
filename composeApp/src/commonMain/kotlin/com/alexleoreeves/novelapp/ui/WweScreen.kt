package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.platformHttpClient
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

// ─────────────────────────────────────────────────────────────────────────────
//  WWE Tab Accent
// ─────────────────────────────────────────────────────────────────────────────
val wweAccent = Color(0xFFE94560)   // WWE red
private val wweAccent2 = Color(0xFFF72585)   // hot pink accent
private val wweLiveRed = Color(0xFFE53935)
private val wwePpvGold = Color(0xFFFFD700)
private val wweBgDark = Color(0xFF1A1A2E)

@Composable
fun WweScreen(
    currentTheme: AppTheme,
    onEventSelected: (WweEvent) -> Unit
) {
    val httpClient = remember {
        platformHttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
            }
        }
    }
    val wweApi = remember { WweApiSource(httpClient) }

    var events by remember { mutableStateOf<List<WweEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeFilter by remember { mutableStateOf(0) } // 0=All, 1=Live/Upcoming, 2=Raw, 3=SmackDown, 4=PPV
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyListState()

    // Auto-refresh every 30 seconds if any event is live
    val refreshTrigger = remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        if (events.any { it.isLive }) {
            delay(30_000)
            refreshTrigger.value += 1
        }
    }

    // Fetch events
    LaunchedEffect(refreshTrigger.value) {
        isLoading = true
        errorMessage = null
        val fetched = wweApi.fetchEvents()
        events = fetched
        if (fetched.isEmpty()) {
            errorMessage = "No WWE events available. Pull down to refresh."
        }
        isLoading = false
    }

    // Filter events
    val displayEvents = remember(events, activeFilter) {
        val filtered = when (activeFilter) {
            1 -> events.filter { it.isLive || it.isUpcoming }
            2 -> events.filter { it.eventType == "raw" }
            3 -> events.filter { it.eventType == "smackdown" }
            4 -> events.filter { it.eventType == "ppv" }
            else -> events
        }
        // Sort: live at top, then upcoming, then past by date desc
        filtered.sortedWith(compareByDescending<WweEvent> { it.isLive }
            .thenByDescending { it.isUpcoming }
            .thenByDescending { it.date })
    }

    // Live count
    val liveCount = events.count { it.isLive }

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            events = wweApi.fetchEvents()
            isLoading = false
            refreshTrigger.value += 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(wweBgDark, currentTheme.backgroundColor())
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "WWE",
                                style = MaterialTheme.typography.headlineLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Black
                            )
                            if (liveCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(wweLiveRed)
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        "$liveCount LIVE",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Text(
                            "Raw, SmackDown & Pay-Per-View",
                            style = MaterialTheme.typography.bodySmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                    Icon(
                        Icons.Default.Star,
                        null,
                        tint = wweAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Filter tabs: All | Live/Upcoming | Raw | SmackDown | PPV
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf("All", "Live/Upcoming", "Raw", "SmackDown", "PPV")
                    items(tabs.size) { idx ->
                        val selected = activeFilter == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) wweAccent else currentTheme.cardColor())
                                .clickable { activeFilter = idx }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                tabs[idx],
                                color = if (selected) Color.White else currentTheme.subTextColor(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        // ── Content ──────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = wweAccent)
            }
        } else if (errorMessage != null && displayEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Star, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(64.dp))
                    Text(errorMessage ?: "No events.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { refresh() },
                        colors = ButtonDefaults.buttonColors(containerColor = wweAccent)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }
                }
            }
        } else if (displayEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No events found", color = currentTheme.subTextColor())
            }
        } else {
            LazyColumn(
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Section headers for Live, Upcoming, Past
                val liveEvents = displayEvents.filter { it.isLive }
                val upcomingEvents = displayEvents.filter { it.isUpcoming }
                val pastEvents = displayEvents.filter { it.isPast }

                if (liveEvents.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(wweLiveRed))
                            Text(
                                "LIVE NOW",
                                color = wweLiveRed,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(liveEvents, key = { it.id }) { event ->
                        WweEventCard(
                            event = event,
                            currentTheme = currentTheme,
                            onClick = { onEventSelected(event) }
                        )
                    }
                }

                if (upcomingEvents.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "UPCOMING",
                            color = wweAccent2,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                    items(upcomingEvents, key = { it.id }) { event ->
                        WweEventCard(
                            event = event,
                            currentTheme = currentTheme,
                            onClick = { onEventSelected(event) }
                        )
                    }
                }

                if (pastEvents.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "PAST EVENTS",
                            color = currentTheme.subTextColor(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                    items(pastEvents, key = { it.id }) { event ->
                        WweEventCard(
                            event = event,
                            currentTheme = currentTheme,
                            onClick = { onEventSelected(event) }
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { httpClient.close() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  WWE Event Card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun WweEventCard(
    event: WweEvent,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wwe_live_pulse")
    val liveAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_alpha_wwe"
    )

    val bgColor = when {
        event.isLive -> Color(0xFF2D1B3E)
        event.eventType == "ppv" -> Color(0xFF1F1A2E)
        else -> currentTheme.cardColor()
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (event.isLive) 6.dp else 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Event type badge
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when (event.eventType) {
                            "raw" -> Color(0xFFE94560)
                            "smackdown" -> Color(0xFF4361EE)
                            "ppv" -> Color(0xFFF72585)
                            else -> currentTheme.cardColor()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (event.eventType) {
                        "ppv" -> Icons.Default.Star
                        else -> Icons.Default.LiveTv
                    },
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.title,
                        color = when {
                            event.isLive -> Color.White
                            event.eventType == "ppv" -> wwePpvGold
                            else -> currentTheme.textColor()
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (event.eventType == "ppv" && !event.isLive) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(wwePpvGold.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "PPV",
                                color = wwePpvGold,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (event.subtitle.isNotEmpty()) event.subtitle
                    else if (event.date.isNotEmpty()) event.date
                    else if (event.isLive) "Streaming now" else "Available",
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.matchCount > 0) {
                    Text(
                        "${event.matchCount} matches",
                        color = currentTheme.subTextColor().copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Status badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            event.isLive -> wweLiveRed.copy(alpha = liveAlpha)
                            event.isUpcoming -> wweAccent2.copy(alpha = 0.2f)
                            else -> currentTheme.subTextColor().copy(alpha = 0.1f)
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (event.isLive) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Text(
                            "LIVE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else if (event.isUpcoming) {
                        Text(
                            "SOON",
                            color = wweAccent2,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            null,
                            tint = currentTheme.subTextColor(),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
