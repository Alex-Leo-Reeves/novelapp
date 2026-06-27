package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
//  Splash / Opening Screen
//  Shown on every cold launch for ~3 seconds, then calls [onFinished].
//
//  Credits:
//    Developed by Mike A. (Alex Leo Reeves)
//    Contact: masteralexleoreevesd1@gmail.com
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SplashScreen(onFinished: () -> Unit) {

    // Trigger auto-advance after 3 seconds
    LaunchedEffect(Unit) {
        delay(3200L)
        onFinished()
    }

    // Animation states
    val infinite = rememberInfiniteTransition(label = "pulse")
    val logoScale by infinite.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "scale"
    )

    var logoVisible by remember { mutableStateOf(false) }
    var titleVisible by remember { mutableStateOf(false) }
    var subtitleVisible by remember { mutableStateOf(false) }
    var creditVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(200); logoVisible = true
        delay(500); titleVisible = true
        delay(400); subtitleVisible = true
        delay(600); creditVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A0533),
                        Color(0xFF0D0D1A),
                        Color(0xFF000000)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // ── Logo Icon ─────────────────────────────────────────────────
            AnimatedVisibility(
                visible = logoVisible,
                enter = fadeIn(tween(600)) + scaleIn(tween(600))
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer { scaleX = logoScale; scaleY = logoScale },
                    contentAlignment = Alignment.Center
                ) {
                    // Glow ring
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color(0xFF7C3AED).copy(0.5f),
                                        Color.Transparent
                                    )
                                ),
                        shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = Color(0xFFE040FB),
                        modifier = Modifier.size(72.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── App Title ────────────────────────────────────────────────
            AnimatedVisibility(
                visible = titleVisible,
                enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { it / 2 }
            ) {
                Text(
                    text = "Watch Anime",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            AnimatedVisibility(
                visible = titleVisible,
                enter = fadeIn(tween(800, 100)) + slideInVertically(tween(800, 100)) { it / 2 }
            ) {
                Text(
                    text = "Read Novels · Read Manga",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE040FB),
                    textAlign = TextAlign.Center
                )
            }

            AnimatedVisibility(
                visible = subtitleVisible,
                enter = fadeIn(tween(600, 200))
            ) {
                Text(
                    text = "— All in One —",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(60.dp))

            // ── Loading Indicator ─────────────────────────────────────────
            AnimatedVisibility(visible = subtitleVisible, enter = fadeIn(tween(400))) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(2.dp),
                    color = Color(0xFF7C3AED),
                    trackColor = Color.White.copy(0.1f)
                )
            }
        }

        // ── Developer Credit — pinned at bottom ───────────────────────────
        AnimatedVisibility(
            visible = creditVisible,
            enter = fadeIn(tween(700)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp)
            ) {
                Text(
                    text = "Developed by Mike A. (Alex Leo Reeves)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(0.5f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "masteralexleoreevesd1@gmail.com",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF7C3AED).copy(0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
