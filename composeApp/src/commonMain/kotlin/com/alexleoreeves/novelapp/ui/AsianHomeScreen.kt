package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * ASIAN tab — three regions, three dedicated servers.
 *
 * The label is "Asian" and the body is three separate rows, one per region:
 * **Chinese Movies**, **Indian** and **Filipino**. Each row is its own TMDB
 * discover query, and each region plays through its own dedicated server behind
 * `/api/asian/` (see `data/AsianApi.kt`):
 *
 *  * Chinese Movies and Indian resolve to a DIRECT public HLS playlist
 *    (AES-128, zero request headers) — verified playable in ExoPlayer, LibVLC,
 *    AVPlayer and hls.js, so no WebView and no redirect to the source site.
 *  * Filipino uses the same source when it has the title and otherwise the
 *    official Star Cinema / Viva / Regal uploads, which the YouTube player
 *    handles.
 *
 * The rows deliberately do NOT share one server: coverage was measured per
 * region against the app's existing servers (vidlink.pro — Indian 6/10,
 * Chinese 6/11, Filipino 4/9; vidsrc.to / autoembed / 2embed / multiembed /
 * nontongo all 0/4), so each region resolves through the source that has it.
 */
@Composable
fun AsianHomeScreen(
    currentTheme: AppTheme,
    isKidsMode: Boolean = false,
    onTitleSelected: (UnifiedSearchResult) -> Unit
) {
    val client = remember { io.ktor.client.HttpClient() }
    val tmdb = remember {
        TmdbSource(
            client = client,
            readAccessToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN,
            apiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY
        )
    }

    // (row label, region category for the server badge, fetch lambda). The
    // two genre rows (Wuxia/Martial Arts + Xianxia/Cultivation) carry
    // CHINESE_MOVIES because TmdbSource tags their items with that mediaKind,
    // which routes them through the Chinese region's dedicated server.
    val rows: List<Triple<String, VideoCategory, suspend () -> List<UnifiedSearchResult>>> = remember {
        listOf(
            Triple("Chinese Movies", VideoCategory.CHINESE_MOVIES) { tmdb.fetchVideo(VideoCategory.CHINESE_MOVIES, 1) },
            Triple("Indian", VideoCategory.INDIAN) { tmdb.fetchVideo(VideoCategory.INDIAN, 1) },
            Triple("Filipino", VideoCategory.FILIPINO) { tmdb.fetchVideo(VideoCategory.FILIPINO, 1) },
            Triple("Wuxia & Martial Arts", VideoCategory.CHINESE_MOVIES) { tmdb.fetchWuxiaRow(1) },
            Triple("Xianxia & Cultivation", VideoCategory.CHINESE_MOVIES) { tmdb.fetchXianxiaRow(1) }
        )
    }

    var rowData by remember { mutableStateOf<Map<String, List<UnifiedSearchResult>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        // All rows load in parallel so the tab is never half-empty.
        val loaded = coroutineScope {
            rows.map { row ->
                async {
                    val items = runCatching { row.third() }
                        .getOrElse { emptyList() }
                        .filter { !isKidsMode || !it.genre.contains("horror", ignoreCase = true) }
                    row.first to items
                }
            }.awaitAll()
        }
        rowData = loaded.toMap()
        loading = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor()),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Asian",
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Chinese movies, wuxia & xianxia, Indian and Filipino — each on its own server",
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sources: direct HLS (ikanbot) · official Filipino channels",
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        items(rows) { row ->
            val label = row.first
            val regionItems = rowData[label].orEmpty()
            Column(modifier = Modifier.padding(bottom = 18.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        color = currentTheme.textColor(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        serverLabelFor(row.second),
                        color = currentTheme.accentColor(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                when {
                    regionItems.isNotEmpty() -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(regionItems, key = { it.id }) { item ->
                            AsianPosterCard(item, currentTheme) { onTitleSelected(item) }
                        }
                    }
                    loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .width(118.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(currentTheme.surfaceColor())
                            )
                        }
                    }
                    else -> Text(
                        "Nothing loaded for this region right now.",
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

/** The dedicated server each region plays on, shown on the row header. */
private fun serverLabelFor(category: VideoCategory): String = when (category) {
    VideoCategory.CHINESE_MOVIES -> "· Direct HLS"
    VideoCategory.INDIAN -> "· Direct HLS"
    VideoCategory.FILIPINO -> "· Direct HLS / YouTube"
    else -> ""
}

@Composable
private fun AsianPosterCard(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(118.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(118.dp)
                .height(168.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(currentTheme.surfaceColor())
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            color = currentTheme.textColor(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
