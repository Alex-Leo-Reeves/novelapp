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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.Job
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
    val scrollScope = rememberCoroutineScope()
    val readerFocusRequester = remember { FocusRequester() }
    // Single cancellable scroll job: rapid D-pad presses used to launch
    // overlapping animateScrollTo() calls ("Scroll is already in progress"),
    // which crashed the reader. Snap-scrolling and cancelling the previous
    // job keeps every press safe.
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val isBlocked = text.contains("blocked", ignoreCase = true) ||
            text.contains("cloudflare", ignoreCase = true) ||
            text.contains("403", ignoreCase = true) ||
            text.contains("Access denied", ignoreCase = true)

    // Initialize TTS
    LaunchedEffect(Unit) {
        ttsEngine.init()
    }

    LaunchedEffect(text) {
        currentChapter = text
        scrollState.scrollTo(0)
    }

    LaunchedEffect(Unit) {
        runCatching { readerFocusRequester.requestFocus() }
    }

    fun scrollReaderBy(delta: Int) {
        scrollJob?.cancel()
        scrollJob = scrollScope.launch {
            val target = (scrollState.value + delta).coerceIn(0, scrollState.maxValue)
            runCatching { scrollState.scrollTo(target) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .focusRequester(readerFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionUp -> { scrollReaderBy(-220); true }
                        Key.DirectionDown -> { scrollReaderBy(220); true }
                        Key.PageUp -> { scrollReaderBy(-720); true }
                        Key.PageDown -> { scrollReaderBy(720); true }
                        Key.MediaPlayPause, Key.DirectionCenter -> {
                            if (ttsSettings.isPlaying) ttsEngine.stop()
                            else ttsEngine.speak(currentChapter)
                            true
                        }
                        Key.VolumeUp -> { ttsEngine.updateVolume(ttsSettings.volume + 0.1f); true }
                        Key.VolumeDown -> { ttsEngine.updateVolume(ttsSettings.volume - 0.1f); true }
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
                    modifier = Modifier.onFocusChanged { backFocused = it.isFocused }
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

                // Play/Pause button in top bar
                var playBtnFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        if (ttsSettings.isPlaying) ttsEngine.pause()
                        else if (ttsSettings.currentText.isNotBlank()) ttsEngine.resume()
                        else ttsEngine.speak(currentChapter)
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        ttsSettings.isPlaying -> Color(0xFF00BFFF).copy(0.3f)
                        playBtnFocused -> Color(0xFF1C1C2E)
                        else -> Color(0xFF14141E)
                    },
                    border = if (playBtnFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                    modifier = Modifier.onFocusChanged { playBtnFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (ttsSettings.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = Color(0xFF00BFFF), modifier = Modifier.size(22.dp)
                        )
                        Text(
                            if (ttsSettings.isPlaying) "Pause" else "Play",
                            color = Color(0xFF00BFFF),
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                    modifier = Modifier.onFocusChanged { settingsFocused = it.isFocused }
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
            if (isBlocked) {
                // Cloudflare / blocked error card
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF14141E),
                        border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(0.4f)),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(64.dp))
                            Text(
                                "Content Temporarily Blocked",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "The source site is currently blocking requests (Cloudflare protection). This is usually temporary.",
                                color = Color.White.copy(0.6f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            var retryFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = onBack,
                                shape = RoundedCornerShape(10.dp),
                                color = if (retryFocused) Color(0xFFFF6B6B) else Color(0xFFFF6B6B).copy(0.2f),
                                border = if (retryFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFFFF6B6B)),
                                modifier = Modifier.onFocusChanged { retryFocused = it.isFocused }
                            ) {
                                Text(
                                    "Go Back & Try Again",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            } else {
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
                                "TTS Playing \u2014 Speed: ${"%.1f".format(ttsSettings.speed)}x | Vol: ${(ttsSettings.volume * 100).toInt()}%",
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
        // Stop
        var stopFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = { ttsEngine.stop() },
            shape = CircleShape,
            color = if (stopFocused) Color(0xFF00BFFF).copy(0.5f) else Color(0xFF1C1C2E),
            border = if (stopFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
            modifier = Modifier
                .size(44.dp)
                .onFocusChanged { stopFocused = it.isFocused }
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
                .onFocusChanged { rereadFocused = it.isFocused }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Replay, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        // Volume down
        var volDownFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = { ttsEngine.updateVolume(ttsSettings.volume - 0.1f) },
            shape = CircleShape,
            color = if (volDownFocused) Purple500.copy(0.4f) else Color(0xFF1C1C2E),
            border = if (volDownFocused) BorderStroke(2.dp, Purple500) else null,
            modifier = Modifier
                .size(36.dp)
                .onFocusChanged { volDownFocused = it.isFocused }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VolumeDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // Volume up
        var volUpFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = { ttsEngine.updateVolume(ttsSettings.volume + 0.1f) },
            shape = CircleShape,
            color = if (volUpFocused) Purple500.copy(0.4f) else Color(0xFF1C1C2E),
            border = if (volUpFocused) BorderStroke(2.dp, Purple500) else null,
            modifier = Modifier
                .size(36.dp)
                .onFocusChanged { volUpFocused = it.isFocused }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VolumeUp, null, tint = Color.White, modifier = Modifier.size(18.dp))
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
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showPitchDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0C0C14),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed Dropdown Button
            RemoteDropdownButton(
                label = "Speed",
                currentValue = "${"%.2f".format(ttsSettings.speed).trimEnd('0').trimEnd('.')}x",
                icon = Icons.Default.Speed,
                onClick = { showSpeedDialog = true }
            )

            // Pitch Dropdown Button
            RemoteDropdownButton(
                label = "Pitch",
                currentValue = "${"%.2f".format(ttsSettings.pitch).trimEnd('0').trimEnd('.')}x",
                icon = Icons.Default.GraphicEq,
                onClick = { showPitchDialog = true }
            )

            // Volume control
            SettingControl(
                label = "Volume",
                value = "${(ttsSettings.volume * 100).toInt()}%",
                onDecrease = { ttsEngine.updateVolume(ttsSettings.volume - 0.1f) },
                onIncrease = { ttsEngine.updateVolume(ttsSettings.volume + 0.1f) }
            )

            // Font size
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
                            .onFocusChanged { decFocused = it.isFocused }
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
                            .onFocusChanged { incFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.TextIncrease, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // Speed Selection Modal
    if (showSpeedDialog) {
        val speedOptions = listOf(
            0.5f to "0.5x (Very Slow)",
            0.75f to "0.75x (Slow)",
            1.0f to "1.0x (Normal)",
            1.25f to "1.25x (Fast)",
            1.5f to "1.5x (Faster)",
            1.75f to "1.75x (Very Fast)",
            2.0f to "2.0x (Maximum)"
        )
        RemoteTtsOptionDialog(
            title = "Speech Speed",
            options = speedOptions,
            currentValue = ttsSettings.speed,
            onDismiss = { showSpeedDialog = false },
            onSelect = { selectedSpeed ->
                ttsEngine.updateSpeed(selectedSpeed)
                showSpeedDialog = false
            }
        )
    }

    // Pitch Selection Modal
    if (showPitchDialog) {
        val pitchOptions = listOf(
            0.5f to "0.5x (Deep / Low)",
            0.75f to "0.75x (Bass)",
            1.0f to "1.0x (Normal)",
            1.25f to "1.25x (High Voice)",
            1.5f to "1.5x (Higher)",
            2.0f to "2.0x (Maximum)"
        )
        RemoteTtsOptionDialog(
            title = "Voice Pitch",
            options = pitchOptions,
            currentValue = ttsSettings.pitch,
            onDismiss = { showPitchDialog = false },
            onSelect = { selectedPitch ->
                ttsEngine.updatePitch(selectedPitch)
                showPitchDialog = false
            }
        )
    }
}

@Composable
private fun RemoteDropdownButton(
    label: String,
    currentValue: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium)
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = if (isFocused) Color(0xFF00BFFF).copy(0.25f) else Color(0xFF1A1A2A),
            border = if (isFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else BorderStroke(1.dp, Color.White.copy(0.1f)),
            modifier = Modifier
                .height(38.dp)
                .onFocusChanged { isFocused = it.isFocused }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, null, tint = if (isFocused) Color(0xFF00BFFF) else Color.White, modifier = Modifier.size(16.dp))
                Text(
                    currentValue,
                    color = if (isFocused) Color(0xFF00BFFF) else Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RemoteTtsOptionDialog(
    title: String,
    options: List<Pair<Float, String>>,
    currentValue: Float,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(options) { (value, label) ->
                    val isSelected = kotlin.math.abs(currentValue - value) < 0.05f
                    var itemFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSelect(value) },
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            itemFocused -> Color(0xFF00BFFF).copy(0.3f)
                            isSelected -> Color(0xFF17172A)
                            else -> Color(0xFF0F0F1A)
                        },
                        border = if (itemFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else if (isSelected) BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.5f)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { itemFocused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                label,
                                color = if (isSelected || itemFocused) Color(0xFF00BFFF) else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint = Color(0xFF00BFFF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF00BFFF), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF12121E)
    )
}

/** Reusable +/- control for volume and other numeric settings. */
@Composable
private fun SettingControl(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var decFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onDecrease,
                shape = CircleShape,
                color = if (decFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                border = if (decFocused) BorderStroke(2.dp, Purple500) else null,
                modifier = Modifier
                    .size(36.dp)
                    .onFocusChanged { decFocused = it.isFocused }
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
                    value,
                    color = Color(0xFF00BFFF),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            var incFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onIncrease,
                shape = CircleShape,
                color = if (incFocused) Purple500.copy(0.4f) else Color(0xFF1A1A2A),
                border = if (incFocused) BorderStroke(2.dp, Purple500) else null,
                modifier = Modifier
                    .size(36.dp)
                    .onFocusChanged { incFocused = it.isFocused }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
    }
}
