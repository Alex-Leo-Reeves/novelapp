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
fun WweMatchScreen(
    event: WweEvent,
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
    val wweApi = remember { WweApiSource(httpClient) }

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
            val urls = wweApi.resolveStreamUrls(event)
            streamUrls = urls
            if (urls.isEmpty()) {
                streamError = "No stream available for this event yet. Try again later."
                isLoadingStream = false
                return@launch
            }
            currentFallbackIndex = 0
            streamUrl = urls[0]
            onPlayStream(urls[0], event.title)
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
        onPlayStream(nextUrl, event.title)
    }

    val eventBgColor = when {
        event.isLive -> Color(0xFF2D1B3E)
        event.eventType == "ppv" -> Color(0xFF1F1A2E)
        else -> Color(0xFF1A1A2E)
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
                            listOf(eventBgColor, currentTheme.backgroundColor()),
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
                // Badge
                Surface(
                    color = when (event.eventType) {
                        "raw" -> Color(0xFFE94560)
                        "smackdown" -> Color(0xFF4361EE)
                        "ppv" -> Color(0xFFF72585)
                        else -> Color(0xFFE94560)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        event.eventType.uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    event.title,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                if (event.subtitle.isNotEmpty()) {
                    Text(
                        event.subtitle,
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = when {
                        event.isLive -> Color(0xFFB71C1C)
                        event.isUpcoming -> Color(0XFF6A1B9A)
                        else -> Color(0xFF333333)
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        when {
                            event.isLive -> "LIVE • Watch now"
                            event.isUpcoming -> "Upcoming"
                            else -> "WATCH REPLAY"
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
            // Event info
            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Event Details",
                        style = MaterialTheme.typography.titleMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    WweInfoRow("Event", event.title, currentTheme)
                    WweInfoRow("Type", event.eventType.uppercase(), currentTheme)
                    if (event.date.isNotEmpty()) WweInfoRow("Date", event.date, currentTheme)
                    if (event.matchCount > 0) WweInfoRow("Matches", "${event.matchCount}", currentTheme)
                    if (event.description.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            event.description,
                            color = currentTheme.subTextColor(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Watch section
            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Watch Event", style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)

                    when {
                        isLoadingStream -> {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = wweAccent)
                                    Text("Resolving stream link...", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        streamUrl != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Check, null, tint = wweAccent, modifier = Modifier.size(18.dp))
                                Text("Stream ready!", color = wweAccent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
                                onPlayStream(streamUrl!!, event.title)
                            } else {
                                resolveStream()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (event.isLive) Color(0xFFB71C1C) else wweAccent),
                        enabled = !isLoadingStream
                    ) {
                        Icon(if (isLoadingStream) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isLoadingStream -> "Resolving..."
                                streamUrl != null -> "Launch Stream"
                                event.isLive -> "Watch Live"
                                else -> "Watch Event"
                            },
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    // Try another server
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
private fun WweInfoRow(label: String, value: String, currentTheme: AppTheme) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = currentTheme.textColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
