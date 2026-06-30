package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.cardColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor
import com.alexleoreeves.novelapp.ui.theme.textColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
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
    // Parse ID and type from tmdb://{mediaType}/{id}
    val parts = media.detailPageUrl.removePrefix("tmdb://").split("/")
    val mediaType = parts.getOrNull(0) ?: "movie"
    val tmdbId = parts.getOrNull(1) ?: ""

    val scraper = remember { TMDBMovieScraper(platformHttpClient()) }
    var episodesList by remember { mutableStateOf<List<MediaEpisode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }

    LaunchedEffect(tmdbId) {
        if (mediaType == "tv") {
            isLoadingEpisodes = true
            episodesList = scraper.fetchTVSeasonsAndEpisodes(tmdbId)
            isLoadingEpisodes = false
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
                            val playUrl = "https://vidlink.pro/movie/$tmdbId"
                            onPlayEpisode(playUrl, media.title)
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
                                        val parts = ep.url.split(":")
                                        val s = parts.getOrNull(2) ?: "1"
                                        val e = parts.getOrNull(3) ?: "1"
                                        val playUrl = "https://vidlink.pro/tv/$tmdbId/$s/$e"
                                        onPlayEpisode(playUrl, "${media.title} - ${ep.title}")
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
