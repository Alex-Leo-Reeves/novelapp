package com.alexleoreeves.novelapp.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.data.fetchSimilarContent
import kotlinx.coroutines.delay

/**
 * Right-hand recommendations rail shown when a movie finishes. Loads similar
 * titles from the backend (TMDB /api/content/similar) and lets the user move
 * through them with the D-pad. Selecting one opens that title's detail screen
 * (where the user picks a server), via [onSelect].
 */
@Composable
fun TvMovieEndRail(
    item: UnifiedSearchResult?,
    onSelect: (UnifiedSearchResult) -> Unit
) {
    var recommendations by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val firstCardFocus = remember { FocusRequester() }

    LaunchedEffect(item?.detailPageUrl) {
        isLoading = true
        recommendations = if (item == null) emptyList() else fetchSimilarContent(item.detailPageUrl)
        isLoading = false
        delay(120)
        if (recommendations.isNotEmpty()) {
            runCatching { firstCardFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Color(0xFF0A0A12).copy(0.92f))
            .padding(20.dp)
    ) {
        Text(
            "More Like This",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick another title with your remote",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(0.5f)
        )
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF00BFFF), modifier = Modifier.size(40.dp))
            }
        } else if (recommendations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No similar titles right now.",
                    color = Color.White.copy(0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(recommendations) { index, rec ->
                    var focused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { onSelect(rec) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (focused) Color(0xFF00BFFF).copy(0.25f) else Color(0xFF14141E),
                        border = if (focused) BorderStroke(2.dp, Color(0xFF00BFFF))
                            else BorderStroke(1.dp, Color.White.copy(0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = rec.coverUrl,
                                contentDescription = rec.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(76.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rec.title,
                                    color = Color.White,
                                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (rec.author.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        rec.author,
                                        color = Color.White.copy(0.5f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
