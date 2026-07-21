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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
//  Sport sub-tabs
// ─────────────────────────────────────────────────────────────────────────────
enum class SportTab(val label: String, val icon: ImageVector) {
    FOOTBALL("Football", Icons.Default.SportsSoccer),
    WWE("WWE", Icons.Default.FitnessCenter)
}

@Composable
fun SportsHomeScreen(
    currentTheme: AppTheme,
    onFootballMatchSelected: (FootballMatch) -> Unit,
    onWweEventSelected: (WweEvent) -> Unit
) {
    var activeSport by remember { mutableStateOf(SportTab.FOOTBALL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1B5E20),
                            currentTheme.backgroundColor()
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sports",
                        style = MaterialTheme.typography.headlineLarge,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Football, WWE, and more",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.subTextColor()
                    )
                }
                Icon(
                    Icons.Default.SportsEsports,
                    null,
                    tint = footballAccent,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // ── Sport tab switcher ────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(SportTab.values().toList()) { sport: SportTab ->
                val isSelected = activeSport == sport

                Surface(
                    onClick = { activeSport = sport },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        when (sport) {
                            SportTab.FOOTBALL -> footballAccent
                            SportTab.WWE -> wweAccent
                        }
                    } else {
                        currentTheme.cardColor()
                    },
                    border = if (!isSelected) BorderStroke(
                        1.dp,
                        currentTheme.subTextColor().copy(alpha = 0.3f)
                    ) else null,
                    modifier = Modifier
                        .width(164.dp)
                        .height(56.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            sport.icon,
                            null,
                            tint = if (isSelected) Color.White else currentTheme.subTextColor(),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                sport.label,
                                color = if (isSelected) Color.White else currentTheme.textColor(),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (sport == SportTab.FOOTBALL) "Live scores & streams" else "RAW, SD, NXT, PPV",
                                color = if (isSelected) Color.White.copy(0.7f) else currentTheme.subTextColor(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Content ───────────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeSport) {
                SportTab.FOOTBALL -> FootballHomeScreen(
                    currentTheme = currentTheme,
                    onMatchSelected = onFootballMatchSelected
                )
                SportTab.WWE -> WweScreen(
                    currentTheme = currentTheme,
                    onEventSelected = onWweEventSelected
                )
            }
        }
    }
}
