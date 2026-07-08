package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount

@Composable
fun TvDownloadsScreen(
    account: SavedUserAccount? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Download, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(32.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
        }

        Spacer(Modifier.height(8.dp))
        Text("Your offline content library", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(32.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.CloudDownload, null, tint = Color.White.copy(0.15f), modifier = Modifier.size(96.dp))
                Text("Downloads Coming Soon", color = Color.White.copy(0.5f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Download episodes, chapters and novels for offline viewing.\nThis feature will be available in the next update.",
                    color = Color.White.copy(0.3f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.widthIn(max = 400.dp)
                )
            }
        }
    }
}
