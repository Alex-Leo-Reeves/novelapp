package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.audio.TvTtsEngine
import com.alexleoreeves.novelapp.tv.audio.TtsSettings
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun TvNovelReaderScreen(
    text: String,
    title: String,
    ttsEngine: TvTtsEngine,
    onBack: () -> Unit
) {
    val ttsSettings by ttsEngine.settings.collectAsState()
    var fontSize by remember { mutableStateOf(20) }
    var lineSpacing by remember { mutableStateOf(8) }
    var showSettings by remember { mutableStateOf(false) }
    var currentChapter by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // Initialize TTS
    LaunchedEffect(Unit) {
        ttsEngine.init()
    }

    LaunchedEffect(text) {
        currentChapter = text
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionUp -> { CoroutineScope(Dispatchers.Main).launch { scrollState.animateScrollTo((scrollState.value - 200).coerceAtLeast(0)) }; true }
                        Key.DirectionDown -> { CoroutineScope(Dispatchers.Main).launch { scrollState.animateScrollTo((scrollState.value + 200).coerceAtMost(scrollState.maxValue)) }; true }
                        Key.MediaPlayPause, Key.DirectionCenter -> {
                            if (ttsSettings.isPlaying) ttsEngine.stop()
                            else ttsEngine.speak(currentChapter)
                            true
                        }
                        Key.Back -> { ttsEngine.stop(); onBack(); true }
                        else -> false
                    }
                } else false
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
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
                    onClick = { ttsEngine.stop(); onBack() },
                    shape = RoundedCornerShape(10.dp),
                    color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                    border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.onFocusChanged { backFocused = it }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Back", color = Color.White)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // TTS controls row
                TtsControlStrip(ttsEngine = ttsEngine, ttsSettings = ttsSettings)

                // Settings toggle
                var settingsFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { showSettings = !showSettings },
                    shape = RoundedCornerShape(10.dp),
                    color = if (settingsFocused) Color(0xFF1C1C2E) else Color(0xFF14141E),
                    border = if (settingsFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.onFocusChanged { settingsFocused = it }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Settings, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(20.dp))
                        Text("Settings", color = Color.White.copy(0.6f))
                    }
                }
            }

            // Settings panel
            if (showSettings) {
                TtsSettingsPanel(
                    ttsEngine = ttsEngine,
                    ttsSettings = ttsSettings,
                    fontSize = fontSize,
                    onFontSizeChange = { fontSize = it },
                    lineSpacing = lineSpacing,
                    onLineSpacingChange = { lineSpacing = it }
                )
            }

            // Reader content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 48.dp, vertical = 24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Chapter title
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Chapter text
                    Text(
                        currentChapter.ifBlank { "Loading chapter content..." },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + lineSpacing).sp
                        ),
                        color = Color.White.copy(0.88f)
                    )

                    Spacer(Modifier.height(80.dp))
                }
            }

            // Now playing bar when TTS active
            if (ttsSettings.isPlaying) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0A12)),
                    color = Color(0xFF0A0A12)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(
                            progress = { ttsSettings.currentProgress },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Color(0xFF00BFFF),
                            trackColor = Color.White.copy(0.1f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "TTS Playing \u2014 Speed: ${"%.1f".format(ttsSettings.speed)}x | Pitch: ${"%.1f".format(ttsSettings.pitch)}x",
                                color = Color.White.copy(0.6f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // Reading progress indicator
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
        ) {
            val textProgress = if (currentChapter.length > 0) {
                scrollState.value.toFloat() / scrollState.maxValue.coerceAtLeast(1).toFloat()
            } else 0f
            Surface(
                color = Color(0xFF14141E),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "${(textProgress * 100).toInt()}%",
                    color = Color.White.copy(0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TtsControlStrip(
    ttsEngine: TvTtsEngine,
    ttsSettings: TtsSettings
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/Pause
        var playFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = {
                if (ttsSettings.isPlaying) ttsEngine.pause()
                else ttsEngine.resume()
            },
            shape = CircleShape,
            color = if (playFocused) Purple500.copy(0.5f) else Color(0xFF1C1C2E),
            border = if (playFocused) BorderStroke(2.dp, Purple500) else null,
            modifier = Modifier
                .size(44.dp)
                .onFocusChanged { playFocused = it }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (ttsSettings.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Stop
        var stopFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = { ttsEngine.stop() },
            shape = CircleShape,
            color = if (stopFocused) Color(0xFF00BFFF).copy(0.5f) else Color(0xFF1C1C2E),
            border = if (stopFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
            modifier = Modifier
                .size(44.dp)
                .onFocusChanged { stopFocused = it }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        // Re-read
        var rereadFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = {
                ttsEngine.stop()
                ttsEngine.resume()
            },
            shape = CircleShape,
            color = if (rereadFocused) Purple500.copy(0.5f) else Color(0xFF1C1C2E),
            border = if (rereadFocused) BorderStroke(2.dp, Purple500) else null,
            modifier = Modifier
                .size(44.dp)
                .onFocusChanged { rereadFocused = it }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Replay, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun TtsSettingsPanel(
    ttsEngine: TvTtsEngine,
    ttsSettings: TtsSettings,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    lineSpacing: Int,
    onLineSpacingChange: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0C0C14),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed control
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Speed", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var decFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { ttsEngine.updateSpeed(ttsSettings.speed - 0.1f) },
                        shape = CircleShape,
                        color = if (decFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                        border = if (decFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(36.dp)
                            .onFocusChanged { decFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }

                    Surface(
                        color = Color(0xFF1A1A2A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${"%.1f".format(ttsSettings.speed)}x",
                            color = Color(0xFF00BFFF),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    var incFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { ttsEngine.updateSpeed(ttsSettings.speed + 0.1f) },
                        shape = CircleShape,
                        color = if (incFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                        border = if (incFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(36.dp)
                            .onFocusChanged { incFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }

            // Pitch control
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Pitch", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var decFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { ttsEngine.updatePitch(ttsSettings.pitch - 0.1f) },
                        shape = CircleShape,
                        color = if (decFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                        border = if (decFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(36.dp)
                            .onFocusChanged { decFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }

                    Surface(
                        color = Color(0xFF1A1A2A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${"%.1f".format(ttsSettings.pitch)}x",
                            color = Color(0xFF00BFFF),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    var incFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { ttsEngine.updatePitch(ttsSettings.pitch + 0.1f) },
                        shape = CircleShape,
                        color = if (incFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                        border = if (incFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(36.dp)
                            .onFocusChanged { incFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }

            // Font size (simplified to Int)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Font Size", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var decFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { onFontSizeChange((fontSize - 2).coerceAtLeast(14)) },
                        shape = CircleShape,
                        color = if (decFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                        border = if (decFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(36.dp)
                            .onFocusChanged { decFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.TextDecrease, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Surface(
                        color = Color(0xFF1A1A2A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${fontSize}sp",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    var incFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { onFontSizeChange((fontSize + 2).coerceAtMost(36)) },
                        shape = CircleShape,
                        color = if (incFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                        border = if (incFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(36.dp)
                            .onFocusChanged { incFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.TextIncrease, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
