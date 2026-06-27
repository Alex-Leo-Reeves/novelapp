package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.datetime.*

/**
 * AnimeDetailScreen — Shows anime info, episode list, and triggers episode playback.
 *
 * @param anime         The AnimeResult metadata from AniList
 * @param repository    The unified search repository used to fetch episode lists
 * @param currentTheme  App theme reference for styling
 * @param onPlayEpisode Callback invoked with a .m3u8 stream URL when user taps play
 * @param onBack        Navigation callback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    anime: AnimeResult,
    repository: NovelSearchRepository,
    currentTheme: AppTheme,
    onPlayEpisode: (streamUrl: String, episodeTitle: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var episodes by remember { mutableStateOf<List<AnimeEpisode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(true) }
    var extractingEpisode by remember { mutableStateOf<Int?>(null) }  // episode number being extracted
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Load episodes on mount
    LaunchedEffect(anime.id) {
        isLoadingEpisodes = true
        episodes = repository.fetchEpisodes(anime.titleRomaji.ifEmpty { anime.titleEnglish })
        isLoadingEpisodes = false
    }

    // Show snack messages
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = currentTheme.backgroundColor()
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Hero Image + Gradient Overlay ─────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = anime.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Dark gradient overlay bottom-up
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        currentTheme.backgroundColor().copy(alpha = 0.5f),
                                        currentTheme.backgroundColor()
                                    ),
                                    startY = 100f
                                )
                            )
                    )
                    // Back button
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .statusBarsPadding()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(0.5f)
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    // Status badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (anime.status) {
                            "RELEASING" -> Color(0xFF4CAF50)
                            "FINISHED" -> Color(0xFF9E9E9E)
                            "NOT_YET_RELEASED" -> Color(0xFF2196F3)
                            else -> Color(0xFF9E9E9E)
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .statusBarsPadding()
                    ) {
                        Text(
                            text = when (anime.status) {
                                "RELEASING" -> "● AIRING"
                                "FINISHED" -> "FINISHED"
                                "NOT_YET_RELEASED" -> "UPCOMING"
                                else -> anime.status
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // ── Title + Meta ──────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text(
                        text = anime.displayTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Black
                    )
                    if (anime.titleRomaji != anime.displayTitle) {
                        Text(
                            text = anime.titleRomaji,
                            style = MaterialTheme.typography.bodyMedium,
                            color = currentTheme.subTextColor()
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Meta row
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (anime.episodeCount > 0) {
                            MetaChip(
                                icon = Icons.Default.Movie,
                                text = "${anime.episodeCount} eps",
                                currentTheme = currentTheme
                            )
                        }
                        if (anime.nextEpisode > 0) {
                            MetaChip(
                                icon = Icons.Default.Schedule,
                                text = "EP ${anime.nextEpisode} upcoming",
                                currentTheme = currentTheme,
                                accentColor = Color(0xFF4CAF50)
                            )
                        }
                        MetaChip(
                            icon = Icons.Default.Language,
                            text = "AniList",
                            currentTheme = currentTheme
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Genre chips
                    if (anime.genres.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            anime.genres.forEach { genre ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = currentTheme.accentColor().copy(0.15f),
                                    border = BorderStroke(
                                        1.dp,
                                        currentTheme.accentColor().copy(0.4f)
                                    )
                                ) {
                                    Text(
                                        text = genre,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = currentTheme.accentColor(),
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 4.dp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Synopsis
                    if (anime.synopsis.isNotEmpty()) {
                        Text(
                            text = "Synopsis",
                            style = MaterialTheme.typography.titleSmall,
                            color = currentTheme.textColor(),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = anime.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = currentTheme.subTextColor(),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            // ── Episodes Header ───────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Episodes",
                        style = MaterialTheme.typography.titleMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Bold
                    )
                    if (!isLoadingEpisodes && episodes.isNotEmpty()) {
                        Text(
                            "${episodes.size} available",
                            style = MaterialTheme.typography.labelMedium,
                            color = currentTheme.subTextColor()
                        )
                    }
                }
            }

            // ── Episodes List ─────────────────────────────────────────────
            if (isLoadingEpisodes) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = currentTheme.accentColor(),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Fetching episode list…",
                                style = MaterialTheme.typography.bodySmall,
                                color = currentTheme.subTextColor()
                            )
                        }
                    }
                }
            } else if (episodes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SearchOff,
                                null,
                                tint = currentTheme.subTextColor(),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No episodes found on GogoAnime/Hianime",
                                style = MaterialTheme.typography.bodyMedium,
                                color = currentTheme.subTextColor()
                            )
                        }
                    }
                }
            } else {
                items(episodes) { episode ->
                    EpisodeRow(
                        episode = episode,
                        isExtracting = extractingEpisode == episode.episodeNumber,
                        currentTheme = currentTheme,
                        onPlayClick = {
                            scope.launch {
                                extractingEpisode = episode.episodeNumber
                                val streamUrl = repository.extractStreamUrl(episode.url)
                                extractingEpisode = null
                                if (streamUrl != null) {
                                    onPlayEpisode(
                                        streamUrl,
                                        "${anime.displayTitle} – EP ${episode.episodeNumber}"
                                    )
                                } else {
                                    snackMessage = "Stream unavailable for Episode ${episode.episodeNumber}. Try again later."
                                }
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: AnimeEpisode,
    isExtracting: Boolean,
    currentTheme: AppTheme,
    onPlayClick: () -> Unit
) {
    Surface(
        onClick = { if (!isExtracting) onPlayClick() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = currentTheme.cardColor()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Episode number badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = currentTheme.accentColor().copy(0.2f)
                ) {
                    Text(
                        text = "EP\n${episode.episodeNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.accentColor(),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                Column {
                    Text(
                        text = episode.title.ifEmpty { "Episode ${episode.episodeNumber}" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "GogoAnime • Tap to stream",
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.subTextColor()
                    )
                }
            }
            if (isExtracting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = currentTheme.accentColor()
                )
            } else {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "Play Episode ${episode.episodeNumber}",
                    tint = currentTheme.accentColor(),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun MetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    currentTheme: AppTheme,
    accentColor: Color? = null
) {
    val color = accentColor ?: currentTheme.subTextColor()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
