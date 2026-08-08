package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.activity.compose.BackHandler

@Composable
fun TvDetailScreen(
    item: UnifiedSearchResult,
    account: SavedUserAccount?,
    onPlayDirectStream: (url: String, title: String, previewLimitMs: Long?) -> Unit,
    onPlayEmbed: (url: String, title: String, previewLimitMs: Long?) -> Unit,
    onReadNovel: (text: String, title: String) -> Unit,
    onReadManga: (pages: List<String>, title: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    
    val initialFocusRequester = remember { FocusRequester() }
    val firstEpisodeFocusRequester = remember { FocusRequester() }

    val mediaRepo = remember { TvMediaRepository() }
    val novelRepo = remember { TvNovelSearchRepository() }

    val isVideoTitle = item.isAnime || item.isVideo
    val kind = if (item.isAnime) "anime"
        else if (item.isManga) "manga"
        else if (item.isComic) "comic"
        else if (item.isVideo) item.mediaKind.lowercase().ifBlank { "movie" }
        else "novel"
        
    val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)

    var selectedServer by remember { mutableStateOf(StreamServer.VIDLINK) }
    var selectedDonghuaServer by remember { mutableStateOf(DonghuaServer.DONGHUA_STREAM) }
    var statusText by remember { mutableStateOf("") }

    LaunchedEffect(item) {
        isLoading = true
        errorMsg = null
        statusText = ""
        try {
            val fetched = if (isVideoTitle) {
                mediaRepo.fetchVideoEpisodes(item)
            } else if (kind == "novel") {
                novelRepo.fetchChapters(item.detailPageUrl.ifBlank { item.url }, item.sourceName)
            } else {
                fetchChapters(
                    if (item.isAnime) "anime" else kind,
                    item.detailPageUrl.ifBlank { item.url },
                    item.title,
                    item.sourceName
                )
            }
            chapters = fetched
        } catch (e: Exception) {
            errorMsg = e.message
        }
        isLoading = false
        
        delay(100)
        try { 
            if (chapters.isNotEmpty()) {
                firstEpisodeFocusRequester.requestFocus() 
            } else {
                initialFocusRequester.requestFocus() 
            }
        } catch (e: Exception) {}
    }

    fun playMedia(chapter: Chapter? = null) {
        scope.launch {
            statusText = "Resolving stream..."
            if (isVideoTitle) {
                val url = mediaRepo.resolveStreamUrl(item, chapter, selectedServer, selectedDonghuaServer)
                if (url != null) {
                    val titleSuffix = chapter?.let { " - ${it.title}" } ?: ""
                    val fullTitle = "${item.title}$titleSuffix"
                    statusText = ""

                    if (selectedServer == StreamServer.VIDLINK_EXO) {
                        // Server 5 (VidLink Exo): scrape the VidLink page to a direct
                        // .m3u8/.mp4 via a hidden WebView, then play natively.
                        statusText = "Resolving native stream..."
                        val scraped = extractTvStreamFromEmbed(context, url)
                        statusText = ""
                        if (scraped != null) {
                            onPlayDirectStream(scraped.url, fullTitle, null)
                        } else {
                            statusText = "Server 5 could not resolve a native stream. Try another server."
                        }
                    } else if (!isDonghua && isTvPlayableStreamUrl(url)) {
                        // Direct stream (.m3u8/.mp4 from anime/drama/cartoon scrapers)
                        // can't run inside a WebView → native player.
                        onPlayDirectStream(url, fullTitle, null)
                    } else {
                        // Embed page (Servers 1-4, 6, 7, 8 + all Donghua) → full
                        // WebView browser/web player so the provider's own player shows.
                        onPlayEmbed(url, fullTitle, null)
                    }
                } else {
                    statusText = "Stream unavailable. Try another server."
                }
            } else if (item.isManga || item.isComic) {
                val chUrl = chapter?.url ?: return@launch
                val pages = fetchMangaPages(chUrl)
                statusText = ""
                onReadManga(
                    pages.ifEmpty {
                        listOf(
                            "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
                            "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=800"
                        )
                    },
                    "${item.title} - ${chapter.title}"
                )
            } else {
                val chUrl = chapter?.url ?: return@launch
                val novelText = if (kind == "novel") {
                    novelRepo.fetchChapterText(chUrl, item.sourceName)
                } else {
                    fetchChapterText(chUrl, item.title, item.sourceName)
                }
                statusText = ""
                onReadNovel(
                    novelText.ifBlank { "Loading chapter ${chapter.chapterNumber}…" },
                    "${item.title} - ${chapter.title}"
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Left panel
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var backFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(10.dp),
                    color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                    border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.align(Alignment.Start)
                        .focusRequester(if (chapters.isEmpty()) initialFocusRequester else FocusRequester.Default)
                        .onFocusChanged { backFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Back", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(240.dp).aspectRatio(0.7f)
                ) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                if (item.author.isNotBlank()) {
                    Text("by ${item.author}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.6f))
                }

                if (item.genre.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.genre.split(",").take(3).forEach { tag ->
                            Surface(color = Purple500.copy(0.2f), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    tag.trim(), color = Purple500, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                if (item.synopsis.isNotBlank()) {
                    Text(item.synopsis, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.65f), lineHeight = 20.sp)
                }

                Spacer(Modifier.height(8.dp))

                Surface(color = Color(0xFF14141E), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "Source: ${item.sourceName.ifBlank { "NovaRead" }}",
                        color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (isVideoTitle) {
                    var watchFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { playMedia(chapters.firstOrNull()) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (watchFocused) Color(0xFF00BFFF) else Color(0xFF00BFFF).copy(0.15f),
                        border = if (watchFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.4f)),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                            .onFocusChanged { watchFocused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (chapters.isNotEmpty()) "Watch Episode 1" else "Watch Now",
                                color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
                
                if (statusText.isNotBlank()) {
                    Text(statusText, color = Color(0xFF00BFFF), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Divider
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(0.06f)))

            // Right panel
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                Text(
                    when {
                        item.isAnime -> "Episodes"
                        item.isManga || item.isComic -> "Chapters"
                        item.isVideo && chapters.isNotEmpty() -> "Episodes"
                        item.isVideo -> "Media"
                        else -> "Chapters"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isVideoTitle && !item.id.startsWith("youtube_nollywood_")) {
                    // Server Selection Row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        if (isDonghua) {
                            items(DonghuaServer.ALL_IN_ORDER) { server ->
                                val isSelected = selectedDonghuaServer == server
                                var sFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { selectedDonghuaServer = server },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                    border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                    modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                                ) {
                                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(server.displayName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        } else {
                            items(StreamServer.ALL_IN_ORDER) { server ->
                                val isSelected = selectedServer == server
                                var sFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { selectedServer = server },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                    border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                    modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                                ) {
                                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(server.displayName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
                    }
                } else if (chapters.isEmpty() && isVideoTitle && !item.isAnime) {
                     Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Movie, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(48.dp))
                            Text("Ready to watch", color = Color.White.copy(0.5f))
                        }
                    }
                } else if (chapters.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(48.dp))
                            Text("No chapters available", color = Color.White.copy(0.5f))
                        }
                    }
                } else {
                    val chapterList = chapters.sortedBy { it.chapterNumber }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(chapterList.size) { index ->
                            val ch = chapterList[index]
                            var chFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { playMedia(ch) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (chFocused) Purple500.copy(0.3f) else Color(0xFF14141E),
                                border = if (chFocused) BorderStroke(2.dp, Purple500) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .then(if (index == 0) Modifier.focusRequester(firstEpisodeFocusRequester) else Modifier)
                                    .onFocusChanged { chFocused = it.isFocused }
                            ) {
                                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                    Text(
                                        ch.title.ifBlank { "Chapter ${ch.chapterNumber}" },
                                        color = Color.White,
                                        fontWeight = if (chFocused) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (account?.isPremium != true) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = Color(0xFF00BFFF).copy(0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Lock, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(18.dp))
                            Text("Some content may require premium", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
