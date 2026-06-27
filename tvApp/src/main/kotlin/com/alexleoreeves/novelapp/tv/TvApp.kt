package com.alexleoreeves.novelapp.tv

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
//  Unified Media Item for TV (resolves Anime, Manga, and Novels)
// ─────────────────────────────────────────────────────────────────────────────
data class TvMediaItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val description: String,
    val genres: List<String>,
    val nextEpisode: Int = 0,
    val format: String = "ANIME" // ANIME, MANGA, NOVEL
)

// ─────────────────────────────────────────────────────────────────────────────
//  TvApp — Root Layout
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvApp() {
    var showSplash by remember { mutableStateOf(true) }
    var selectedMedia by remember { mutableStateOf<TvMediaItem?>(null) }
    var animeStreamUrl by remember { mutableStateOf<String?>(null) }
    var animeTitle by remember { mutableStateOf("") }
    
    // Viewer screens
    var activeChapterText by remember { mutableStateOf<String?>(null) }
    var activeMangaPages by remember { mutableStateOf<List<String>?>(null) }
    var activeViewerTitle by remember { mutableStateOf("") }

    var currentSection by remember { mutableStateOf(TvSection.ANIME) }

    if (showSplash) {
        TvSplashScreen(onFinished = { showSplash = false })
        return
    }

    // Full-screen video player
    if (animeStreamUrl != null) {
        TvPlayerScreen(
            streamUrl = animeStreamUrl!!,
            title = animeTitle,
            onBack = { animeStreamUrl = null }
        )
        return
    }

    // Full-screen novel text reader
    if (activeChapterText != null) {
        TvNovelReaderScreen(
            title = activeViewerTitle,
            text = activeChapterText!!,
            onBack = { activeChapterText = null }
        )
        return
    }

    // Full-screen manga comic viewer
    if (activeMangaPages != null) {
        TvMangaViewerScreen(
            title = activeViewerTitle,
            pages = activeMangaPages!!,
            onBack = { activeMangaPages = null }
        )
        return
    }

    // Main Layout (Pure Black background)
    Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TvSidebar(
            currentSection = currentSection,
            onSectionChange = { 
                currentSection = it
                selectedMedia = null 
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                selectedMedia != null -> TvDetailScreen(
                    media = selectedMedia!!,
                    onPlayEpisode = { url, title -> 
                        animeStreamUrl = url
                        animeTitle = title 
                    },
                    onReadNovel = { text, title ->
                        activeChapterText = text
                        activeViewerTitle = title
                    },
                    onReadManga = { pages, title ->
                        activeMangaPages = pages
                        activeViewerTitle = title
                    },
                    onBack = { selectedMedia = null }
                )
                currentSection == TvSection.ANIME -> TvMediaHomeScreen(
                    format = "ANIME",
                    title = "🌸 Currently Airing Anime",
                    onMediaSelected = { selectedMedia = it }
                )
                currentSection == TvSection.MANGA -> TvMediaHomeScreen(
                    format = "MANGA",
                    title = "📚 Trending Manga",
                    onMediaSelected = { selectedMedia = it }
                )
                currentSection == TvSection.NOVELS -> TvMediaHomeScreen(
                    format = "NOVEL",
                    title = "📖 Popular Light Novels",
                    onMediaSelected = { selectedMedia = it }
                )
                currentSection == TvSection.DOWNLOADS -> TvDownloadsScreen(
                    onPlayEpisode = { url, title ->
                        animeStreamUrl = url
                        animeTitle = title
                    },
                    onReadManga = { pages, title ->
                        activeMangaPages = pages
                        activeViewerTitle = title
                    },
                    onReadNovel = { text, title ->
                        activeChapterText = text
                        activeViewerTitle = title
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Splash Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvSplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(3500); onFinished() }

    val pulse = rememberInfiniteTransition(label = "tv_pulse")
    val glow by pulse.animateFloat(
        0.75f, 1.0f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    var logoVis by remember { mutableStateOf(false) }
    var titleVis by remember { mutableStateOf(false) }
    var creditVis by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200); logoVis = true
        kotlinx.coroutines.delay(600); titleVis = true
        kotlinx.coroutines.delay(500); creditVis = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(Color(0xFF0F041C), Color(0xFF05050A), Color.Black))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(logoVis, enter = fadeIn(tween(500)) + scaleIn(tween(500))) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer { scaleX = glow; scaleY = glow },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF7C3AED).copy(0.4f), Color.Transparent)
                                ),
                                CircleShape
                            )
                    )
                    Icon(Icons.Default.AutoStories, null, tint = Color(0xFFFF2A85), modifier = Modifier.size(80.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
            AnimatedVisibility(titleVis, enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 3 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Watch Anime · Read Novels · Read Manga",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "— All in One —",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFFFF2A85).copy(0.9f)
                    )
                }
            }
        }

        AnimatedVisibility(
            creditVis, enter = fadeIn(tween(600)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text(
                    "Developed by Mike A. (Alex Leo Reeves)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(0.6f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "masteralexleoreevesd1@gmail.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8B5CF6)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Sidebar (OLED styled sidebar)
// ─────────────────────────────────────────────────────────────────────────────
enum class TvSection(val label: String, val icon: ImageVector) {
    ANIME("Anime", Icons.Default.PlayCircle),
    MANGA("Manga", Icons.Default.Collections),
    NOVELS("Novels", Icons.Default.AutoStories),
    DOWNLOADS("Downloads", Icons.Default.Download)
}

@Composable
private fun TvSidebar(currentSection: TvSection, onSectionChange: (TvSection) -> Unit) {
    Column(
        modifier = Modifier
            .width(230.dp)
            .fillMaxHeight()
            .background(Color(0xFF06060A))
            .border(end = 1.dp, color = Color.White.copy(0.06f))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // App branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFF2A85), Color(0xFF8B5CF6)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text(
                    "All-in-One",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
            }

            TvSection.values().forEach { section ->
                val selected = currentSection == section
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val scale by animateFloatAsState(if (isFocused) 1.06f else 1.0f)

                Surface(
                    onClick = { onSectionChange(section) },
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        selected -> Color(0xFF8B5CF6).copy(0.25f)
                        isFocused -> Color.White.copy(0.08f)
                        else -> Color.Transparent
                    },
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF8B5CF6)) else null,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            section.icon,
                            null,
                            tint = if (selected || isFocused) Color(0xFF8B5CF6) else Color.White.copy(0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            section.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected || isFocused) Color.White else Color.White.copy(0.5f),
                            fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Sidebar credit footer
        Column(modifier = Modifier.padding(horizontal = 6.dp)) {
            Text("Developed by", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.3f))
            Text(
                "Mike A.",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(0.7f),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "masteralexleoreevesd1@gmail.com",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8B5CF6).copy(0.8f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Media Home (Dynamic GraphQL Fetcher per Section)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvMediaHomeScreen(
    format: String,
    title: String,
    onMediaSelected: (TvMediaItem) -> Unit
) {
    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
    }
    var itemsList by remember { mutableStateOf<List<TvMediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(format) {
        isLoading = true
        itemsList = fetchTvMediaItems(client, format)
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(start = 28.dp, top = 28.dp, end = 28.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF8B5CF6), modifier = Modifier.size(48.dp))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(itemsList) { media ->
                    TvMediaCard(media = media, onClick = { onMediaSelected(media) })
                }
            }
        }
    }
}

