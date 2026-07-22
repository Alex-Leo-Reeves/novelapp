package com.alexleoreeves.novelapp.ui

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
import androidx.compose.ui.text.style.TextAlign
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

// Sport accent colors (kept for tab highlights)
// Note: these are prefixed with "sports" to avoid conflict with same-named
// package-visible vals in FootballHomeScreen.kt and WweScreen.kt
private val sportsFootballAccent = Color(0xFF4CAF50)
private val sportsWweAccent = Color(0xFFFF5722)

@Composable
fun SportsHomeScreen(
    currentTheme: AppTheme,
    onFootballMatchSelected: (FootballMatch) -> Unit,
    onWweEventSelected: (WweEvent) -> Unit
) {
    var activeSport by remember { mutableStateOf(SportTab.FOOTBALL) }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Glass Background ─────────────────────────────────────────────
        GlassBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassOverlayColor)
                .statusBarsPadding()
        ) {
            // ── Header ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Sports",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Football, WWE, and more",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // ── Sport tab switcher (glass pill chips) ───────────────────
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(SportTab.values().toList()) { sport: SportTab ->
                    val isSelected = activeSport == sport

                    Box(
                        modifier = Modifier
                            .width(164.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) {
                                    when (sport) {
                                        SportTab.FOOTBALL -> sportsFootballAccent.copy(alpha = 0.25f)
                                        SportTab.WWE -> sportsWweAccent.copy(alpha = 0.25f)
                                    }
                                } else {
                                    Color.White.copy(alpha = 0.06f)
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                color = if (isSelected) {
                                    when (sport) {
                                        SportTab.FOOTBALL -> sportsFootballAccent
                                        SportTab.WWE -> sportsWweAccent
                                    }
                                } else {
                                    Color.White.copy(alpha = 0.12f)
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { activeSport = sport }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                sport.icon,
                                null,
                                tint = if (isSelected) {
                                    when (sport) {
                                        SportTab.FOOTBALL -> sportsFootballAccent
                                        SportTab.WWE -> sportsWweAccent
                                    }
                                } else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    sport.label,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    if (sport == SportTab.FOOTBALL) "Live scores & streams"
                                    else "RAW, SD, NXT, PPV",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Content area ────────────────────────────────────────────
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
}
