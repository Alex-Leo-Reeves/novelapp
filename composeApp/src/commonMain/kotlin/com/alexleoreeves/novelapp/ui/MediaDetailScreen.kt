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
import androidx.compose.material.icons.filled.Lock
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
import kotlin.math.ceil

@Composable
fun MediaDetailScreen(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    isPremium: Boolean,
    onSubscribe: () -> Unit,
    onPlayStream: (streamUrl: String, title: String, previewLimitMs: Long?) -> Unit,
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
    var providerTmdbId by remember(item.detailPageUrl) { mutableStateOf("") }
    var providerTmdbType by remember(item.detailPageUrl) { mutableStateOf("tv") }

    var selectedServer by remember { mutableStateOf(0) }
    val isTmdbDetail = item.detailPageUrl.startsWith("tmdb://")
    val isDramaCoolDetail = item.detailPageUrl.contains("dramacool", ignoreCase = true)
    val isKimCartoonDetail = item.detailPageUrl.contains("kimcartoon", ignoreCase = true)
    val embedServerNames = listOf("VidLink", "AutoEmbed", "VidSrc.me", "EmbedSu", "VidSrc.cc", "2Embed")
    val sourceServerOffset = if (isTmdbDetail) 0 else 2
    val serverNames = if (isTmdbDetail) {
        embedServerNames
    } else {
        listOf("Source host", "Episode page") + embedServerNames
    }
    val freeMoviePreviewMs = 20 * 60 * 1000L
    val freeEpisodeCount = remember(episodesList, isPremium) {
        if (isPremium || episodesList.isEmpty()) episodesList.size
        else ceil(episodesList.size * 0.2).toInt().coerceAtLeast(1)
    }

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
        selectedServer = 0
        providerTmdbId = ""
        providerTmdbType = "tv"
        isLoadingEpisodes = true
        episodesList = when {
            isTmdbDetail -> {
                if (mediaType == "tv") {
                    tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                } else {
                    emptyList()
                }
            }
            isDramaCoolDetail -> {
                dramaScraper.fetchEpisodes(item.detailPageUrl)
            }
            isKimCartoonDetail -> {
                cartoonScraper.fetchEpisodes(item.detailPageUrl)
            }
            else -> emptyList()
        }
        if (!isTmdbDetail) {
            val tmdbMatch = runCatching {
                tmdbScraper.search(item.title)
                    .sortedWith(
                        compareByDescending<MediaResult> { it.title.normalizedMediaTitle() == item.title.normalizedMediaTitle() }
                            .thenByDescending {
                                when (item.mediaKind) {
                                    VideoCategory.K_DRAMA.name -> it.type == "TVSHOW"
                                    VideoCategory.CARTOON.name -> it.type == "TVSHOW" || it.genres.contains("Animation", ignoreCase = true)
                                    else -> true
                                }
                            }
                    )
                    .firstOrNull()
            }.getOrNull()
            if (tmdbMatch != null) {
                providerTmdbId = tmdbMatch.id
                providerTmdbType = if (tmdbMatch.type == "MOVIE") "movie" else "tv"
            }
        }
        isLoadingEpisodes = false
    }

    val playEpisode: (MediaEpisode) -> Unit = { ep ->
        scope.launch {
            statusText = "Resolving stream link..."
            val playUrl = when {
                isTmdbDetail -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    resolveTvUrl(tvId, s, e, selectedServer)
                }
                isDramaCoolDetail -> {
                    when {
                        selectedServer == 0 -> dramaScraper.extractStreamUrl(ep.url) ?: ep.url
                        selectedServer == 1 -> ep.url
                        providerTmdbId.isNotBlank() -> {
                            val providerIndex = selectedServer - sourceServerOffset
                            if (providerTmdbType == "movie") {
                                resolveMovieUrl(providerTmdbId, providerIndex)
                            } else {
                                resolveTvUrl(providerTmdbId, "1", ep.episodeNumber.coerceAtLeast(1).toString(), providerIndex)
                            }
                        }
                        else -> {
                            statusText = "Provider match unavailable for this title. Opening source episode page."
                            ep.url
                        }
                    }
                }
                isKimCartoonDetail -> {
                    when {
                        selectedServer == 0 -> cartoonScraper.extractStreamUrl(ep.url) ?: ep.url
                        selectedServer == 1 -> ep.url
                        providerTmdbId.isNotBlank() -> {
                            val providerIndex = selectedServer - sourceServerOffset
                            if (providerTmdbType == "movie") {
                                resolveMovieUrl(providerTmdbId, providerIndex)
                            } else {
                                resolveTvUrl(providerTmdbId, "1", ep.episodeNumber.coerceAtLeast(1).toString(), providerIndex)
                            }
                        }
                        else -> {
                            statusText = "Provider match unavailable for this title. Opening source episode page."
                            ep.url
                        }
                    }
                }
                else -> ep.url
            }
            if (!statusText.startsWith("Provider match unavailable")) {
                statusText = ""
            }
            onPlayStream(playUrl, "${item.title} - ${ep.title}", null)
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
                if (!isPremium) {
                    Surface(
                        color = currentTheme.accentColor().copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Free account preview: first 20 minutes. Subscribe for the full movie, cartoons, and K-drama.",
                            color = currentTheme.textColor(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                Button(
                    onClick = {
                        val playUrl = resolveMovieUrl(tmdbId, selectedServer)
                        onPlayStream(playUrl, item.title, if (isPremium) null else freeMoviePreviewMs)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPremium) "Watch Movie Now" else "Watch 20-minute Preview", fontWeight = FontWeight.Bold)
                }
                if (!isPremium) {
                    OutlinedButton(
                        onClick = onSubscribe,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Subscribe for full access")
                    }
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
                    if (!isPremium) {
                        Text(
                            "Free account access: ${freeEpisodeCount} of ${episodesList.size} episodes unlocked.",
                            color = currentTheme.subTextColor(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    episodesList.forEachIndexed { index, ep ->
                        val locked = !isPremium && index >= freeEpisodeCount
                        Card(
                            onClick = {
                                if (locked) onSubscribe() else playEpisode(ep)
                            },
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
                                    if (locked) Icons.Default.Lock else Icons.Default.PlayArrow,
                                    contentDescription = if (locked) "Locked" else "Play",
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
                                if (locked) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Premium",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = currentTheme.accentColor(),
                                        fontWeight = FontWeight.Bold
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

private fun String.normalizedMediaTitle(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
