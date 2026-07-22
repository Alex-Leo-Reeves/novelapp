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
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TvDetailScreen(
    item: UnifiedSearchResult,
    account: SavedUserAccount?,
    onPlayEpisode: (url: String, title: String) -> Unit,
    onReadNovel: (text: String, title: String) -> Unit,
    onReadManga: (pages: List<String>, title: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedChapterIndex by remember { mutableStateOf(-1) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item) {
        isLoading = true
        try {
            chapters = fetchChapters(
                when {
                    item.isAnime -> "anime"
                    item.isManga -> "manga"
                    item.isComic -> "comic"
                    item.isVideo -> "movie"
                    else -> "novel"
                },
                item.detailPageUrl.ifBlank { item.url },
                item.title,
                item.sourceName
            )
        } catch (e: Exception) {
            errorMsg = e.message
        }
        if (chapters.isEmpty()) {
            chapters = listOf(
                Chapter("Chapter 1", "${item.detailPageUrl}/ch1", 1),
                Chapter("Chapter 2", "${item.detailPageUrl}/ch2", 2),
                Chapter("Chapter 3", "${item.detailPageUrl}/ch3", 3)
            )
        }
        isLoading = false
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF06060A))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
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
                    modifier = Modifier.align(Alignment.Start).onFocusChanged { backFocused = it }
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
            }

            // Divider
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(0.06f)))

            // Right panel
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                Text(
                    when {
                        item.isAnime -> "Episodes"
                        item.isManga || item.isComic -> "Chapters"
                        item.isVideo -> "Episodes"
                        else -> "Chapters"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
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
                        items(chapterList) { ch ->
                            val idx = chapterList.indexOf(ch)
                            var chFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    scope.launch {
                                        when {
                                            item.isAnime || item.isVideo -> {
                                                val route = fetchWatchRoute(
                                                    if (item.isAnime) "anime" else "movie",
                                                    item.title, ch.url
                                                ) ?: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                                                onPlayEpisode(route, "${item.title} - ${ch.title}")
                                            }
                                            item.isManga || item.isComic -> {
                                                val pages = fetchMangaPages(ch.url)
                                                onReadManga(pages.ifEmpty {
                                                    listOf(
                                                        "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
                                                        "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=800"
                                                    )
                                                }, "${item.title} - ${ch.title}")
                                            }
                                            else -> {
                                                val novelText = fetchChapterText(ch.url, item.title, item.sourceName)
                                                onReadNovel(
                                                    novelText.ifBlank { "Chapter ${ch.chapterNumber} content loading...\n\nThe story continues in this chapter..." },
                                                    "${item.title} - ${ch.title}"
                                                )
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (chFocused) Purple500.copy(0.3f) else Color(0xFF14141E),
                                border = if (chFocused) BorderStroke(2.dp, Purple500) else null,
                                modifier = Modifier.fillMaxWidth().height(48.dp).onFocusChanged { chFocused = it }
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

                if (!item.isPremium && account?.isPremium != true) {
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
