package com.alexleoreeves.novelapp.ui

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
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
 * Stream types enum — corresponds to WweStreamResult sealed class.
 */
private enum class StreamPlaybackType {
    /**
     * Option A: Embed URL → MaServerPlayerScreen (WebView)
     * For sites like doodstream, vidmoly, streamtape, etc.
     */
    EMBED,

    /**
     * Option B: Direct HLS URL → AnimePlayerScreen (ExoPlayer)
     * For .m3u8 direct stream URLs
     */
    DIRECT
}

@Composable
fun WweMatchScreen(
    event: WweEvent,
    currentTheme: AppTheme,
    onPlayEmbed: (embedUrl: String, title: String) -> Unit,
    onPlayDirect: (streamUrl: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val httpClient = remember {
        platformHttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 25_000
            }
        }
    }
    val wweSource = remember { WweSource(httpClient) }

    var isResolving by remember { mutableStateOf(false) }
    var resolvedResult by remember { mutableStateOf<WweStreamResult?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var streamPlaybackType by remember { mutableStateOf(StreamPlaybackType.EMBED) }
    var matches by remember { mutableStateOf(event.matches) }
    var isLoadingMatches by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Fetch full match list if not loaded
    LaunchedEffect(event.eventId) {
        if (matches.isEmpty()) {
            isLoadingMatches = true
            matches = wweSource.fetchMatches(event.eventId)
            isLoadingMatches = false
        }
    }

    fun resolveStream() {
        if (isResolving) return
        scope.launch {
            isResolving = true
            streamError = null
            resolvedResult = null

            // Use the full pipeline: try Direct first, then Embed, then error
            val result = wweSource.resolveStream(event.eventId, event.title)
            if (result != null) {
                resolvedResult = result
                // Auto-launch based on type
                when (result) {
                    is WweStreamResult.Direct -> {
                        streamPlaybackType = StreamPlaybackType.DIRECT
                        onPlayDirect(result.url, event.title)
                    }
                    is WweStreamResult.Embed -> {
                        streamPlaybackType = StreamPlaybackType.EMBED
                        onPlayEmbed(result.url, event.title)
                    }
                }
            } else {
                streamError = "No stream URL could be resolved. Try again later."
            }
            isResolving = false
        }
    }

    fun launchStream(wweResult: WweStreamResult) {
        when (wweResult) {
            is WweStreamResult.Direct -> onPlayDirect(wweResult.url, event.title)
            is WweStreamResult.Embed -> onPlayEmbed(wweResult.url, event.title)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .verticalScroll(rememberScrollState())
    ) {
        // ── Hero Image ────────────────────────────────────────────────────
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
                            listOf(Color(0xFFB71C1C), currentTheme.backgroundColor()),
                            startY = 50f
                        )
                    )
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    
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
                if (event.brand.isNotBlank()) {
                    Surface(
                        color = currentTheme.surfaceColor().copy(alpha = 0.86f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            event.brand,
                            color = wweAccent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    event.title,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.eventType.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        event.eventType,
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = when (event.status) {
                        "LIVE" -> Color(0xFFB71C1C)
                        "COMPLETED" -> Color(0xFF333333)
                        else -> Color(0xFF1A237E)
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        when (event.status) {
                            "LIVE" -> "LIVE NOW"
                            "COMPLETED" -> "COMPLETED"
                            else -> if (event.date.isNotBlank()) event.date.take(10) else "UPCOMING"
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
            // ── Event Info ────────────────────────────────────────────────
            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (event.brand.isNotBlank()) InfoRowWwe("Brand", event.brand, currentTheme)
                    if (event.eventType.isNotBlank()) InfoRowWwe("Type", event.eventType, currentTheme)
                    if (event.venue.isNotBlank()) InfoRowWwe("Venue", event.venue, currentTheme)
                    if (event.date.isNotBlank()) InfoRowWwe("Date", event.date.take(10), currentTheme)
                    if (event.time.isNotBlank()) InfoRowWwe("Time", event.time, currentTheme)
                }
            }

            // ── Stream Section ─────────────────────────────────────────────
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

                    // Status messages
                    when {
                        isResolving -> {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = wweAccent)
                                    Text("Finding stream... trying multiple sources", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        resolvedResult != null -> {
                            val typeLabel = when (resolvedResult) {
                                is WweStreamResult.Direct -> "Direct HLS Stream"
                                is WweStreamResult.Embed -> "Embed Player"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Check, null, tint = wweAccent, modifier = Modifier.size(18.dp))
                                Text("Stream ready! ($typeLabel)", color = wweAccent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        streamError != null -> {
                            Surface(color = Color(0xFFFF6F00).copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(streamError ?: "", color = Color(0xFFFF6F00), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }

                    // Main watch button
                    Button(
                        onClick = {
                            if (resolvedResult != null) launchStream(resolvedResult!!)
                            else resolveStream()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (event.status == "LIVE") Color(0xFFB71C1C) else wweAccent),
                        enabled = !isResolving
                    ) {
                        Icon(if (isResolving) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isResolving -> "Finding Stream..."
                                resolvedResult != null -> {
                                    when (resolvedResult) {
                                        is WweStreamResult.Direct -> "Launch Direct Stream"
                                        is WweStreamResult.Embed -> "Launch Player"
                                    }
                                }
                                event.status == "LIVE" -> "Find Live Stream"
                                event.status == "COMPLETED" -> "Watch Replay"
                                else -> "Check Stream"
                            },
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    // Second attempt button — try the other method
                    if (resolvedResult != null) {
                        val altMethodLabel = when (resolvedResult) {
                            is WweStreamResult.Direct -> "Try Embed Player Instead"
                            is WweStreamResult.Embed -> "Try Direct Stream Instead"
                        }
                        OutlinedButton(
                            onClick = {
                                // Force resolve the other type
                                scope.launch {
                                    isResolving = true
                                    streamError = null
                                    // Determine which method to try
                                    val altResults = when (resolvedResult) {
                                        is WweStreamResult.Direct -> {
                                            // Already tried Direct, now try Embed
                                            val embeds = wweSource.resolveEmbedUrls(event.eventId, event.title)
                                            embeds.map { WweStreamResult.Embed(it) }
                                        }
                                        is WweStreamResult.Embed -> {
                                            // Already tried Embed, now try Direct
                                            wweSource.resolveDirectStreamUrls(event.eventId, event.title)
                                        }
                                    }
                                    val altResult = altResults.firstOrNull()
                                    if (altResult != null) {
                                        resolvedResult = altResult
                                        when (altResult) {
                                            is WweStreamResult.Direct -> streamPlaybackType = StreamPlaybackType.DIRECT
                                            is WweStreamResult.Embed -> streamPlaybackType = StreamPlaybackType.EMBED
                                        }
                                    } else {
                                        streamError = "No alternative stream method available."
                                    }
                                    isResolving = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(altMethodLabel, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Error state: retry
                    if (streamError != null && resolvedResult == null) {
                        OutlinedButton(
                            onClick = { resolveStream() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry Stream Resolution", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Description ───────────────────────────────────────────────
            if (event.description.isNotBlank()) {
                Surface(
                    color = currentTheme.cardColor(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(event.description, color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ── Match Card ────────────────────────────────────────────────
            Text(
                "Match Card",
                style = MaterialTheme.typography.titleLarge,
                color = currentTheme.textColor(),
                fontWeight = FontWeight.Bold
            )

            if (isLoadingMatches) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = wweAccent)
                }
            } else if (matches.isEmpty()) {
                Text("Match card not yet announced.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
            } else {
                matches.forEach { match ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (match.isTitleMatch && match.titleName.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFFFD700).copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            match.titleName.take(20),
                                            color = Color(0xFFFFD700),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (match.stipulation.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = wweAccent.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            match.stipulation.take(25),
                                            color = wweAccent,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                match.participantDisplay.ifBlank { match.title },
                                color = currentTheme.textColor(),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )

                            if (match.matchType.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(match.matchType, color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelMedium)
                            }

                            if (match.hasResult) {
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF1B5E20).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        "Winner: ${match.winner.ifBlank { match.result }}",
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
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
private fun InfoRowWwe(label: String, value: String, currentTheme: AppTheme) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = currentTheme.textColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
