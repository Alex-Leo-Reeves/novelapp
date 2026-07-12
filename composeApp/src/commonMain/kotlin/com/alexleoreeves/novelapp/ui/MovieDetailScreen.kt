package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.cardColor
import com.alexleoreeves.novelapp.ui.theme.textColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.platform.platformHttpClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    media: UnifiedSearchResult,
    currentTheme: AppTheme,
    onPlayEpisode: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val httpClient = remember { platformHttpClient() }
    // Parse ID and type from tmdb://{mediaType}/{id}
    val parts = media.detailPageUrl.removePrefix("tmdb://").split("/")
    val mediaType = parts.getOrNull(0) ?: "movie"
    val tmdbId = parts.getOrNull(1) ?: ""

    val scraper = remember { TMDBMovieScraper(httpClient) }
    var episodesList by remember { mutableStateOf<List<MediaEpisode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    val serverNames = listOf(
        "CinePro (Direct)",
        "SuperEmbed (Direct)",
        "VidSrc.to",
        "Nontongo",
        "MultiEmbed",
        "VidSrc.me",
        "VidSrc.in",
        "VidLink"
    )

    LaunchedEffect(tmdbId) {
        if (mediaType == "tv") {
            isLoadingEpisodes = true
            episodesList = scraper.fetchTVSeasonsAndEpisodes(tmdbId)
            isLoadingEpisodes = false
        }
    }

    fun buildMoviePlayUrl(type: String, id: String, season: String = "1", episode: String = "1"): String {
        if (id.isBlank()) return ""
        return when (selectedServer) {
            0 -> { statusText = "Resolving via CinePro..."; return@buildMoviePlayUrl "" }  // handled by CinePro coroutine
            1 -> buildSuperEmbedUrl(id, type, season, episode)
            2 -> if (type == "movie") "https://vidsrc.to/embed/movie/$id" else "https://vidsrc.to/embed/tv/$id/$season/$episode"
            3 -> if (type == "movie") "https://nontongo.win/embed/movie/$id" else "https://nontongo.win/embed/tv/$id/$season/$episode"
            4 -> if (type == "movie") "https://multiembed.mov/?video_id=$id&tmdb=1" else "https://multiembed.mov/?video_id=$id&tmdb=1&s=$season&e=$episode"
            5 -> if (type == "movie") "https://vidsrcme.ru/embed/movie?tmdb=$id" else "https://vidsrcme.ru/embed/tv?tmdb=$id&season=$season&episode=$episode"
            6 -> if (type == "movie") "https://vidsrc.in/embed/movie/$id" else "https://vidsrc.in/embed/tv/$id/$season/$episode"
            7 -> if (type == "movie") "https://vidlink.pro/movie/$id" else "https://vidlink.pro/tv/$id/$season/$episode"
            else -> if (type == "movie") "https://vidsrc.to/embed/movie/$id" else "https://vidsrc.to/embed/tv/$id/$season/$episode"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 80.dp)
        ) {
            // Header backdrop
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                AsyncImage(
                    model = media.coverUrl,
                    contentDescription = media.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, currentTheme.backgroundColor())
                            )
                        )
                )

                // Back Button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(0.5f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }

            // Info Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Cover thumbnail
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(0.72f)
                ) {
                    AsyncImage(
                        model = media.coverUrl,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Title and Genres
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = currentTheme.textColor()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = media.genre,
                        style = MaterialTheme.typography.bodyMedium,
                        color = currentTheme.accentColor()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Source: TMDB",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.subTextColor()
                    )
                }
            }

            // Overview/Synopsis
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Synopsis",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = currentTheme.textColor()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = media.synopsis.ifEmpty { "No overview available." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor()
                )
            }

            // Server Selector
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("Streaming Server", color = currentTheme.textColor(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
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
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // Playback controls (TV vs Movie)
            if (mediaType == "movie") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                statusText = "Resolving ${serverNames.getOrNull(selectedServer) ?: "server"}..."
                                val playUrl = if (selectedServer == 0) {
                                    resolveCineProStream(httpClient, "movie", tmdbId)
                                } else {
                                    buildMoviePlayUrl("movie", tmdbId)
                                }
                                if (playUrl.isNullOrBlank()) {
                                    statusText = "Stream unavailable for selected server. Try a different server."
                                    return@launch
                                }
                                statusText = ""
                                onPlayEpisode(playUrl, media.title)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Watch Movie Now",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // TV Show episode selector
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textColor()
                    )
                    Spacer(Modifier.height(12.dp))

                    if (isLoadingEpisodes) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = currentTheme.accentColor())
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            episodesList.forEach { ep ->
                                Card(
                                    onClick = {
                                        scope.launch {
                                            statusText = "Resolving ${serverNames.getOrNull(selectedServer) ?: "server"}..."
                                            val epParts = ep.url.split(":")
                                            val s = epParts.getOrNull(2) ?: "1"
                                            val e = epParts.getOrNull(3) ?: "1"
                                            val playUrl = if (selectedServer == 0) {
                                                resolveCineProStream(httpClient, "tv", tmdbId, s, e)
                                            } else {
                                                buildMoviePlayUrl("tv", tmdbId, s, e).takeIf { it.isNotBlank() } ?: buildVidLinkUrl(tmdbId, "tv", s, e)
                                            }
                                            if (playUrl.isNullOrBlank()) {
                                                statusText = "Stream unavailable for selected server. Try a different server."
                                                return@launch
                                            }
                                            statusText = ""
                                            onPlayEpisode(playUrl, "${media.title} - ${ep.title}")
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = currentTheme.accentColor(),
                                            modifier = Modifier.size(24.dp)
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
    }
}
