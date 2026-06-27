package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.audio.GeminiTtsController
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.sensor.SleepDetector
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    chapterUrl: String,
    novelTitle: String,
    chapterTitle: String,
    sourceName: String,
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val repository = remember {
        NovelSearchRepository(
            geminiApiKey = BuildKonfig.GEMINI_API_KEY,
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }
    val ttsController = remember {
        GeminiTtsController(BuildKonfig.GEMINI_API_KEY)
    }
    val sleepDetector = remember { SleepDetector() }

    var chapterText by remember { mutableStateOf("Loading chapter…") }
    var isLoading by remember { mutableStateOf(true) }
    var showOverlays by remember { mutableStateOf(true) }
    var showThemePicker by remember { mutableStateOf(false) }
    var selectedTextColor by remember { mutableStateOf(currentTheme.textColor()) }
    var fontSize by remember { mutableStateOf(18f) }
    var showAmbientScreen by remember { mutableStateOf(false) }
    val isPlaying = ttsController.isPlaying.collectAsState()
    val lazyListState = rememberLazyListState()

    // — Start sleep detection when playing, stop when paused —
    LaunchedEffect(isPlaying.value) {
        if (isPlaying.value) {
            sleepDetector.startMonitoring {
                // User has fallen asleep — pause narration silently
                ttsController.pause()
                showAmbientScreen = true
            }
        } else {
            sleepDetector.stopMonitoring()
        }
    }

    // Cleanup on leave
    DisposableEffect(Unit) {
        onDispose {
            sleepDetector.stopMonitoring()
        }
    }

    // Load chapter text
    LaunchedEffect(chapterUrl) {
        isLoading = true
        chapterText = if (sourceName == "local" || chapterUrl.endsWith(".txt")) {
            loadDownloadedText(chapterUrl)
        } else {
            repository.fetchChapterText(chapterUrl, sourceName)
        }
        isLoading = false
    }

    // Update text color on theme change
    LaunchedEffect(currentTheme) {
        selectedTextColor = currentTheme.textColor()
    }

    // 5-minute inactivity → ambient mode
    val isUserScrolling = lazyListState.isScrollInProgress
    LaunchedEffect(isPlaying.value, isUserScrolling) {
        if (isPlaying.value && !isUserScrolling) {
            delay(300_000L)
            showAmbientScreen = true
        } else {
            showAmbientScreen = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(currentTheme.backgroundColor())) {
        if (showAmbientScreen) {
            val sleepTimerMinutes = ttsController.sleepTimerMinutes.collectAsState()
            AmbientPlayerScreen(
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                isPlaying = isPlaying.value,
                sleepTimerMinutes = sleepTimerMinutes.value,
                onSleepTimerSelected = { mins ->
                    if (mins == 0) ttsController.cancelSleepTimer()
                    else ttsController.startSleepTimer(mins) { showAmbientScreen = false }
                },
                onPlay = { ttsController.resume() },
                onPause = { ttsController.pause() },
                onSkipBack = { ttsController.skipBack() },
                onSkipForward = { ttsController.skipForward() },
                onWakeUp = { showAmbientScreen = false }
            )
        } else {
            // ── Reading Content ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { showOverlays = !showOverlays }
                    }
            ) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxHeight().widthIn(max = 800dp).align(Alignment.TopCenter),
                    contentPadding = PaddingValues(
                        top = 72.dp, bottom = 100.dp,
                        start = 22.dp, end = 22.dp
                    )
                ) {
                    item {
                        Text(
                            chapterTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = selectedTextColor,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }
                    item {
                        if (isLoading) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = currentTheme.accentColor())
                            }
                        } else {
                            Text(
                                text = chapterText,
                                style = TextStyle(
                                    fontFamily = FontFamily.Serif,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.6f).sp,
                                    color = selectedTextColor
                                )
                            )
                        }
                    }
                }

                // ── Animated Top Bar ─────────────────────────────────────────
                AnimatedVisibility(
                    visible = showOverlays,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Surface(
                        color = currentTheme.surfaceColor().copy(alpha = 0.95f),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, "Back",
                                    tint = currentTheme.textColor())
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    novelTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = currentTheme.subTextColor(),
                                    maxLines = 1
                                )
                                Text(
                                    chapterTitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = currentTheme.textColor(),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // ── Animated Bottom Bar ──────────────────────────────────────
                AnimatedVisibility(
                    visible = showOverlays,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        color = currentTheme.surfaceColor().copy(alpha = 0.97f),
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(12.dp)
                        ) {
                            // Font size slider
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.TextFields, null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(16.dp))
                                Slider(
                                    value = fontSize,
                                    onValueChange = { fontSize = it },
                                    valueRange = 14f..26f,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = currentTheme.accentColor(),
                                        activeTrackColor = currentTheme.accentColor()
                                    )
                                )
                                Icon(Icons.Default.TextFields, null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(22.dp))
                            }

                            // Theme switcher + TTS speaker
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Theme Picker
                                IconButton(onClick = { showThemePicker = !showThemePicker }) {
                                    Icon(Icons.Default.Palette, "Theme",
                                        tint = currentTheme.accentColor())
                                }

                                // Text colors
                                listOf(
                                    Color(0xFFE8E8E8) to "Light",
                                    Color(0xFF2C2C2C) to "Dark",
                                    Color(0xFFFFD700) to "Gold",
                                    Color(0xFF98E4B0) to "Mint"
                                ).forEach { (color, desc) ->
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (selectedTextColor == color) 3.dp else 0.dp,
                                                color = currentTheme.accentColor(),
                                                shape = CircleShape
                                            )
                                            .clickable { selectedTextColor = color }
                                    )
                                }

                                // TTS Speaker button
                                IconButton(
                                    onClick = {
                                        if (isPlaying.value) {
                                            ttsController.pause()
                                        } else {
                                            scope.launch {
                                                ttsController.readText(chapterText)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (isPlaying.value) Icons.Default.PauseCircle
                                        else Icons.Default.PlayCircle,
                                        contentDescription = "Play/Pause narration",
                                        tint = currentTheme.accentColor(),
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            // Theme picker panel
                            if (showThemePicker) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    AppTheme.values().forEach { theme ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.clickable {
                                                onThemeChange(theme)
                                                showThemePicker = false
                                            }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(theme.backgroundColor())
                                                    .border(
                                                        width = if (currentTheme == theme) 3.dp else 1.dp,
                                                        color = if (currentTheme == theme)
                                                            theme.accentColor()
                                                        else currentTheme.subTextColor(),
                                                        shape = CircleShape
                                                    )
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                theme.displayName.split(" ").first(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = currentTheme.subTextColor()
                                            )
                                        }
                                    }
                                }
                            }

                            // Sleep Timer controls row
                            val sleepTimerMinutes = ttsController.sleepTimerMinutes.collectAsState()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Sleep Timer: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = currentTheme.subTextColor()
                                )
                                listOf(0, 15, 30, 45).forEach { mins ->
                                    val isSelected = sleepTimerMinutes.value == mins
                                    Text(
                                        text = if (mins == 0) "Off" else "${mins}m",
                                        color = if (isSelected) currentTheme.accentColor() else currentTheme.subTextColor(),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp)
                                            .clickable {
                                                if (mins == 0) ttsController.cancelSleepTimer()
                                                else ttsController.startSleepTimer(mins) {}
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