suspend fun fetchTvMediaItems(client: HttpClient, format: String): List<TvMediaItem> {
    return try {
        val queryType = if (format == "ANIME") "ANIME" else "MANGA"
        val formatQuery = if (format == "ANIME") "" else "format: $format,"
        val isAiring = if (format == "ANIME") "status: RELEASING," else ""
        
        val gqlQuery = """
            query {
              Page(page: 1, perPage: 16) {
                media(type: $queryType, $formatQuery $isAiring sort: POPULARITY_DESC) {
                  id
                  title { english romaji }
                  coverImage { large }
                  description(asHtml: false)
                  genres
                  nextAiringEpisode { episode }
                }
              }
            }
        """.trimIndent()

        val reqBody = buildJsonObject {
            put("query", gqlQuery)
        }

        val response: String = client.post("https://graphql.anilist.co") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(reqBody.toString())
        }.body()

        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(response).jsonObject
        val mediaArr = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray
        mediaArr?.mapNotNull { el ->
            val obj = el.jsonObject
            val titleObj = obj["title"]?.jsonObject
            val title = (titleObj?.get("english")?.jsonPrimitive?.contentOrNull 
                ?: titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull) ?: "Unknown Item"
            val rawDesc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "No description available."
            val cleanDesc = rawDesc.replace(Regex("<[^>]*>"), "")
            
            TvMediaItem(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                title = title,
                coverUrl = obj["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull ?: "",
                description = cleanDesc,
                genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                nextEpisode = obj["nextAiringEpisode"]?.jsonObject?.get("episode")?.jsonPrimitive?.intOrNull ?: 0,
                format = format
            )
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
private fun TvMediaCard(media: TvMediaItem, onClick: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f)
    val accent = if (media.format == "ANIME") Color(0xFFFF2A85) else Color(0xFF8B5CF6)

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF14141E) else Color(0xFF0C0C12)
        ),
        border = if (isFocused) BorderStroke(3.dp, accent) else BorderStroke(1.dp, Color.White.copy(0.05f)),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                            listOf(Color.Transparent, Color.Black.copy(0.85f)),
                            startY = 0.5f
                        )
                    )
            )

            // Content title overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    media.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (media.genres.isNotEmpty()) {
                    Text(
                        media.genres.take(2).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.5f)
                    )
                }
            }

            // Airing / Category Badge
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(
                    when (media.format) {
                        "ANIME" -> "A"
                        "MANGA" -> "M"
                        else -> "N"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Detail Screen (Unified details overlay, with dynamic Gogo extraction)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvDetailScreen(
    media: TvMediaItem,
    onPlayEpisode: (streamUrl: String, epTitle: String) -> Unit,
    onReadNovel: (text: String, title: String) -> Unit,
    onReadManga: (pages: List<String>, title: String) -> Unit,
    onBack: () -> Unit
) {
    val client = remember { HttpClient(OkHttp) }
    val scope = rememberCoroutineScope()
    var isLoadingContent by remember { mutableStateOf(false) }
    var episodesList by remember { mutableStateOf<List<String>>(emptyList()) }
    var actionText by remember { mutableStateOf("Ready") }

    LaunchedEffect(media) {
        if (media.format == "ANIME") {
            isLoadingContent = true
            episodesList = fetchTvAnimeEpisodes(client, media.title)
            isLoadingContent = false
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(28.dp)
    ) {
        Column {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        media.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        media.genres.joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF8B5CF6)
                    )
                }
            }

            // Description + Actions
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                // Left Column: Cover + Synopsis
                Column(modifier = Modifier.weight(1.2f)) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .width(180.dp)
                            .aspectRatio(0.72f)
                            .align(Alignment.CenterHorizontally)
                    ) {
                        AsyncImage(
                            model = media.coverUrl,
                            contentDescription = media.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        media.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.7f),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Right Column: Grid list of items
                Column(modifier = Modifier.weight(2f)) {
                    Text(
                        when (media.format) {
                            "ANIME" -> "Episodes"
                            "MANGA" -> "Chapters"
                            else -> "Chapters"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (isLoadingContent) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFFFF2A85))
                        }
                    } else {
                        when (media.format) {
                            "ANIME" -> {
                                if (episodesList.isEmpty()) {
                                    Text("No streamable episodes found on GogoAnime.", color = Color.White.copy(0.4f))
                                } else {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(episodesList.size) { index ->
                                            val epNum = index + 1
                                            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                            val isFocused by interactionSource.collectIsFocusedAsState()

                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        actionText = "Extracting stream link…"
                                                        val url = extractTvStreamUrl(client, media.title, epNum)
                                                        if (url != null) {
                                                            onPlayEpisode(url, "${media.title} - Episode $epNum")
                                                        } else {
                                                            actionText = "Failed to resolve stream link."
                                                        }
                                                    }
                                                },
                                                interactionSource = interactionSource,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isFocused) Color(0xFFFF2A85) else Color(0xFF14141E)
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("Episode $epNum", color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                            "MANGA" -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(12) { idx ->
                                        val chapNum = idx + 1
                                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                        val isFocused by interactionSource.collectIsFocusedAsState()

                                        Button(
                                            onClick = {
                                                // Load high-quality demo manga pages
                                                val dummyPages = listOf(
                                                    "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
                                                    "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=800",
                                                    "https://images.unsplash.com/photo-1560942485-b2a11cc13456?w=800",
                                                    "https://images.unsplash.com/photo-1580477667995-2b94f01c9516?w=800"
                                                )
                                                onReadManga(dummyPages, "${media.title} - Chapter $chapNum")
                                            },
                                            interactionSource = interactionSource,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isFocused) Color(0xFF8B5CF6) else Color(0xFF14141E)
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Chapter $chapNum", color = Color.White)
                                        }
                                    }
                                }
                            }
                            "NOVEL" -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(12) { idx ->
                                        val chapNum = idx + 1
                                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                        val isFocused by interactionSource.collectIsFocusedAsState()

                                        Button(
                                            onClick = {
                                                val novelText = "This is a premium TV optimization demonstration of the light novel reader.\n\n" +
                                                        "Chapter $chapNum content:\n\n" +
                                                        "The wind rustled through the emerald leaves as our heroes gazed at the massive city walls. " +
                                                        "This was the capital of the Empire, a place of legend and high mystery.\n\n" +
                                                        "\"We must proceed with caution,\" whispered the guide. \"The guards are highly suspicious of foreign travellers after the recent events.\"\n\n" +
                                                        "With their hoods drawn close, they walked forward, blending in with the caravans of merchant wagons."
                                                onReadNovel(novelText, "${media.title} - Chapter $chapNum")
                                            },
                                            interactionSource = interactionSource,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isFocused) Color(0xFF8B5CF6) else Color(0xFF14141E)
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Chapter $chapNum", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Text(actionText, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.4f))
                }
            }
        }
    }
}

