package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val fullTitle = "NovaRead.TV"
    var typedTitle by remember { mutableStateOf("") }
    var logoVisible by remember { mutableStateOf(false) }
    var subtitleVisible by remember { mutableStateOf(false) }
    var creditVisible by remember { mutableStateOf(false) }

    // Typewriter effect + sound timing
    LaunchedEffect(Unit) {
        delay(150)
        logoVisible = true
        delay(300)
        for (i in 1..fullTitle.length) {
            typedTitle = fullTitle.take(i)
            delay(90)
        }
        delay(300)
        subtitleVisible = true
        delay(400)
        creditVisible = true
        delay(1200)
        onFinished()
    }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val logoScale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1E0738),
                        Color(0xFF0C0A1A),
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
            // Logo Icon
            AnimatedVisibility(
                visible = logoVisible,
                enter = fadeIn(tween(500)) + scaleIn(tween(500))
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer { scaleX = logoScale; scaleY = logoScale },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color(0xFF22D3EE).copy(0.45f),
                                        Color(0xFFE84D8A).copy(0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = Color(0xFF22D3EE),
                        modifier = Modifier.size(72.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // Typed Title: NovaRead.TV
            Text(
                text = typedTitle,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
                letterSpacing = (-1).sp
            )

            AnimatedVisibility(
                visible = subtitleVisible,
                enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 2 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Watch Anime · Read Novels · Read Manga",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE84D8A),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "— Ultimate Streaming Hub —",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.55f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            AnimatedVisibility(visible = subtitleVisible, enter = fadeIn(tween(400))) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(3.dp),
                    color = Color(0xFF22D3EE),
                    trackColor = Color.White.copy(0.12f)
                )
            }
        }

        // Developer Credit
        AnimatedVisibility(
            visible = creditVisible,
            enter = fadeIn(tween(700)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Developed by Mike A. (Alex Leo Reeves)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(0.5f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "masteralexleoreevesd1@gmail.com",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF22D3EE).copy(0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
