package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Match detail screen with stream playback options.
 * When the user taps "Watch", we:
 * 1. Call our server to resolve a stream URL for this fixture
 * 2. Pass the URL to the existing AnimePlayerScreen
 */
@Composable
fun FootballMatchScreen(
    match: FootballMatch,
    currentTheme: AppTheme,
    onPlayStream: (streamUrl: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val httpClient = remember {
        platformHttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 25_000
            }
        }
    }
    val footballApi = remember { FootballApiSource(httpClient) }

    var isLoadingStream by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun resolveStream() {
        if (isLoadingStream) return
        scope.launch {
            isLoadingStream = true
            streamError = null
            streamUrl = null
            val url = footballApi.resolveStreamUrl(match.fixtureId)
            if (url != null) {
                streamUrl = url
                onPlayStream(url, "${match.homeTeam} vs ${match.awayTeam}")
            } else {
                streamError = "No stream available for this match yet. Try again closer to kickoff."
            }
            isLoadingStream = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .verticalScroll(rememberScrollState())
    ) {
        // ── Hero header with gradient ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF003300), currentTheme.backgroundColor()),
                            startY = 50f
                        )
                    )
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(10.dp)
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            // Match info centered
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // League badge
                Surface(
                    color = currentTheme.surfaceColor().copy(alpha = 0.86f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        match.leagueName,
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Score row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Home team
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            match.homeTeam,
                            color = if (match.homeGoals != null && match.awayGoals != null &&
                                match.homeGoals > match.awayGoals && match.isFinished
                            ) footballAccent else currentTheme.textColor(),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Score
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            match.scoreDisplay,
                            color = when {
                                match.isLive -> Color.White
                                match.isFinished -> currentTheme.subTextColor()
                                else -> currentTheme.textColor()
                            },
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.displayLarge
                        )
                        if (match.elapsed != null && match.isLive) {
                            Surface(
                                color = Color(0xFF1B5E20),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "${match.elapsed}'",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Away team
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            match.awayTeam,
                            color = if (match.homeGoals != null && match.awayGoals != null &&
                                match.awayGoals > match.homeGoals && match.isFinished
                            ) footballAccent else currentTheme.textColor(),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Status badge
                Surface(
                    color = when {
                        match.isLive -> Color(0xFF1B5E20)
                        match.isFinished -> Color(0xFF333333)
                        else -> Color(0xFF1A237E)
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        when {
                            match.isLive -> "LIVE • Watch now"
                            match.isFinished -> "FULL TIME"
                            match.isNotStarted -> "Starts at ${match.matchTime}"
                            else -> match.status
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ── Action section ────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Match info card
            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("League", match.leagueName, currentTheme)
                    InfoRow("Season", match.leagueSeason.toString(), currentTheme)
                    InfoRow("Date", match.matchDate.take(10), currentTheme)
                    InfoRow("Kickoff", match.matchTime.ifBlank { match.statusDisplay }, currentTheme)
                }
            }

            // Stream section
            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Watch Match",
                        style = MaterialTheme.typography.titleMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Bold
                    )

                    when {
                        isLoadingStream -> {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = footballAccent
                                    )
                                    Text(
                                        "Resolving stream link...",
                                        color = currentTheme.subTextColor(),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        streamUrl != null -> {
                            Text(
                                "Stream ready!",
                                color = footballAccent,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        streamError != null -> {
                            Surface(
                                color = Color(0xFFFF6F00).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    streamError ?: "",
                                    color = Color(0xFFFF6F00),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    // Watch button
                    Button(
                        onClick = {
                            if (streamUrl != null) {
                                onPlayStream(streamUrl!!, "${match.homeTeam} vs ${match.awayTeam}")
                            } else {
                                resolveStream()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (match.isLive) Color(0xFF1B5E20) else footballAccent
                        ),
                        enabled = !isLoadingStream
                    ) {
                        Icon(
                            if (isLoadingStream) Icons.Default.HourglassEmpty
                            else Icons.Default.PlayArrow,
                            null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isLoadingStream -> "Resolving..."
                                streamUrl != null -> "Launch Stream"
                                match.isLive -> "Find Live Stream"
                                match.isFinished -> "Find Replay"
                                else -> "Check Stream"
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    if (streamError != null) {
                        Text(
                            "Note: Live football streams are found by matching the fixture to available broadcast sources. If no stream is available, check back closer to match time.",
                            color = currentTheme.subTextColor().copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
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

@Composable
private fun InfoRow(label: String, value: String, currentTheme: AppTheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = currentTheme.textColor(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
