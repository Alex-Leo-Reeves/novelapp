package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
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
    val wcoStreamScraper = remember { WcoStreamScraper(httpClient) }
    val youtubeNollywoodScraper = remember { YouTubeNollywoodScraper(httpClient) }

    val parts = item.detailPageUrl.removePrefix("tmdb://").split("/")
    val mediaType = parts.getOrNull(0) ?: "movie"
    val tmdbId = parts.getOrNull(1) ?: ""

    var episodesList by remember { mutableStateOf<List<MediaEpisode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var providerTmdbId by remember(item.detailPageUrl) { mutableStateOf("") }
    var providerTmdbType by remember(item.detailPageUrl) { mutableStateOf("tv") }

    var isMovieContent by remember(mediaType) { mutableStateOf(mediaType == "movie") }
    val isYouTubeNollywood = item.id.startsWith("youtube_nollywood_")

    var selectedServer by remember { mutableStateOf(if (item.mediaKind.equals("DONGHUA", ignoreCase = true)) 1 else 0) }
    val isTmdbDetail = item.detailPageUrl.startsWith("tmdb://")
    val isDramaCoolDetail = item.detailPageUrl.contains("dramacool", ignoreCase = true)
    val isKimCartoonDetail = item.detailPageUrl.contains("kimcartoon", ignoreCase = true)
    val isWcoStreamDetail = item.sourceName == "WCOStream" || item.detailPageUrl.contains("wcostream", ignoreCase = true)
    
    // Made VidLink the first and only visible source per user request, but kept others for fallback
    val embedServerNames = listOf("VidLink", "VidSrc.to", "Nontongo", "MultiEmbed", "VidSrc.me", "VidSrc.in")
    val serverNames = listOf("VidLink") // Hide the other sources in UI

    fun buildEmbedUrl(
        type: String,
        id: String,
        season: String = "1",
        episode: String = "1"
    ): String? {
        if (id.isBlank()) return null
        return when (selectedServer) {
            0 -> if (type == "movie") "https://vidlink.pro/movie/$id" else "https://vidlink.pro/tv/$id/$season/$episode"
            1 -> if (type == "movie") "https://vidsrc.to/embed/movie/$id" else "https://vidsrc.to/embed/tv/$id/$season/$episode"
            2 -> if (type == "movie") "https://nontongo.win/embed/movie/$id" else "https://nontongo.win/embed/tv/$id/$season/$episode"
            3 -> if (type == "movie") "https://multiembed.mov/?video_id=$id&tmdb=1" else "https://multiembed.mov/?video_id=$id&tmdb=1&s=$season&e=$episode"
            4 -> if (type == "movie") "https://vidsrcme.ru/embed/movie?tmdb=$id" else "https://vidsrcme.ru/embed/tv?tmdb=$id&season=$season&episode=$episode"
            5 -> if (type == "movie") "https://vidsrc.in/embed/movie/$id" else "https://vidsrc.in/embed/tv/$id/$season/$episode"
            else -> if (type == "movie") "https://vidlink.pro/movie/$id" else "https://vidlink.pro/tv/$id/$season/$episode"
        }
    }

    val freeMoviePreviewMs = 20 * 60 * 1000L
    val freeEpisodeCount = remember(episodesList, isPremium) {
        if (isPremium || episodesList.isEmpty()) episodesList.size
        else ceil(episodesList.size * 0.2).toInt().coerceAtLeast(1)
    }

    var downloadingEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    fun contentTypeForItem(): String = when (item.mediaKind.uppercase()) {
        "MOVIE" -> ContentType.MOVIE
        "CARTOON" -> ContentType.CARTOON
        "K_DRAMA" -> ContentType.K_DRAMA
        "CLASSIC" -> ContentType.CLASSIC
        "DONGHUA" -> ContentType.MOVIE
        "NIGERIAN" -> ContentType.NIGERIAN
        else -> ContentType.ANIME
    }

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
                                type = contentTypeForItem(),
                                sourceName = item.sourceName
                            )
                        )

                        val embedUrl = when {
                            isTmdbDetail -> {
                                val urlParts = ep.url.split(":")
                                val tvId = urlParts.getOrNull(1) ?: tmdbId
                                val s = urlParts.getOrNull(2) ?: "1"
                                val e = urlParts.getOrNull(3) ?: "1"
                                buildEmbedUrl("tv", tvId, s, e)
                            }
                            isDramaCoolDetail -> {
                                if (providerTmdbId.isNotBlank()) {
                                    val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                                    buildEmbedUrl(providerTmdbType, providerTmdbId, "1", epNum)
                                } else {
                                    dramaScraper.extractStreamUrl(ep.url)
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                }
                            }
                            isKimCartoonDetail -> {
                                if (providerTmdbId.isNotBlank()) {
                                    val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                                    buildEmbedUrl(providerTmdbType, providerTmdbId, "1", epNum)
                                } else {
                                    cartoonScraper.extractStreamUrl(ep.url)
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                }
                            }
                            isWcoStreamDetail -> {
                                wcoStreamScraper.extractStreamUrl(ep.url)
                                    ?.takeIf { it.isDirectPlayableStreamUrl() }
                            }
                            else -> ep.url
                        }

                        if (embedUrl != null) {
                            statusText = "Downloading Episode ${ep.episodeNumber}..."
                            val saved = saveDownloadedVideo(
                                parentId = item.id,
                                episodeNumber = ep.episodeNumber,
                                sourceUrl = embedUrl
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

    LaunchedEffect(item.detailPageUrl) {
        selectedServer = if (item.mediaKind.equals("DONGHUA", ignoreCase = true)) 1 else 0
        providerTmdbId = ""
        providerTmdbType = "tv"
        isLoadingEpisodes = true

        // Phase 1: Load episodes from the URL the item came from
        val initialEpisodes = when {
            isTmdbDetail -> {
                if (mediaType == "tv") {
                    tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                } else {
                    emptyList()
                }
            }
            isDramaCoolDetail -> dramaScraper.fetchEpisodes(item.detailPageUrl)
            isKimCartoonDetail -> cartoonScraper.fetchEpisodes(item.detailPageUrl)
            isWcoStreamDetail -> wcoStreamScraper.fetchEpisodes(item.detailPageUrl)
            else -> emptyList()
        }
        episodesList = initialEpisodes

        // Phase 2: If episodes are still empty (or it's a movie without a TMDB ID),
        // try to find a TMDB match by title and load episodes from it.
        // This handles items from BACKEND, curated seeds, WCOStream, etc.
        // that have no real TMDB ID but do have a title that matches TMDB.
        val isMovie = mediaType == "movie"
        val needsTmdbMatch = (episodesList.isEmpty() && !isMovie) || (isMovie && !isTmdbDetail)
        if (needsTmdbMatch) {
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
                if (providerTmdbType == "movie") {
                    isMovieContent = true
                }
                // Search returned a TV match — fetch real episodes
                if (providerTmdbType == "tv") {
                    val tmdbEpisodes = tmdbScraper.fetchTVSeasonsAndEpisodes(providerTmdbId)
                    if (tmdbEpisodes.isNotEmpty()) {
                        episodesList = tmdbEpisodes
                        isMovieContent = false
                    }
                }
            }
        }

        // Phase 3: If STILL no episodes found from any source,
        // try a broader TMDB search using genre hints from the item
        if (episodesList.isEmpty() && !isMovieContent) {
            val genreHint = when {
                item.mediaKind == VideoCategory.K_DRAMA.name -> "korean drama"
                item.mediaKind == VideoCategory.CARTOON.name -> "cartoon"
                item.mediaKind == VideoCategory.CLASSIC.name -> "tv series"
                item.mediaKind == VideoCategory.DONGHUA.name -> "donghua chinese"
                else -> "tv show"
            }
            val broaderSearch = runCatching {
                tmdbScraper.search("$genreHint ${item.title}")
                    .filter { it.type == "TVSHOW" }
                    .firstOrNull()
            }.getOrNull()
            if (broaderSearch != null) {
                providerTmdbId = broaderSearch.id
                providerTmdbType = "tv"
                val tmdbEpisodes = tmdbScraper.fetchTVSeasonsAndEpisodes(broaderSearch.id)
                if (tmdbEpisodes.isNotEmpty()) {
                    episodesList = tmdbEpisodes
                }
            }
        }

        isLoadingEpisodes = false
    }

    val playEpisode: (MediaEpisode) -> Unit = { ep ->
        scope.launch {
            statusText = "Resolving stream..."
            val playUrl = when {
                isTmdbDetail && mediaType == "tv" -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    buildEmbedUrl("tv", tvId, s, e) ?: return@launch
                }
                isTmdbDetail -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    buildEmbedUrl("tv", tvId, s, e) ?: return@launch
                }
                isDramaCoolDetail -> {
                    if (providerTmdbId.isNotBlank()) {
                        if (providerTmdbType == "movie") {
                            buildEmbedUrl("movie", providerTmdbId) ?: return@launch
                        } else {
                            buildEmbedUrl("tv", providerTmdbId, "1", ep.episodeNumber.coerceAtLeast(1).toString()) ?: return@launch
                        }
                    } else {
                        val extracted = dramaScraper.extractStreamUrl(ep.url)
                            ?.takeIf { it.isDirectPlayableStreamUrl() }
                        if (extracted == null) {
                            statusText = "Direct stream unavailable for this episode."
                            return@launch
                        }
                        extracted
                    }
                }
                isKimCartoonDetail -> {
                    if (providerTmdbId.isNotBlank()) {
                        if (providerTmdbType == "movie") {
                            buildEmbedUrl("movie", providerTmdbId) ?: return@launch
                        } else {
                            buildEmbedUrl("tv", providerTmdbId, "1", ep.episodeNumber.coerceAtLeast(1).toString()) ?: return@launch
                        }
                    } else {
                        val extracted = cartoonScraper.extractStreamUrl(ep.url)
                            ?.takeIf { it.isDirectPlayableStreamUrl() }
                        if (extracted == null) {
                            statusText = "Direct stream unavailable for this episode."
                            return@launch
                        }
                        extracted
                    }
                }
                isWcoStreamDetail -> {
                    val extracted = wcoStreamScraper.extractStreamUrl(ep.url)
                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                    if (extracted == null) {
                        statusText = "This episode stream is unavailable from the cartoon provider."
                        return@launch
                    }
                    extracted
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
                // sourceName badge removed for abstraction
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

            // Server Selector removed for abstraction

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // YouTube Nollywood
            if (isYouTubeNollywood) {
                Button(
                    onClick = {
                        scope.launch {
                            statusText = "Resolving ad-free stream via Piped..."
                            val prefix = "youtube_nollywood_"
                            val videoId = item.id.removePrefix(prefix)
                            val streamUrl = youtubeNollywoodScraper.extractStreamUrl(videoId)
                            if (streamUrl != null) {
                                onPlayStream(streamUrl, item.title, null)
                            } else {
                                statusText = "Could not resolve stream."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Watch on ExoPlayer (Piped)", fontWeight = FontWeight.Bold)
                }
            }

            // Movie: Single Play button
            val hasMovieId = (isTmdbDetail && mediaType == "movie") || (providerTmdbType == "movie" && providerTmdbId.isNotBlank())
            if (hasMovieId) {
                if (!isPremium) {
                    Surface(
                        color = currentTheme.accentColor().copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Free account preview: first 20 minutes.",
                            color = currentTheme.textColor(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                var downloadingMovie by remember { mutableStateOf(false) }
                val isMovieDownloaded = remember(refreshTrigger) { downloadRepo.isEpisodeDownloaded(item.id, 1) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val resolvedTmdbId = if (isTmdbDetail) tmdbId else providerTmdbId
                                statusText = "Opening ${serverNames.getOrNull(selectedServer) ?: "server"}..."
                                statusText = "Opening stream..."
                                val embedUrl = buildEmbedUrl("movie", resolvedTmdbId) ?: return@launch
                                onPlayStream(embedUrl, item.title, if (isPremium) null else freeMoviePreviewMs)
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
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
                                    if (downloadRepo.getEpisodesFor(item.id).isEmpty()) downloadRepo.deleteItem(item.id)
                                    refreshTrigger++
                                } else {
                                    downloadingMovie = true
                                    statusText = "Resolving download link..."
                                    scope.launch {
                                        try {
                                            downloadRepo.addItem(DownloadedItem(item.id, item.title, item.coverUrl, contentTypeForItem(), item.sourceName))
                                            val resolvedTmdbId = if (isTmdbDetail) tmdbId else providerTmdbId
                                            val embedUrl = buildEmbedUrl("movie", resolvedTmdbId)
                                            if (embedUrl != null) {
                                                statusText = "Downloading movie..."
                                                val saved = saveDownloadedVideo(item.id, 1, embedUrl)
                                                if (saved.success) {
                                                    downloadRepo.addEpisode(DownloadedEpisode(item.id, 1, item.title, saved.localPath, saved.fileSizeBytes))
                                                    statusText = "Movie saved offline."
                                                } else { statusText = saved.error.ifBlank { "Download failed." }; if (downloadRepo.getEpisodesFor(item.id).isEmpty()) downloadRepo.deleteItem(item.id) }
                                            } else { statusText = "Movie stream unavailable for download."; if (downloadRepo.getEpisodesFor(item.id).isEmpty()) downloadRepo.deleteItem(item.id) }
                                        } catch (e: Exception) { statusText = "Download failed: ${e.message}" }
                                        finally { downloadingMovie = false; refreshTrigger++ }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(50.dp).background(currentTheme.cardColor(), RoundedCornerShape(12.dp))
                    ) {
                        if (downloadingMovie) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = currentTheme.accentColor())
                        else if (isMovieDownloaded) Icon(Icons.Default.OfflinePin, null, tint = Color(0xFF4CAF50))
                        else Icon(Icons.Default.Download, null, tint = currentTheme.textColor())
                    }
                }

                if (!isPremium) {
                    OutlinedButton(onClick = onSubscribe, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Subscribe for full access")
                    }
                }
            }
            else {
                // TV / Episodic
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                if (isLoadingEpisodes) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = currentTheme.accentColor())
                    }
                } else if (episodesList.isEmpty()) {
                    Text("No episodes found for this show.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (!isPremium) {
                        Text("Free account access: $freeEpisodeCount of ${episodesList.size} episodes unlocked.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodySmall)
                    }
                    
                    val chunkSize = 50
                    var currentChunkIndex by remember(episodesList) { mutableStateOf(0) }
                    
                    if (episodesList.size > chunkSize) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            items(episodesList.chunked(chunkSize).size) { chunkIndex ->
                                val startEp = chunkIndex * chunkSize + 1
                                val endEp = minOf((chunkIndex + 1) * chunkSize, episodesList.size)
                                val isSelected = currentChunkIndex == chunkIndex
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { currentChunkIndex = chunkIndex },
                                    label = { Text("$startEp - $endEp", color = if (isSelected) currentTheme.backgroundColor() else currentTheme.textColor()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = currentTheme.accentColor(),
                                        containerColor = currentTheme.cardColor()
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = if (isSelected) currentTheme.accentColor() else currentTheme.subTextColor().copy(alpha = 0.3f),
                                        enabled = true,
                                        selected = isSelected
                                    )
                                )
                            }
                        }
                    }

                    val displayedEpisodes = if (episodesList.size > chunkSize) {
                        episodesList.chunked(chunkSize).getOrNull(currentChunkIndex) ?: emptyList()
                    } else {
                        episodesList
                    }

                    displayedEpisodes.forEachIndexed { i, ep ->
                        val actualIndex = currentChunkIndex * chunkSize + i
                        val locked = !isPremium && actualIndex >= freeEpisodeCount
                        val isDownloaded = remember(refreshTrigger, ep.episodeNumber) { downloadRepo.isEpisodeDownloaded(item.id, ep.episodeNumber) }
                        val isDownloading = ep.episodeNumber in downloadingEpisodes

                        Card(
                            onClick = { if (locked) onSubscribe() else playEpisode(ep) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (locked) Icons.Default.Lock else Icons.Default.PlayArrow, null, tint = currentTheme.accentColor(), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(ep.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = currentTheme.textColor(), modifier = Modifier.weight(1f))
                                if (locked) { Spacer(Modifier.width(8.dp)); Text("Premium", style = MaterialTheme.typography.labelSmall, color = currentTheme.accentColor(), fontWeight = FontWeight.Bold) }
                                else {
                                    IconButton(onClick = { downloadEpisode(ep) }, modifier = Modifier.size(36.dp)) {
                                        if (isDownloading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = currentTheme.accentColor())
                                        else if (isDownloaded) Icon(Icons.Default.OfflinePin, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                        else Icon(Icons.Outlined.Download, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(20.dp))
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
    lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
