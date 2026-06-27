package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme

/**
 * iOS stub — AVPlayer integration can be wired here via
 * expect/actual + UIKitView for full native iOS support.
 */
@Composable
actual fun AnimePlayerScreen(
    streamUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.PlayCircle,
                null,
                tint = Color.White.copy(0.4f),
                modifier = Modifier.size(80.dp)
            )
            Text(
                "iOS Player Coming Soon",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "AVPlayer integration required",
                color = Color.White.copy(0.6f),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onBack) {
                Text("Back", color = Color.White)
            }
        }
    }
}
