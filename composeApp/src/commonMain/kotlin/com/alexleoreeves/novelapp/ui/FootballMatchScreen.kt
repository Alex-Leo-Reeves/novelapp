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
    var streamUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentFallbackIndex by remember { mutableIntStateOf(0) }
    var streamError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun resolveStream() {
        if (isLoadingStream) return
        scope.launch {
            isLoadingStream = true
            streamError = null
            streamUrl = null
            streamUrls = emptyList()
            currentFallbackIndex = 0
            val urls = footballApi.resolveStreamUrls(
                fixtureId = match.fixtureId,
                homeTeam = match.homeTeam,
                awayTeam = match.awayTeam,
                leagueName = match.leagueName
            )
            streamUrls = urls
            if (urls.isEmpty()) {
                streamError = "No stream available for this match yet. Try again closer to kickoff."
                isLoadingStream = false
                return@launch
            }
            currentFallbackIndex = 0
            streamUrl = urls[0]
            onPlayStream(urls[0], "${match.homeTeam} vs ${match.awayTeam}")
            isLoadingStream = false
        }
    }

    fun tryNextServer() {
        if (streamUrls.isEmpty()) {
            resolveStream()
            return
        }
        if (streamUrls.size == 1) {
            streamError = "Only one stream source available. Try the same source again."
            return
        }
        val nextIndex = if (currentFallbackIndex + 1 < streamUrls.size) currentFallbackIndex + 1 else 0
        currentFallbackIndex = nextIndex
        val nextUrl = streamUrls[nextIndex]
        streamUrl = nextUrl
        streamError = null
        onPlayStream(nextUrl, "${match.homeTeam} vs ${match.awayTeam}")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .verticalScroll(rememberScrollState())
    ) {
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
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
                            Surface(color = Color(0xFF1B5E20), shape = RoundedCornerShape(6.dp)) {
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
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

        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Watch Match", style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)

                    when {
                        isLoadingStream -> {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = footballAccent)
                                    Text("Resolving stream link...", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        streamUrl != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Check, null, tint = footballAccent, modifier = Modifier.size(18.dp))
                                Text("Stream ready!", color = footballAccent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                if (streamUrls.size > 1) {
                                    Spacer(Modifier.weight(1f))
                                    Text("Server ${currentFallbackIndex + 1}/${streamUrls.size}", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        streamError != null -> {
                            Surface(color = Color(0xFFFF6F00).copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                                Text(streamError ?: "", color = Color(0xFFFF6F00), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }

                    // Main watch button
                    Button(
                        onClick = {
                            if (streamUrl != null) {
                                onPlayStream(streamUrl!!, "${match.homeTeam} vs ${match.awayTeam}")
                            } else {
                                resolveStream()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (match.isLive) Color(0xFF1B5E20) else footballAccent),
                        enabled = !isLoadingStream
                    ) {
                        Icon(if (isLoadingStream) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isLoadingStream -> "Resolving..."
                                streamUrl != null -> "Launch Stream"
                                match.isLive -> "Find Live Stream"
                                match.isFinished -> "Find Replay"
                                else -> "Check Stream"
                            },
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    // Try another server button (shown when streams exist but user needs to cycle)
                    if (streamUrls.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { tryNextServer() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (streamUrls.size > 1) "Try Server ${(currentFallbackIndex % streamUrls.size) + 2}/${streamUrls.size}"
                                else "Retry Same Source",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (streamError != null) {
                        Text(
                            "Tip: Different stream sources work at different times. Tap 'Try' to cycle through available servers (${streamUrls.size} found).",
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = currentTheme.textColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
