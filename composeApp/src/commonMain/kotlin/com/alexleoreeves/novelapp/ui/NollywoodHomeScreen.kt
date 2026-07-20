package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
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
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.platformHttpClient
import com.alexleoreeves.novelapp.ui.theme.*
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class NollywoodYouTubeItem(
    val videoId: String,
    val title: String,
    val description: String = "",
    val channelTitle: String = "",
    val thumbnailUrl: String = "",
    val publishedAt: String = ""
)

sealed class NollywoodFeedItem {
    data class Tmdb(val result: UnifiedSearchResult) : NollywoodFeedItem()
    data class YouTube(val item: NollywoodYouTubeItem) : NollywoodFeedItem()
}

private val nigerianGreen = Color(0xFF008751)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NollywoodHomeScreen(
    currentTheme: AppTheme,
    onPlayStream: (String, String, Long?) -> Unit,
    onPlayYtVideo: (String, String) -> Unit,
    onPlayMaEmbed: (String, String) -> Unit,
    onBack: () -> Unit,
    requireAuth: (() -> Unit) -> Unit,
    onSubscribe: () -> Unit,
    downloadRepo: LocalDownloadRepository
) {
    val scope = rememberCoroutineScope()
    val httpClient = remember {
        platformHttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    var tmdbItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var youtubeItems by remember { mutableStateOf<List<NollywoodYouTubeItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedYtItem by remember { mutableStateOf<NollywoodYouTubeItem?>(null) }
    var selectedTmdbItem by remember { mutableStateOf<UnifiedSearchResult?>(null) }

    val feedItems = remember(tmdbItems, youtubeItems, searchQuery) {
        val results = mutableListOf<NollywoodFeedItem>()
        results.addAll(tmdbItems.map { NollywoodFeedItem.Tmdb(it) })
        results.addAll(youtubeItems.map { NollywoodFeedItem.YouTube(it) })
        if (searchQuery.isBlank()) results
        else results.filter { item ->
            val title = when (item) {
                is NollywoodFeedItem.Tmdb -> item.result.title
                is NollywoodFeedItem.YouTube -> item.item.title
            }
            title.contains(searchQuery, ignoreCase = true)
        }
    }

    fun loadFeeds(query: String = "") {
        scope.launch {
            isLoading = true
            try {
                // Fetch TMDB Nollywood
                val tmdbSource = TmdbSource(
                    client = httpClient,
                    readAccessToken = BuildKonfig.TMDB_READ_ACCESS_TOKEN,
                    apiKey = BuildKonfig.TMDB_API_KEY
                )
                val tmdb = tmdbSource.fetchVideo(VideoCategory.NIGERIAN, 1)
                tmdbItems = tmdb

                // Fetch YouTube Nollywood from render backend
                val ytResponse = httpClient.get("${AppReleaseConfig.API_BASE_URL}/youtube/nollywood-feed") {
                    parameter("page", 1)
                    if (query.isNotBlank()) parameter("q", query)
                }.bodyAsText()

                val ytRoot = Json { ignoreUnknownKeys = true }.parseToJsonElement(ytResponse).jsonObject
                val ytData = ytRoot["data"]?.jsonArray ?: JsonArray(emptyList())
                youtubeItems = ytData.mapNotNull { el ->
                    val obj = el.jsonObject
                    NollywoodYouTubeItem(
                        videoId = obj["videoId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        channelTitle = obj["channelTitle"]?.jsonPrimitive?.contentOrNull ?: "",
                        thumbnailUrl = obj["thumbnail"]?.jsonPrimitive?.contentOrNull ?: "",
                        publishedAt = obj["publishedAt"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } catch (e: Exception) {
                println("[Nollywood] Feed load failed: ${e.message}")
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadFeeds() }

    if (selectedYtItem != null) {
        YouTubeNollywoodDetailScreen(
            item = selectedYtItem!!,
            currentTheme = currentTheme,
            onPlayInYtPlayer = { videoId, title ->
                onPlayYtVideo(videoId, title)
            },
            onPlayInExoPlayer = { videoId, title ->
                scope.launch {
                    val streamUrl = resolveYouTubePipedStream(httpClient, videoId)
                    if (streamUrl != null) {
                        onPlayStream(streamUrl, title, null)
                    }
                }
            },
            onBack = { selectedYtItem = null }
        )
        return
    }

    if (selectedTmdbItem != null) {
        MediaDetailScreen(
            item = selectedTmdbItem!!,
            currentTheme = currentTheme,
            isPremium = false,
            downloadRepo = downloadRepo,
            requireAuth = requireAuth,
            onSubscribe = onSubscribe,
            onPlayStream = onPlayStream,
            onPlayMaEmbed = onPlayMaEmbed,
            onBack = { selectedTmdbItem = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .statusBarsPadding()
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(nigerianGreen.copy(0.3f), currentTheme.backgroundColor())
                    )
                )
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
                    }
                    Icon(
                        Icons.Default.Flag,
                        null,
                        tint = nigerianGreen,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            "Nollywood",
                            style = MaterialTheme.typography.headlineLarge,
                            color = currentTheme.textColor(),
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Nigerian films & YouTube videos",
                            style = MaterialTheme.typography.bodySmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search Nollywood...", color = currentTheme.subTextColor()) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = nigerianGreen) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                loadFeeds()
                            }) {
                                Icon(Icons.Default.Close, null, tint = currentTheme.subTextColor())
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = nigerianGreen,
                        unfocusedBorderColor = currentTheme.subTextColor().copy(0.3f),
                        focusedTextColor = currentTheme.textColor(),
                        unfocusedTextColor = currentTheme.textColor(),
                        cursorColor = nigerianGreen,
                        focusedContainerColor = currentTheme.cardColor().copy(0.5f),
                        unfocusedContainerColor = currentTheme.cardColor().copy(0.5f)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }

        // Content
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = nigerianGreen)
            }
        } else if (feedItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Flag, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(64.dp))
                    Text("No Nollywood content found", color = currentTheme.subTextColor(), style = MaterialTheme.typography.titleMedium)
                    Text("Pull down to refresh", color = currentTheme.subTextColor().copy(0.6f), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(feedItems.size) { index ->
                    val item = feedItems[index]
                    when (item) {
                        is NollywoodFeedItem.Tmdb -> NollywoodTmdbCard(
                            item = item.result,
                            currentTheme = currentTheme,
                            onClick = { selectedTmdbItem = item.result }
                        )
                        is NollywoodFeedItem.YouTube -> NollywoodYtCard(
                            item = item.item,
                            currentTheme = currentTheme,
                            onClick = { selectedYtItem = item.item }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NollywoodTmdbCard(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
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
                                listOf(Color.Transparent, Color.Black.copy(0.7f)),
                                startY = 0.6f
                            )
                        )
                )
                // TMDB badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF01B4E4),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text(
                        "TMDB",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.genre.isNotEmpty()) {
                    Text(
                        item.genre,
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.subTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NollywoodYtCard(
    item: NollywoodYouTubeItem,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(0.7f)),
                                startY = 0.6f
                            )
                        )
                )
                // YouTube badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFF0000),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text(
                        "YT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                // Play icon
                Icon(
                    Icons.Default.PlayCircle,
                    null,
                    tint = Color.White.copy(0.8f),
                    modifier = Modifier.size(36.dp).align(Alignment.Center)
                )
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.channelTitle.isNotEmpty()) {
                    Text(
                        item.channelTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.subTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

suspend fun resolveYouTubePipedStream(client: HttpClient, videoId: String): String? {
    return try {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.lunar.icu"
        )
        for (instance in instances) {
            try {
                val response = client.get("$instance/streams/$videoId") {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    header("Accept", "application/json")
                }.bodyAsText()
                val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(response).jsonObject
                val videoStreams = root["videoStreams"]?.jsonArray ?: JsonArray(emptyList())
                val sorted = videoStreams.mapNotNull { it.jsonObject }
                    .sortedByDescending { stream ->
                        val quality = stream["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        when {
                            quality.contains("720") -> 3000
                            quality.contains("480") -> 2000
                            quality.contains("360") -> 1000
                            else -> 0
                        }
                    }
                val best = sorted.firstOrNull()
                    ?.let { it["url"]?.jsonPrimitive?.contentOrNull }
                if (!best.isNullOrBlank()) return best
            } catch (e: Exception) { continue }
        }
        null
    } catch (e: Exception) { null }
}
