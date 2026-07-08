package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*

@Composable
fun TvSportsScreen(
    account: SavedUserAccount?,
    onPlayStream: (url: String, title: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    var activeTab by remember { mutableStateOf(0) } // 0=Football, 1=WWE

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF06060A))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A12))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var backFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
                modifier = Modifier.onFocusChanged { backFocused = it.isFocused }
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Back", color = Color.White)
                }
            }

            Icon(Icons.Default.SportsSoccer, null, tint = Color(0xFF06D6A0), modifier = Modifier.size(32.dp))
            Text("Sports", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
        }

        // Tab bar (Football / WWE)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C0C14))
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf("⚽ Football", "💪 WWE").forEachIndexed { idx, label ->
                var tabFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { activeTab = idx },
                    shape = RoundedCornerShape(10.dp),
                    color = if (activeTab == idx) Color(0xFF06D6A0).copy(0.2f) else Color(0xFF14141E),
                    border = if (activeTab == idx) BorderStroke(2.dp, Color(0xFF06D6A0)) else if (tabFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.onFocusChanged { tabFocused = it.isFocused }
                ) {
                    Text(
                        label,
                        color = if (activeTab == idx) Color(0xFF06D6A0) else Color.White.copy(0.6f),
                        fontWeight = if (activeTab == idx) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }

        when (activeTab) {
            0 -> TvFootballScreen(account, onPlayStream)
            1 -> TvWweScreen(account, onPlayStream)
        }
    }
}

@Composable
private fun TvFootballScreen(
    account: SavedUserAccount?,
    onPlayStream: (url: String, title: String) -> Unit
) {
    var fixtures by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        fixtures = fetchFootballFixtures()
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color(0xFF06D6A0), modifier = Modifier.size(48.dp))
                Text("Loading football fixtures...", color = Color.White.copy(0.6f))
            }
        }
    } else if (fixtures.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SportsSoccer, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(64.dp))
                Text("No fixtures available", color = Color.White.copy(0.4f))
                Text("Check back for upcoming matches", color = Color.White.copy(0.3f))
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(fixtures.size) { idx ->
                val fixture = fixtures[idx]
                val homeTeam = fixture["homeTeam"]?.jsonPrimitive?.contentOrNull ?: "Home"
                val awayTeam = fixture["awayTeam"]?.jsonPrimitive?.contentOrNull ?: "Away"
                val league = fixture["league"]?.jsonPrimitive?.contentOrNull ?: "Unknown League"
                val fixtureId = fixture["fixtureId"]?.jsonPrimitive?.intOrNull ?: idx
                val status = fixture["status"]?.jsonPrimitive?.contentOrNull ?: "Scheduled"
                val score = fixture["score"]?.jsonPrimitive?.contentOrNull.orEmpty()

                var isFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val streamUrl = fetchFootballStream(fixtureId, homeTeam, awayTeam, league)
                            if (streamUrl != null) {
                                withContext(Dispatchers.Main) {
                                    onPlayStream(streamUrl, "$homeTeam vs $awayTeam")
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFocused) Color(0xFF06D6A0).copy(0.15f) else Color(0xFF0C0C14),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF06D6A0)) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Home
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF14141E),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(homeTeam.take(2), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(homeTeam, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                        }

                        // Score / VS
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (score.isNotBlank()) score else "VS", color = Color(0xFF06D6A0), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                            Text(league, color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelSmall)
                            Surface(color = Color(0xFF06D6A0).copy(0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text(status, color = Color(0xFF06D6A0), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }

                        // Away
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = CircleShape, color = Color(0xFF14141E), modifier = Modifier.size(48.dp)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(awayTeam.take(2), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(awayTeam, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                        }

                        // Play button
                        if (isFocused) {
                            Spacer(Modifier.width(16.dp))
                            Icon(Icons.Default.PlayCircle, null, tint = Color(0xFF06D6A0), modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvWweScreen(
    account: SavedUserAccount?,
    onPlayStream: (url: String, title: String) -> Unit
) {
    var events by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        events = fetchWweEvents()
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color(0xFFFF2A85), modifier = Modifier.size(48.dp))
                Text("Loading WWE events...", color = Color.White.copy(0.6f))
            }
        }
    } else if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FitnessCenter, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(64.dp))
                Text("No events available", color = Color.White.copy(0.4f))
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(events.size) { idx ->
                val event = events[idx]
                val title = event["title"]?.jsonPrimitive?.contentOrNull ?: "WWE Event"
                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: "RAW"
                val desc = event["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val date = event["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val cover = event["coverUrl"]?.jsonPrimitive?.contentOrNull ?: ""

                var isFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val streamUrl = fetchWweStream(idx.toString(), title, type)
                            if (streamUrl != null) {
                                withContext(Dispatchers.Main) {
                                    onPlayStream(streamUrl, title)
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFocused) Color(0xFFFF2A85).copy(0.15f) else Color(0xFF0C0C14),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFFFF2A85)) else BorderStroke(1.dp, Color.White.copy(0.05f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Card with gradient
                        Box(
                            modifier = Modifier
                                .size(80.dp, 100.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFFF2A85).copy(0.5f), Color(0xFF8B5CF6).copy(0.3f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.FitnessCenter, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Surface(color = Color(0xFFFF2A85).copy(0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text(type, color = Color(0xFFFF2A85), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                            if (date.isNotBlank()) {
                                Text(date, color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelSmall)
                            }
                            if (desc.isNotBlank()) {
                                Text(desc, color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        if (isFocused) {
                            Icon(Icons.Default.PlayCircle, null, tint = Color(0xFFFF2A85), modifier = Modifier.size(48.dp))
                        } else {
                            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(36.dp))
                        }
                    }
                }
            }
        }
    }
}
