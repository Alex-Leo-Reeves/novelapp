package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.cardColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor
import com.alexleoreeves.novelapp.ui.theme.textColor
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.platform.platformHttpClient
import kotlinx.coroutines.launch

@Composable
fun MediaDetailScreen(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    onPlayStream: (streamUrl: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val httpClient = remember { platformHttpClient() }
    val tmdbScraper = remember { TMDBMovieScraper(httpClient) }
    val dramaScraper = remember { DramaCoolScraper(httpClient) }
    val cartoonScraper = remember { KimCartoonScraper(httpClient) }

    val parts = item.detailPageUrl.removePrefix("tmdb://").split("/")
    val mediaType = parts.getOrNull(0) ?: "movie"
    val tmdbId = parts.getOrNull(1) ?: ""

    var episodesList by remember { mutableStateOf<List<MediaEpisode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    var selectedServer by remember { mutableStateOf(0) }
    val serverNames = listOf("VidLink", "AutoEmbed", "VidSrc.me", "EmbedSu", "VidSrc.cc", "2Embed")

    val resolveMovieUrl: (String, Int) -> String = { id, server ->
        when (server) {
            0 -> "https://vidlink.pro/movie/$id"
            1 -> "https://player.autoembed.cc/movie/$id"
            2 -> "https://vidsrc.me/embed/movie?tmdb=$id"
            3 -> "https://embed.su/embed/movie/$id"
            4 -> "https://vidsrc.cc/v2/embed/movie/$id"
            5 -> "https://2embed.cc/embed/$id"
            else -> "https://vidlink.pro/movie/$id"
        }
    }

    val resolveTvUrl: (String, String, String, Int) -> String = { id, s, e, server ->
        when (server) {
            0 -> "https://vidlink.pro/tv/$id/$s/$e"
            1 -> "https://player.autoembed.cc/tv/$id/$s/$e"
            2 -> "https://vidsrc.me/embed/tv?tmdb=$id&season=$s&episode=$e"
            3 -> "https://embed.su/embed/tv/$id/$s/$e"
            4 -> "https://vidsrc.cc/v2/embed/tv/$id/$s/$e"
            5 -> "https://2embed.cc/embedtv/$id&s=$s&e=$e"
            else -> "https://vidlink.pro/tv/$id/$s/$e"
        }
    }

    LaunchedEffect(item.detailPageUrl) {
        isLoadingEpisodes = true
        episodesList = when {
            item.detailPageUrl.startsWith("tmdb://") -> {
                if (mediaType == "tv") {
                    tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                } else {
                    emptyList()
                }
            }
            item.detailPageUrl.contains("dramacool") -> {
                dramaScraper.fetchEpisodes(item.detailPageUrl)
            }
            item.detailPageUrl.contains("kimcartoon") -> {
                cartoonScraper.fetchEpisodes(item.detailPageUrl)
            }
            else -> emptyList()
        }
        isLoadingEpisodes = false
    }

    val playEpisode: (MediaEpisode) -> Unit = { ep ->
        scope.launch {
            statusText = "Resolving stream link..."
            val playUrl = when {
                item.detailPageUrl.startsWith("tmdb://") -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    resolveTvUrl(tvId, s, e, selectedServer)
                }
                item.detailPageUrl.contains("dramacool") -> {
                    dramaScraper.extractStreamUrl(ep.url) ?: ep.url
                }
                item.detailPageUrl.contains("kimcartoon") -> {
                    cartoonScraper.extractStreamUrl(ep.url) ?: ep.url
                }
                else -> ep.url
            }
            statusText = ""
            onPlayStream(playUrl, "${item.title} - ${ep.title}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .verticalScroll(rememberScrollState())
            .padding(bottom = 60.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.15f), currentTheme.backgroundColor()),
                            startY = 120f
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
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
            ) {
                Surface(
                    color = currentTheme.surfaceColor().copy(alpha = 0.86f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        item.sourceName,
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    item.title,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (item.genre.isNotBlank()) {
                Text(
                    item.genre,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    item.synopsis.ifBlank { "Synopsis unavailable." },
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Server Selector
            Column {
                Text("Streaming Server", color = currentTheme.textColor(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    serverNames.forEachIndexed { idx, name ->
                        FilterChip(
                            selected = selectedServer == idx,
                            onClick = { selectedServer = idx },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = currentTheme.accentColor(),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Movie: Single Play button
            if (item.detailPageUrl.startsWith("tmdb://") && mediaType == "movie") {
                Button(
                    onClick = {
                        val playUrl = resolveMovieUrl(tmdbId, selectedServer)
                        onPlayStream(playUrl, item.title)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Watch Movie Now", fontWeight = FontWeight.Bold)
                }
            }
 else {
                // TV / Episodic: List of episodes
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                if (isLoadingEpisodes) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = currentTheme.accentColor())
                    }
                } else if (episodesList.isEmpty()) {
                    Text(
                        "No episodes found for this show.",
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    episodesList.forEach { ep ->
                        Card(
                            onClick = { playEpisode(ep) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = currentTheme.accentColor(),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = ep.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = currentTheme.textColor()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
