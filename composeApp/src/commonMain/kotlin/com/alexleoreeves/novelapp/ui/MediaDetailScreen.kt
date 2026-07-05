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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
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
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

@Composable
fun MediaDetailScreen(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    isPremium: Boolean,
    downloadRepo: LocalDownloadRepository,
    requireAuth: (() -> Unit) -> Unit,
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
    val embedServerNames = listOf("Web fallback: VidLink", "Web fallback: AutoEmbed", "Web fallback: VidSrc.me", "Web fallback: EmbedSu", "Web fallback: VidSrc.cc", "Web fallback: 2Embed")
    val embedServerKeys = listOf("vidlink", "autoembed", "vidsrcme", "embedsu", "vidsrccc", "twoembed")
    val serverNames = listOf("Direct first") + embedServerNames
    val freeMoviePreviewMs = 20 * 60 * 1000L
    val freeEpisodeCount = remember(episodesList, isPremium) {
        if (isPremium || episodesList.isEmpty()) episodesList.size
        else ceil(episodesList.size * 0.2).toInt().coerceAtLeast(1)
    }

    fun buildWebFallbackStream(
        type: String,
        id: String,
        season: String = "1",
        episode: String = "1",
        server: Int
    ): String? {
        if (id.isBlank()) return null
        val providers = listOf("vidlink", "autoembed", "vidsrcme", "embedsu", "vidsrccc", "twoembed")
        val provider = providers.getOrElse((server - 1).coerceAtLeast(0)) { providers.first() }
        return if (type == "movie") {
            when (provider) {
                "vidlink" -> "https://vidlink.pro/movie/$id"
                "autoembed" -> "https://player.autoembed.cc/movie/$id"
                "vidsrcme" -> "https://vidsrc.me/embed/movie?tmdb=$id"
                "embedsu" -> "https://embed.su/embed/movie/$id"
                "vidsrccc" -> "https://vidsrc.cc/v2/embed/movie/$id"
                "twoembed" -> "https://2embed.cc/embed/$id"
                else -> "https://vidlink.pro/movie/$id"
            }
        } else {
            when (provider) {
                "vidlink" -> "https://vidlink.pro/tv/$id/$season/$episode"
                "autoembed" -> "https://player.autoembed.cc/tv/$id/$season/$episode"
                "vidsrcme" -> "https://vidsrc.me/embed/tv?tmdb=$id&season=$season&episode=$episode"
                "embedsu" -> "https://embed.su/embed/tv/$id/$season/$episode"
                "vidsrccc" -> "https://vidsrc.cc/v2/embed/tv/$id/$season/$episode"
                "twoembed" -> "https://2embed.cc/embedtv/$id&s=$season&e=$episode"
                else -> "https://vidlink.pro/tv/$id/$season/$episode"
            }
        }
    }

    var downloadingEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    fun downloadEpisode(ep: MediaEpisode) {
        requireAuth {
            if (downloadRepo.isEpisodeDownloaded(item.id, ep.episodeNumber)) {
                downloadRepo.deleteEpisode(item.id, ep.episodeNumber)
                if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                    downloadRepo.deleteItem(item.id)
                }
                refreshTrigger++
            } else {
                downloadingEpisodes = downloadingEpisodes + ep.episodeNumber
                statusText = "Resolving download link for Episode ${ep.episodeNumber}..."
                scope.launch {
                    try {
                        downloadRepo.addItem(
                            DownloadedItem(
                                id = item.id,
                                title = item.title,
                                coverUrl = item.coverUrl,
                                type = "ANIME", // Keep it "ANIME" so it fits the video tab offline views perfectly
                                sourceName = item.sourceName
                            )
                        )
                        // Resolve the URL using the same logic as playback!
                        val playUrl = when {
                            isTmdbDetail -> {
                                val urlParts = ep.url.split(":")
                                val tvId = urlParts.getOrNull(1) ?: tmdbId
                                val s = urlParts.getOrNull(2) ?: "1"
                                val e = urlParts.getOrNull(3) ?: "1"
                                resolveBackendMediaStream(httpClient, "tv", tvId, s, e)?.url
                                    ?: buildWebFallbackStream("tv", tvId, s, e, selectedServer)
                            }
                            isDramaCoolDetail -> {
                                if (providerTmdbId.isNotBlank()) {
                                    if (providerTmdbType == "movie") {
                                        resolveBackendMediaStream(httpClient, "movie", providerTmdbId, "1", "1")?.url
                                            ?: buildWebFallbackStream("movie", providerTmdbId, server = selectedServer)
                                    } else {
                                        val episodeNumber = ep.episodeNumber.coerceAtLeast(1).toString()
                                        resolveBackendMediaStream(httpClient, "tv", providerTmdbId, "1", episodeNumber)?.url
                                            ?: buildWebFallbackStream("tv", providerTmdbId, "1", episodeNumber, selectedServer)
                                    }
                                } else {
                                    dramaScraper.extractStreamUrl(ep.url)
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                }
                            }
                            isKimCartoonDetail -> {
                                if (providerTmdbId.isNotBlank()) {
                                    if (providerTmdbType == "movie") {
                                        resolveBackendMediaStream(httpClient, "movie", providerTmdbId, "1", "1")?.url
                                            ?: buildWebFallbackStream("movie", providerTmdbId, server = selectedServer)
                                    } else {
                                        val episodeNumber = ep.episodeNumber.coerceAtLeast(1).toString()
                                        resolveBackendMediaStream(httpClient, "tv", providerTmdbId, "1", episodeNumber)?.url
                                            ?: buildWebFallbackStream("tv", providerTmdbId, "1", episodeNumber, selectedServer)
                                    }
                                } else {
                                    cartoonScraper.extractStreamUrl(ep.url)
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                }
                            }
                            else -> ep.url
                        }

                        if (playUrl != null) {
                            statusText = "Extracting stream link..."
                            val directUrl = if (playUrl.isDirectPlayableStreamUrl()) {
                                playUrl
                            } else {
                                // It is an embed/website, scrape it
                                extractStreamFromEmbed(playUrl)
                            }
                            if (directUrl != null) {
                                statusText = "Downloading Episode ${ep.episodeNumber}..."
                                val saved = saveDownloadedVideo(
                                    parentId = item.id,
                                    episodeNumber = ep.episodeNumber,
                                    sourceUrl = directUrl
                                )
                                if (saved.success) {
                                    downloadRepo.addEpisode(
                                        DownloadedEpisode(
                                            parentId = item.id,
                                            episodeNumber = ep.episodeNumber,
                                            episodeTitle = ep.title,
                                            localFilePath = saved.localPath,
                                            fileSizeBytes = saved.fileSizeBytes
                                        )
                                    )
                                    statusText = "Episode ${ep.episodeNumber} saved offline."
                                } else {
                                    statusText = saved.error.ifBlank { "Download failed." }
                                    if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                        downloadRepo.deleteItem(item.id)
                                    }
                                }
                            } else {
                                statusText = "Failed to extract direct stream for download."
                                if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                    downloadRepo.deleteItem(item.id)
                                }
                            }
                        } else {
                            statusText = "Stream unavailable for download."
                            if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                    downloadRepo.deleteItem(item.id)
                            }
                        }
                    } catch (e: Exception) {
                        statusText = "Download failed: ${e.message}"
                    } finally {
                        downloadingEpisodes = downloadingEpisodes - ep.episodeNumber
                        refreshTrigger++
                    }
                }
            }
        }
    }

    val isMovieContent = mediaType == "movie"

    suspend fun resolvePlayableRoute(
        type: String,
        id: String,
        season: String = "1",
        episode: String = "1"
    ): String? {
        if (id.isBlank()) {
            statusText = "Provider match unavailable for this title."
            return null
        }
        val direct = resolveBackendMediaStream(httpClient, type, id, season, episode)
        if (direct?.isDirect == true) {
            statusText = ""
            return direct.url
        }
        if (type == "movie") {
            // Movies: fall back to web embed page (VidLink etc.)
            val fallback = resolveWebFallbackStream(type, id, season, episode, selectedServer)
            if (fallback != null) {
                val selected = serverNames.getOrNull(selectedServer).orEmpty()
                statusText = if (selectedServer == 0) {
                    "Direct stream unavailable; opening Web fallback: VidLink."
                } else {
                    "Direct stream unavailable; opening $selected."
                }
                return fallback
            }
        } else {
            // TV (K-Drama / Cartoon): silently scrape embed.su — the player will
            // extract the .m3u8 in background via EmbedSuStreamScraper.
            if (id.isNotBlank()) {
                // Build embed.su URL — this will be intercepted by the hidden WebView scraper
                val embedUrl = buildEmbedSuUrl(id, type, season, episode)
                statusText = "Loading via embed.su stream extraction..."
                return embedUrl
            }
            statusText = direct?.message
                ?.takeIf { it.isNotBlank() }
                ?: "Direct stream unavailable. Try a different episode."
        }
        return null
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
                if (episodesList.isEmpty() && providerTmdbType == "tv") {
                    episodesList = tmdbScraper.fetchTVSeasonsAndEpisodes(providerTmdbId)
                }
            }
        }
        isLoadingEpisodes = false
    }

    val playEpisode: (MediaEpisode) -> Unit = { ep ->
        scope.launch {
            statusText = "Resolving stream link..."
            val playUrl = when {
                // TMDB TV content (K-Drama / Cartoon): go straight to embed.su
                // The platform's EmbedSuStreamScraper extracts the .m3u8 in the background.
                isTmdbDetail && mediaType == "tv" -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    statusText = "Loading via embed.su stream extraction..."
                    buildEmbedSuUrl(tvId, "tv", s, e)
                }
                isTmdbDetail -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    resolvePlayableRoute("tv", tvId, s, e) ?: return@launch
                }
                isDramaCoolDetail -> {
                    if (providerTmdbId.isNotBlank()) {
                        if (providerTmdbType == "movie") {
                            resolvePlayableRoute("movie", providerTmdbId) ?: return@launch
                        } else {
                            resolvePlayableRoute("tv", providerTmdbId, "1", ep.episodeNumber.coerceAtLeast(1).toString()) ?: return@launch
                        }
                    } else {
                        val extracted = dramaScraper.extractStreamUrl(ep.url)
                            ?.takeIf { it.isDirectPlayableStreamUrl() }
                        if (extracted == null) {
                            statusText = "Direct stream unavailable for this episode. Try a different episode or check back later."
                            return@launch
                        }
                        extracted
                    }
                }
                isKimCartoonDetail -> {
                    if (providerTmdbId.isNotBlank()) {
                        if (providerTmdbType == "movie") {
                            resolvePlayableRoute("movie", providerTmdbId) ?: return@launch
                        } else {
                            resolvePlayableRoute("tv", providerTmdbId, "1", ep.episodeNumber.coerceAtLeast(1).toString()) ?: return@launch
                        }
                    } else {
                        val extracted = cartoonScraper.extractStreamUrl(ep.url)
                            ?.takeIf { it.isDirectPlayableStreamUrl() }
                        if (extracted == null) {
                            statusText = "Direct stream unavailable for this episode. Try a different episode or check back later."
                            return@launch
                        }
                        extracted
                    }
                }
                else -> ep.url
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

                var downloadingMovie by remember { mutableStateOf(false) }
                val isMovieDownloaded = remember(refreshTrigger) {
                    downloadRepo.isEpisodeDownloaded(item.id, 1)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                statusText = "Resolving ${serverNames.getOrNull(selectedServer) ?: "server"}..."
                                val playUrl = resolvePlayableRoute("movie", tmdbId)
                                    ?: return@launch
                                onPlayStream(playUrl, item.title, if (isPremium) null else freeMoviePreviewMs)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPremium) "Watch Movie Now" else "Watch 20-minute Preview", fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                        onClick = {
                            requireAuth {
                                if (isMovieDownloaded) {
                                    downloadRepo.deleteEpisode(item.id, 1)
                                    if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                        downloadRepo.deleteItem(item.id)
                                    }
                                    refreshTrigger++
                                } else {
                                    downloadingMovie = true
                                    statusText = "Resolving download link..."
                                    scope.launch {
                                        try {
                                            downloadRepo.addItem(
                                                DownloadedItem(
                                                    id = item.id,
                                                    title = item.title,
                                                    coverUrl = item.coverUrl,
                                                    type = "ANIME",
                                                    sourceName = item.sourceName
                                                )
                                            )
                                            val playUrl = resolvePlayableRoute("movie", tmdbId)
                                            if (playUrl != null) {
                                                statusText = "Extracting stream link..."
                                                val directUrl = if (playUrl.isDirectPlayableStreamUrl()) {
                                                    playUrl
                                                } else {
                                                    extractStreamFromEmbed(playUrl)
                                                }
                                                if (directUrl != null) {
                                                    statusText = "Downloading movie..."
                                                    val saved = saveDownloadedVideo(
                                                        parentId = item.id,
                                                        episodeNumber = 1,
                                                        sourceUrl = directUrl
                                                    )
                                                    if (saved.success) {
                                                        downloadRepo.addEpisode(
                                                            DownloadedEpisode(
                                                                parentId = item.id,
                                                                episodeNumber = 1,
                                                                episodeTitle = item.title,
                                                                localFilePath = saved.localPath,
                                                                fileSizeBytes = saved.fileSizeBytes
                                                            )
                                                        )
                                                        statusText = "Movie saved offline."
                                                    } else {
                                                        statusText = saved.error.ifBlank { "Movie download failed." }
                                                        if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                                            downloadRepo.deleteItem(item.id)
                                                        }
                                                    }
                                                } else {
                                                    statusText = "Failed to extract movie stream."
                                                    if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                                        downloadRepo.deleteItem(item.id)
                                                    }
                                                }
                                            } else {
                                                statusText = "Movie stream unavailable for download."
                                                if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                                    downloadRepo.deleteItem(item.id)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            statusText = "Download failed: ${e.message}"
                                        } finally {
                                            downloadingMovie = false
                                            refreshTrigger++
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(currentTheme.cardColor(), RoundedCornerShape(12.dp))
                    ) {
                        if (downloadingMovie) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = currentTheme.accentColor()
                            )
                        } else if (isMovieDownloaded) {
                            Icon(Icons.Default.OfflinePin, contentDescription = "Downloaded", tint = Color(0xFF4CAF50))
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "Download Movie", tint = currentTheme.textColor())
                        }
                    }
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
                        val isDownloaded = remember(refreshTrigger, ep.episodeNumber) {
                            downloadRepo.isEpisodeDownloaded(item.id, ep.episodeNumber)
                        }
                        val isDownloading = ep.episodeNumber in downloadingEpisodes

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
                                    color = currentTheme.textColor(),
                                    modifier = Modifier.weight(1f)
                                )
                                if (locked) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Premium",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = currentTheme.accentColor(),
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            downloadEpisode(ep)
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        if (isDownloading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = currentTheme.accentColor()
                                            )
                                        } else if (isDownloaded) {
                                            Icon(
                                                Icons.Default.OfflinePin,
                                                contentDescription = "Downloaded",
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.Download,
                                                contentDescription = "Download",
                                                tint = currentTheme.subTextColor(),
                                                modifier = Modifier.size(20.dp)
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
}

private fun String.normalizedMediaTitle(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private val mediaRouteJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private data class BackendMediaRoute(
    val url: String = "",
    val route: String = "",
    val provider: String = "",
    val message: String = ""
) {
    val isDirect: Boolean
        get() = route.equals("direct", ignoreCase = true) && url.isDirectPlayableStreamUrl()
}

private suspend fun resolveBackendMediaStream(
    client: HttpClient,
    type: String,
    id: String,
    season: String = "1",
    episode: String = "1"
): BackendMediaRoute? = runCatching {
    val raw = client.get("${AppReleaseConfig.API_BASE_URL}/content/stream") {
        parameter("type", if (type == "movie") "movie" else "tv")
        parameter("id", id)
        parameter("season", season)
        parameter("episode", episode)
        parameter("provider", "all")
    }.bodyAsText()
    val root = mediaRouteJson.parseToJsonElement(raw).jsonObject
    val data = root["data"]?.jsonObject ?: root
    BackendMediaRoute(
        url = data["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        route = data["route"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        provider = data["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        message = data["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
    )
}.getOrNull()

private fun resolveWebFallbackStream(
    type: String,
    id: String,
    season: String = "1",
    episode: String = "1",
    server: Int
): String? {
    if (id.isBlank()) return null
    val providers = listOf("vidlink", "autoembed", "vidsrcme", "embedsu", "vidsrccc", "twoembed")
    val provider = providers.getOrElse((server - 1).coerceAtLeast(0)) { providers.first() }
    return if (type == "movie") {
        when (provider) {
            "vidlink" -> "https://vidlink.pro/movie/$id"
            "autoembed" -> "https://player.autoembed.cc/movie/$id"
            "vidsrcme" -> "https://vidsrc.me/embed/movie?tmdb=$id"
            "embedsu" -> "https://embed.su/embed/movie/$id"
            "vidsrccc" -> "https://vidsrc.cc/v2/embed/movie/$id"
            "twoembed" -> "https://2embed.cc/embed/$id"
            else -> "https://vidlink.pro/movie/$id"
        }
    } else {
        when (provider) {
            "vidlink" -> "https://vidlink.pro/tv/$id/$season/$episode"
            "autoembed" -> "https://player.autoembed.cc/tv/$id/$season/$episode"
            "vidsrcme" -> "https://vidsrc.me/embed/tv?tmdb=$id&season=$season&episode=$episode"
            "embedsu" -> "https://embed.su/embed/tv/$id/$season/$episode"
            "vidsrccc" -> "https://vidsrc.cc/v2/embed/tv/$id/$season/$episode"
            "twoembed" -> "https://2embed.cc/embedtv/$id&s=$season&e=$episode"
            else -> "https://vidlink.pro/tv/$id/$season/$episode"
        }
    }
}

private fun String.isDirectPlayableStreamUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
        Regex("""/(playlist|manifest|hls|dash)(/|$)""").containsMatchIn(clean) ||
        startsWith("file:", ignoreCase = true)
}
