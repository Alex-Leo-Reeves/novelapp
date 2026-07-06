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
//  Football Tab Accent
// ─────────────────────────────────────────────────────────────────────────────
val footballAccent = Color(0xFF00C853)
private val footballAccent2 = Color(0xFF00E676)
private val liveGreen = Color(0xFF4CAF50)
private val ftGrey = Color(0xFF757575)

@Composable
fun FootballHomeScreen(
    currentTheme: AppTheme,
    onMatchSelected: (FootballMatch) -> Unit
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
    val footballApi = remember { FootballApiSource(httpClient) }

    var fixtures by remember { mutableStateOf<List<FootballMatch>>(emptyList()) }
    var leagues by remember { mutableStateOf<List<FootballLeague>>(emptyList()) }
    var selectedLeagueId by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf(0) } // 0=All, 1=Live, 2=Today, 3=Upcoming
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<FootballMatch>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyListState()

    // Auto-refresh every 30 seconds if there are live matches
    val refreshTrigger = remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        if (fixtures.any { it.isLive }) {
            delay(30_000)
            refreshTrigger.value += 1
        }
    }

    // Fetch fixtures and leagues
    LaunchedEffect(selectedLeagueId, refreshTrigger.value, activeTab) {
        isLoading = true
        errorMessage = null
        if (activeTab == 3) {
            // Upcoming tab: fetch next 5 days of fixtures
            val upcoming = footballApi.fetchUpcomingFixtures()
            fixtures = upcoming
            if (upcoming.isEmpty()) {
                // Fallback: try today's fixtures and filter for not-started
                val todayFixtures = footballApi.fetchFixtures()
                val upcomingToday = todayFixtures.filter { it.isNotStarted }
                if (upcomingToday.isNotEmpty()) {
                    fixtures = upcomingToday
                    errorMessage = null
                } else {
                    errorMessage = "No upcoming matches scheduled in the next 5 days. Try the Today tab to see scheduled matches."
                }
            }
        } else {
            val fetched = footballApi.fetchFixtures()
            if (fetched.isEmpty()) {
                // Try fetching live matches as fallback
                val liveMatch = footballApi.fetchLiveFixtures()
                fixtures = liveMatch
                if (liveMatch.isEmpty()) {
                    errorMessage = "No matches found. Pull down to refresh or check your internet connection."
                }
            } else {
                fixtures = fetched
            }
        }
        val leaguesFetched = footballApi.fetchLeagues()
        leagues = leaguesFetched.take(20)
        isLoading = false
    }

    // Debounced search
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            isSearching = false
            searchResults = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        delay(400)
        searchResults = footballApi.searchFixtures(searchQuery)
        isSearching = false
    }

    // Filtered matches
    val displayMatches = if (searchQuery.length >= 2) {
        searchResults
    } else {
        val filtered = if (selectedLeagueId != null) {
            fixtures.filter { it.leagueName.contains(leagues.find { l -> l.id == selectedLeagueId }?.name ?: "", ignoreCase = true) }
        } else fixtures

        when (activeTab) {
            0 -> filtered
            1 -> filtered.filter { it.isLive || it.status == "HT" || it.status == "1H" || it.status == "2H" }
            2 -> filtered
            3 -> filtered.filter { it.isNotStarted }
            else -> filtered
        }
    }

    // Group by league
    val matchesByLeague = remember(displayMatches) {
        displayMatches.groupBy { it.leagueName }
    }

    // Refresh function
    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            fixtures = footballApi.fetchFixtures()
            if (fixtures.isEmpty()) {
                fixtures = footballApi.fetchLiveFixtures()
            }
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
                        listOf(Color(0xFF003300), currentTheme.backgroundColor())
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
                        Text(
                            "Football",
                            style = MaterialTheme.typography.headlineLarge,
                            color = currentTheme.textColor(),
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Live scores & streaming",
                            style = MaterialTheme.typography.bodySmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                    Icon(
                        Icons.Default.SportsSoccer,
                        null,
                        tint = footballAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search teams or leagues...", color = currentTheme.subTextColor()) },
                    leadingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = footballAccent
                            )
                        } else {
                            Icon(Icons.Default.Search, null, tint = footballAccent)
                        }
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null, tint = currentTheme.subTextColor())
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = footballAccent,
                        unfocusedBorderColor = currentTheme.subTextColor().copy(0.3f),
                        focusedTextColor = currentTheme.textColor(),
                        unfocusedTextColor = currentTheme.textColor(),
                        cursorColor = footballAccent,
                        unfocusedContainerColor = currentTheme.cardColor().copy(0.5f),
                        focusedContainerColor = currentTheme.cardColor().copy(0.5f)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.height(10.dp))

                // Filter tabs: All | Live | Today | Upcoming
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf("All", "Live", "Today", "Upcoming")
                    items(tabs.size) { idx ->
                        val selected = activeTab == idx
                        val label = if (idx == 1) {
                            val liveCount = fixtures.count { it.isLive }
                            "Live${if (liveCount > 0) " ($liveCount)" else ""}"
                        } else tabs[idx]

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) footballAccent else currentTheme.cardColor())
                                .clickable { activeTab = idx }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.White else currentTheme.subTextColor(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // League filter chips
                if (leagues.isNotEmpty() && searchQuery.length < 2) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedLeagueId == null,
                                onClick = { selectedLeagueId = null },
                                label = { Text("All Leagues") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = footballAccent,
                                    selectedLabelColor = Color.White,
                                    containerColor = currentTheme.cardColor(),
                                    labelColor = currentTheme.subTextColor()
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selectedLeagueId == null,
                                    selectedBorderColor = footballAccent,
                                    borderColor = currentTheme.subTextColor().copy(0.3f)
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                        items(leagues) { league ->
                            FilterChip(
                                selected = selectedLeagueId == league.id,
                                onClick = {
                                    selectedLeagueId = if (selectedLeagueId == league.id) null else league.id
                                },
                                label = { Text(league.name.take(14)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = footballAccent,
                                    selectedLabelColor = Color.White,
                                    containerColor = currentTheme.cardColor(),
                                    labelColor = currentTheme.subTextColor()
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selectedLeagueId == league.id,
                                    selectedBorderColor = footballAccent,
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
                CircularProgressIndicator(color = footballAccent)
            }
        } else if (errorMessage != null && displayMatches.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.SportsSoccer, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(64.dp))
                    Text(errorMessage ?: "No matches.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { refresh() },
                        colors = ButtonDefaults.buttonColors(containerColor = footballAccent)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }
                }
            }
        } else if (displayMatches.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches found", color = currentTheme.subTextColor())
            }
        } else {
            LazyColumn(
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                matchesByLeague.forEach { (leagueName, matches) ->
                    item {
                        Text(
                            leagueName.ifBlank { "Other" },
                            color = footballAccent,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }

                    items(matches, key = { "${it.fixtureId}_${it.status}_${refreshTrigger.value}" }) { match ->
                        FootballMatchCard(
                            match = match,
                            currentTheme = currentTheme,
                            onClick = { onMatchSelected(match) }
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
//  Football Match Card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FootballMatchCard(
    match: FootballMatch,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    // Live indicator animation
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val liveAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_alpha"
    )

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // League name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    match.leagueName.take(24),
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                // Status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                match.isLive -> liveGreen.copy(alpha = if (match.isLive) liveAlpha else 1f)
                                match.isFinished -> ftGrey
                                else -> Color(0xFFFF9800)
                            }.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (match.isLive) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(liveGreen.copy(alpha = liveAlpha))
                            )
                        }
                        Text(
                            match.statusDisplay,
                            color = when {
                                match.isLive -> liveGreen
                                match.isFinished -> ftGrey
                                else -> Color(0xFFFF9800)
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Home Team
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    match.homeTeam,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    match.scoreDisplay,
                    color = when {
                        match.isLive -> Color.White
                        match.isFinished -> currentTheme.subTextColor()
                        else -> currentTheme.textColor()
                    },
                    fontWeight = if (match.isLive || match.isFinished) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // Away Team
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    match.awayTeam,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Show elapsed time for live matches
                if (match.elapsed != null && match.isLive) {
                    Text(
                        "${match.elapsed}'",
                        color = liveGreen,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            // Watch button
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (match.isLive) Color(0xFF1B5E20) else Color(0xFF1A237E)
                )
            ) {
                Icon(
                    if (match.isLive) Icons.Default.PlayArrow else Icons.Default.Schedule,
                    null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (match.isLive) "Watch Live" else if (match.isFinished) "Watch Replay" else "Upcoming",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