// GogoAnime scraper methods replicated inside TV module for clean dependency mapping
suspend fun fetchTvAnimeEpisodes(client: HttpClient, query: String): List<String> {
    return try {
        val searchHtml: String = client.get("https://gogoanime3.co/search.html") {
            parameter("keyword", query)
            header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }.body()

        val slugRegex = Regex("""href="/category/([^"]+)""")
        val slug = slugRegex.find(searchHtml)?.groupValues?.get(1) ?: return emptyList()

        val seriesHtml: String = client.get("https://gogoanime3.co/category/$slug") {
            header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }.body()

        val epEndRegex = Regex("""id="episode_page".*?<a.*?ep_end\s*=\s*"(\d+)"""", RegexOption.DOT_MATCHES_ALL)
        val total = epEndRegex.find(seriesHtml)?.groupValues?.get(1)?.toIntOrNull() ?: 12
        (1..total).map { "Episode $it" }
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun extractTvStreamUrl(client: HttpClient, query: String, episodeNum: Int): String? {
    return try {
        val searchHtml: String = client.get("https://gogoanime3.co/search.html") {
            parameter("keyword", query)
            header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }.body()
        val slugRegex = Regex("""href="/category/([^"]+)""")
        val slug = slugRegex.find(searchHtml)?.groupValues?.get(1) ?: return null

        val epUrl = "https://gogoanime3.co/$slug-episode-$episodeNum"
        val html: String = client.get(epUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }.body()

        val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
        val direct = m3u8Regex.find(html)?.value
        if (direct != null) return direct

        val iframeRegex = Regex("""<iframe[^>]+src="([^"]+)"""")
        val iframeUrl = iframeRegex.find(html)?.groupValues?.get(1)
        if (iframeUrl != null) {
            val iframeHtml: String = client.get(iframeUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
                header("Referer", epUrl)
            }.body()
            return m3u8Regex.find(iframeHtml)?.value
        }
        null
    } catch (e: Exception) {
        null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Downloads Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvDownloadsScreen(
    onPlayEpisode: (url: String, title: String) -> Unit,
    onReadManga: (pages: List<String>, title: String) -> Unit,
    onReadNovel: (text: String, title: String) -> Unit
) {
    val context = LocalContext.current
    var activeCategory by remember { mutableStateOf("ANIME") }
    
    // Scan directory directly for a clean cross-package display
    val localFiles = remember {
        val list = mutableListOf<File>()
        val dir = File(context.filesDir, "downloads")
        if (dir.exists()) {
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".mp4") || file.name.endsWith(".txt") || file.isDirectory) {
                    list.add(file)
                }
            }
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(28.dp)
    ) {
        Text(
            "💾 Downloaded Offline items",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // Subcategory select Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf("ANIME", "MANGA", "NOVELS").forEach { cat ->
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val selected = activeCategory == cat
                
                Button(
                    onClick = { activeCategory = cat },
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            selected -> Color(0xFF8B5CF6)
                            isFocused -> Color.White.copy(0.12f)
                            else -> Color(0xFF14141E)
                        }
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(cat, color = Color.White)
                }
            }
        }

        if (localFiles.isEmpty()) {
            // Stylized placeholder notice for easy simulation
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.CloudDownload, null, tint = Color.White.copy(0.15f), modifier = Modifier.size(80.dp))
                    Text("No local files found in TV sandbox", style = MaterialTheme.typography.titleLarge, color = Color.White.copy(0.5f))
                    
                    // Offline playback triggers for demo
                    Text("Or, launch simulated download files below for testing:", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.3f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val int1 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val isF1 by int1.collectIsFocusedAsState()
                        Button(
                            onClick = { onPlayEpisode("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", "Offline Anime demo") },
                            interactionSource = int1,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isF1) Color(0xFFFF2A85) else Color(0xFF1E1E2E))
                        ) {
                            Text("Play Offline Anime")
                        }

                        val int2 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val isF2 by int2.collectIsFocusedAsState()
                        Button(
                            onClick = { 
                                onReadManga(
                                    listOf(
                                        "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
                                        "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=800"
                                    ),
                                    "Offline Manga demo"
                                ) 
                            },
                            interactionSource = int2,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isF2) Color(0xFF8B5CF6) else Color(0xFF1E1E2E))
                        ) {
                            Text("Read Offline Manga")
                        }

                        val int3 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val isF3 by int3.collectIsFocusedAsState()
                        Button(
                            onClick = { 
                                onReadNovel(
                                    "This is a local text document stored in device memory.\n\nAll narration features work offline utilizing standard Android text synthesis.",
                                    "Offline Novel demo"
                                ) 
                            },
                            interactionSource = int3,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isF3) Color(0xFF10B981) else Color(0xFF1E1E2E))
                        ) {
                            Text("Read Offline Novel")
                        }
                    }
                }
            }
        } else {
            // Render actual scanned directories
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(localFiles) { file ->
                    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    Card(
                        onClick = {
                            if (file.name.endsWith(".mp4")) {
                                onPlayEpisode(file.absolutePath, file.name)
                            } else if (file.name.endsWith(".txt")) {
                                onReadNovel(file.readText(), file.name.removeSuffix(".txt"))
                            }
                        },
                        interactionSource = interactionSource,
                        colors = CardDefaults.cardColors(containerColor = if (isFocused) Color(0xFF2C2C3E) else Color(0xFF14141E)),
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(file.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV ExoPlayer Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvPlayerScreen(streamUrl: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    var showControls by remember { mutableStateOf(true) }
    LaunchedEffect(showControls) { if (showControls) { kotlinx.coroutines.delay(4500); showControls = false } }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { showControls = !showControls }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true // default TV controls
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.45f))) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(20.dp).size(50.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 80.dp, top = 26.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Novel Fullscreen Text Reader
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvNovelReaderScreen(title: String, text: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(40.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            }
            
            // Large D-pad scrollable content box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(12.dp))
                    .padding(24.dp)
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 32.sp
                    ),
                    color = Color.White.copy(0.85f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV Manga Fullscreen Viewer (D-pad Left/Right flip)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TvMangaViewerScreen(title: String, pages: List<String>, onBack: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }
    
    BackHandler { onBack() }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Comic slide image
        AsyncImage(
            model = pages[currentPage],
            contentDescription = "Page $currentPage",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay layout
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.Black.copy(0.6f), CircleShape)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(8.dp))
                }
            }

            // D-pad control overlays
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val backInt = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val backFoc by backInt.collectIsFocusedAsState()
                Button(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0,
                    interactionSource = backInt,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (backFoc) Color(0xFF8B5CF6) else Color.Black.copy(0.6f)
                    )
                ) {
                    Text("◀ Prev Page")
                }

                Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(8.dp)) {
                    Text("Page ${currentPage + 1} of ${pages.size}", color = Color.White, modifier = Modifier.padding(8.dp))
                }

                val nextInt = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val nextFoc by nextInt.collectIsFocusedAsState()
                Button(
                    onClick = { if (currentPage < pages.size - 1) currentPage++ },
                    enabled = currentPage < pages.size - 1,
                    interactionSource = nextInt,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (nextFoc) Color(0xFF8B5CF6) else Color.Black.copy(0.6f)
                    )
                ) {
                    Text("Next Page ▶")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TV back handler utility
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BackHandler(onBack: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        // Note: Basic handler inside Compose context
        onDispose {}
    }
}
