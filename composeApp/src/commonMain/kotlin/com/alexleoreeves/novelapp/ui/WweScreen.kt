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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
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

val wweAccent = Color(0xFFFF1744)
private val wweDarkRed = Color(0xFFB71C1C)

@Composable
fun WweScreen(
    currentTheme: AppTheme,
    onEventSelected: (WweEvent) -> Unit
) {
    val httpClient = remember {
        platformHttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
            }
        }
    }
    val wweSource = remember { WweSource(httpClient) }

    var events by remember { mutableStateOf<List<WweEvent>>(emptyList()) }
    var brands by remember { mutableStateOf<List<WweBrand>>(emptyList()) }
    var selectedBrand by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf(0) } // 0=All, 1=Upcoming, 2=Live, 3=Past
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val refreshTrigger = remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        if (events.any { it.status == "LIVE" }) {
            delay(30_000)
            refreshTrigger.value += 1
        }
    }

    LaunchedEffect(selectedBrand, refreshTrigger.value, activeTab) {
        isLoading = true
        errorMessage = null
        val statusFilter = when (activeTab) {
            1 -> "UPCOMING"
            2 -> "LIVE"
            3 -> "COMPLETED"
            else -> ""
        }
        val fetched = wweSource.fetchEvents(brand = selectedBrand, status = statusFilter)
        events = fetched
        brands = wweSource.fetchBrands()
        if (fetched.isEmpty()) {
            errorMessage = "No WWE events found. Check back closer to event dates."
        }
        isLoading = false
    }

    val displayEvents = if (selectedBrand.isNotEmpty()) {
        events.filter { it.brand.equals(selectedBrand, ignoreCase = true) }
    } else events

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            events = wweSource.fetchEvents()
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
                        listOf(wweDarkRed, currentTheme.backgroundColor())
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "WWE",
                            style = MaterialTheme.typography.headlineLarge,
                            color = currentTheme.textColor(),
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "RAW · SmackDown · NXT · PPV",
                            style = MaterialTheme.typography.bodySmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                    Icon(
                        Icons.Default.FitnessCenter,
                        null,
                        tint = wweAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Filter tabs
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tabs = listOf("All", "Upcoming", "Live", "Past")
                    items(tabs.size) { idx ->
                        val selected = activeTab == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) wweAccent else currentTheme.cardColor())
                                .clickable { activeTab = idx }
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

                // Brand filter chips
                if (brands.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedBrand.isEmpty(),
                                onClick = { selectedBrand = "" },
                                label = { Text("All Brands") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = wweAccent,
                                    selectedLabelColor = Color.White,
                                    containerColor = currentTheme.cardColor(),
                                    labelColor = currentTheme.subTextColor()
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selectedBrand.isEmpty(),
                                    selectedBorderColor = wweAccent,
                                    borderColor = currentTheme.subTextColor().copy(0.3f)
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                        items(brands) { brand ->
                            FilterChip(
                                selected = selectedBrand.equals(brand.name, ignoreCase = true),
                                onClick = {
                                    selectedBrand = if (selectedBrand.equals(brand.name, ignoreCase = true)) "" else brand.name
                                },
                                label = { Text(brand.name.take(12)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = wweAccent,
                                    selectedLabelColor = Color.White,
                                    containerColor = currentTheme.cardColor(),
                                    labelColor = currentTheme.subTextColor()
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selectedBrand.equals(brand.name, ignoreCase = true),
                                    selectedBorderColor = wweAccent,
                                    borderColor = currentTheme.subTextColor().copy(0.3f)
                                ),
                                shape = RoundedCornerShape(20.dp)
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
                    Icon(Icons.Default.FitnessCenter, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(64.dp))
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
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayEvents, key = { "${it.eventId}_${refreshTrigger.value}" }) { event ->
                    WweEventCard(
                        event = event,
                        currentTheme = currentTheme,
                        onClick = { onEventSelected(event) }
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { httpClient.close() }
    }
}

@Composable
fun WweEventCard(
    event: WweEvent,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wwe_live")
    val liveAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "live_alpha"
    )

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top row: Brand + Type + Status
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.brand.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (event.brand.lowercase()) {
                            "raw" -> wweAccent
                            "smackdown" -> Color(0xFF2196F3)
                            "nxt" -> Color(0xFF9C27B0)
                            else -> wweAccent
                        }.copy(alpha = 0.2f)
                    ) {
                        Text(
                            event.brand,
                            color = when (event.brand.lowercase()) {
                                "raw" -> wweAccent
                                "smackdown" -> Color(0xFF2196F3)
                                "nxt" -> Color(0xFF9C27B0)
                                else -> wweAccent
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (event.status) {
                                "LIVE" -> Color(0xFF4CAF50).copy(alpha = if (event.status == "LIVE") liveAlpha * 0.3f else 0.15f)
                                "COMPLETED" -> Color(0xFF757575).copy(alpha = 0.15f)
                                else -> Color(0xFFFF9800).copy(alpha = 0.15f)
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (event.status == "LIVE") {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50).copy(alpha = liveAlpha))
                            )
                        }
                        Text(
                            when (event.status) {
                                "LIVE" -> "LIVE"
                                "COMPLETED" -> "Past"
                                else -> event.date.take(10).ifBlank { "Upcoming" }
                            },
                            color = when (event.status) {
                                "LIVE" -> Color(0xFF4CAF50)
                                "COMPLETED" -> Color(0xFF757575)
                                else -> Color(0xFFFF9800)
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Poster and event info row
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // If poster available, show it
                if (event.posterUrl.isNotBlank()) {
                    AsyncImage(
                        model = event.posterUrl,
                        contentDescription = event.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 80.dp, height = 110.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        event.title,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (event.eventType.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            event.eventType,
                            color = currentTheme.subTextColor(),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (event.venue.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.LocationOn, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(12.dp))
                            Text(event.venue.take(40), color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Match count
                    if (event.matches.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${event.matches.size} matches",
                            color = wweAccent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Synopsis
                    if (event.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            event.description.take(100),
                            color = currentTheme.subTextColor().copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Watch button
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (event.status) {
                        "LIVE" -> Color(0xFFB71C1C)
                        else -> Color(0xFF1A237E)
                    }
                )
            ) {
                Icon(
                    when (event.status) {
                        "LIVE" -> Icons.Default.PlayArrow
                        "COMPLETED" -> Icons.Default.Replay
                        else -> Icons.Default.Schedule
                    },
                    null, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when (event.status) {
                        "LIVE" -> "Watch Live"
                        "COMPLETED" -> "Watch Replay"
                        else -> "Details & Streams"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
