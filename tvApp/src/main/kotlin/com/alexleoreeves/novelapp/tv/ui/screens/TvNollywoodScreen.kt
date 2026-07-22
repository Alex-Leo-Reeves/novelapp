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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.alexleoreeves.novelapp.tv.data.*

data class NollywoodYtItem(
    val videoId: String,
    val title: String,
    val description: String = "",
    val channelTitle: String = "",
    val thumbnail: String = ""
)

@Composable
fun TvNollywoodScreen(
    onPlayStream: (url: String, title: String) -> Unit,
    onPlayYouTube: (videoId: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
    }

    var tmdbItems by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var ytItems by remember { mutableStateOf<List<NollywoodYtItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            tmdbItems = fetchContentHome("nigerian")
            val ytResp: String = client.get("${ApiConfig.API_BASE_URL}/youtube/nollywood-feed").body()
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(ytResp).jsonObject
            val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
            ytItems = data.mapNotNull { el ->
                val obj = el.jsonObject
                NollywoodYtItem(
                    videoId = obj["videoId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    channelTitle = obj["channelTitle"]?.jsonPrimitive?.contentOrNull ?: "",
                    thumbnail = obj["thumbnail"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        isLoading = false
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
            Text("Nollywood", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Black)
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF008751), modifier = Modifier.size(48.dp))
            }
        } else {
            Text("TMDB Nigerian Films", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(tmdbItems) { item ->
                    val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val foc by int.collectIsFocusedAsState()
                    val scale by animateFloatAsState(if (foc) 1.08f else 1f)
                    Card(onClick = { onPlayStream(item.detailPageUrl, item.title) }, shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF14141E) else Color(0xFF0C0C12)),
                        border = if (foc) BorderStroke(3.dp, Color(0xFF01B4E4)) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                        interactionSource = int, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).graphicsLayer { scaleX = scale; scaleY = scale }) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = item.coverUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)), startY = 0.5f)))
                            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF01B4E4), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                                Text("TMDB", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            Text("YouTube Nollywood", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(ytItems) { yt ->
                    val int = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val foc by int.collectIsFocusedAsState()
                    val scale by animateFloatAsState(if (foc) 1.08f else 1f)
                    Card(onClick = { onPlayYouTube(yt.videoId, yt.title) }, shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (foc) Color(0xFF14141E) else Color(0xFF0C0C12)),
                        border = if (foc) BorderStroke(3.dp, Color.Red) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                        interactionSource = int, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).graphicsLayer { scaleX = scale; scaleY = scale }) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = yt.thumbnail, contentDescription = yt.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)), startY = 0.5f)))
                            Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(48.dp).align(Alignment.Center))
                            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                Text(yt.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Surface(shape = RoundedCornerShape(4.dp), color = Color.Red, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                                Text("YT", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
