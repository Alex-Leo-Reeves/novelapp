package com.alexleoreeves.novelapp.tv.ui.screens

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.alexleoreeves.novelapp.tv.data.*

@Composable
fun TvDonghuaScreen(
    onPlayStream: (url: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
    }
    var items by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<UnifiedSearchResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        items = fetchContentHome("donghua")
        isLoading = false
    }

    if (selectedItem != null) {
        TvDetailScreen(
            media = com.alexleoreeves.novelapp.tv.TvMediaItem(
                id = selectedItem!!.id,
                title = selectedItem!!.title,
                coverUrl = selectedItem!!.coverUrl,
                description = selectedItem!!.synopsis,
                genres = selectedItem!!.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                format = "ANIME",
                detailPageUrl = selectedItem!!.detailPageUrl
            ),
            onPlayEpisode = { url, title -> onPlayStream(url, title) },
            onReadNovel = { _, _ -> },
            onReadManga = { _, _ -> },
            onBack = { selectedItem = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF06060A)).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 20.dp)) {
            val backInt = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val backFoc by backInt.collectIsFocusedAsState()
            Surface(onClick = onBack, shape = RoundedCornerShape(10.dp), color = if (backFoc) Color(0xFF1C1C2E) else Color.Transparent,
                border = if (backFoc) BorderStroke(2.dp, Color(0xFF00BFFF)) else null, interactionSource = backInt) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Back", color = Color.White)
                }
            }
            Text("Donghua", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Black)
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE91E63), modifier = Modifier.size(48.dp))
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(items) { item ->
                    val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val foc by int.collectIsFocusedAsState()
                    val scale by animateFloatAsState(if (foc) 1.08f else 1f)
                    Card(onClick = { selectedItem = item }, shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF14141E) else Color(0xFF0C0C12)),
                        border = if (foc) BorderStroke(3.dp, Color(0xFFE91E63)) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                        interactionSource = int, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).graphicsLayer { scaleX = scale; scaleY = scale }) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = item.coverUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)), startY = 0.5f)))
                            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFE91E63), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                                Text("DH", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
