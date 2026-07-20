package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.*

@Composable
fun YouTubeNollywoodDetailScreen(
    item: NollywoodYouTubeItem,
    currentTheme: AppTheme,
    onPlayInYtPlayer: (videoId: String, title: String) -> Unit,
    onPlayInExoPlayer: (videoId: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // Hero
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
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
                            listOf(Color.Black.copy(0.3f), Color.Black.copy(0.9f))
                        )
                    )
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 40.dp, start = 8.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Play button overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.channelTitle.isNotEmpty()) {
                    Text(
                        item.channelTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.7f)
                    )
                }
            }
        }

        // Description
        if (item.description.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Description",
                    style = MaterialTheme.typography.headlineMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = currentTheme.subTextColor(),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Divider(color = currentTheme.accentColor().copy(0.2f))
        }

        // Play buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Watch Options",
                style = MaterialTheme.typography.titleLarge,
                color = currentTheme.textColor(),
                fontWeight = FontWeight.Bold
            )

            // YouTube Player button
            Button(
                onClick = { onPlayInYtPlayer(item.videoId, item.title) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("▶ Play in YouTube Player", color = Color.White, fontWeight = FontWeight.Bold)
            }

            // ExoPlayer (Piped) button
            OutlinedButton(
                onClick = { onPlayInExoPlayer(item.videoId, item.title) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, currentTheme.accentColor())
            ) {
                Icon(Icons.Default.PlayCircle, null, tint = currentTheme.accentColor(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("▶ Play in App Player (Piped)", color = currentTheme.textColor())
            }

            Spacer(Modifier.height(8.dp))

            if (item.publishedAt.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Published: ${item.publishedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = currentTheme.subTextColor()
                    )
                }
            }
        }
    }
}
