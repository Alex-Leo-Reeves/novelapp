package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
//  Sports Parent Screen — top tabs:  Football  |  WWE
// ─────────────────────────────────────────────────────────────────────────────
enum class SportsSubTab(val label: String) {
    FOOTBALL("Football"),
    WWE("WWE")
}

val sportsAccent = Color(0xFF00C853)

@Composable
fun SportsHomeScreen(
    currentTheme: AppTheme,
    onFootballMatchSelected: (FootballMatch) -> Unit,
    onWweEventSelected: (WweEvent) -> Unit
) {
    var activeSubTab by remember { mutableStateOf(SportsSubTab.FOOTBALL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Sports Header ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF003300), currentTheme.backgroundColor())
                    )
                )
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sports",
                        style = MaterialTheme.typography.headlineLarge,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Football & WWE streaming",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.subTextColor()
                    )
                }
                Icon(
                    Icons.Default.SportsSoccer,
                    null,
                    tint = sportsAccent,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // ── Sub-tabs: Football | WWE ─────────────────────────────────────
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(SportsSubTab.values()) { tab ->
                val selected = activeSubTab == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (selected) {
                                when (tab) {
                                    SportsSubTab.FOOTBALL -> sportsAccent
                                    SportsSubTab.WWE -> wweAccent
                                }
                            } else currentTheme.cardColor()
                        )
                        .clickable { activeSubTab = tab }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            when (tab) {
                                SportsSubTab.FOOTBALL -> Icons.Default.SportsSoccer
                                SportsSubTab.WWE -> Icons.Default.Star
                            },
                            null,
                            tint = if (selected) Color.White else currentTheme.subTextColor(),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            tab.label,
                            color = if (selected) Color.White else currentTheme.textColor(),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // ── Content ──────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when (activeSubTab) {
                SportsSubTab.FOOTBALL -> FootballHomeScreen(
                    currentTheme = currentTheme,
                    onMatchSelected = onFootballMatchSelected
                )
                SportsSubTab.WWE -> WweScreen(
                    currentTheme = currentTheme,
                    onEventSelected = onWweEventSelected
                )
            }
        }
    }
}
