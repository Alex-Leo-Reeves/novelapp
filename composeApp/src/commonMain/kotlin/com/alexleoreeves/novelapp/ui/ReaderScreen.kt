package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.audio.KokoroNarrationController
import com.alexleoreeves.novelapp.audio.KokoroVoiceSetupPhase
import com.alexleoreeves.novelapp.audio.VoiceMode
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.sensor.SleepDetector
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    chapterUrl: String,
    novelTitle: String,
    chapterTitle: String,
    sourceName: String,
    currentTheme: AppTheme,
    ttsController: KokoroNarrationController,
    onThemeChange: (AppTheme) -> Unit,
    initialParagraphIndex: Int = 0,
    onProgress: (Int) -> Unit = {},
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val repository = remember {
        NovelSearchRepository(
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }
    val sleepDetector = remember { SleepDetector() }

    var chapterText by remember { mutableStateOf("Loading chapter…") }
    var isLoading by remember { mutableStateOf(true) }
    var showOverlays by remember { mutableStateOf(true) }
    var showThemePicker by remember { mutableStateOf(false) }
    var selectedTextColor by remember { mutableStateOf(currentTheme.textColor()) }
    var fontSize by remember { mutableStateOf(18f) }
    var showAmbientScreen by remember { mutableStateOf(false) }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    val isPlaying = ttsController.isPlaying.collectAsState()
    val ttsChunkIndex = ttsController.currentChunkIndex.collectAsState()
    val ttsChunkBoundaries = ttsController.chunkBoundaries.collectAsState()
    val ttsParagraphIndex = ttsController.currentParagraphIndex.collectAsState()
    val ttsWordIndex = ttsController.currentWordIndex.collectAsState()
    val playbackProgress = ttsController.playbackProgress.collectAsState()
    val isBuffering = ttsController.isBuffering.collectAsState()
    val voiceSetupStatus = ttsController.voiceSetupStatus.collectAsState()
    val narrationSettings = ttsController.settings.collectAsState()
    val ttsError = ttsController.lastError.collectAsState()
    val lazyListState = rememberLazyListState()
    var isSeekingNarration by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableStateOf(0f) }
    val paragraphs by remember(chapterText, isLoading) {
        derivedStateOf {
            if (isLoading || chapterText.startsWith("Loading")) {
                emptyList()
            } else {
                chapterText.toReaderBlocks().ifEmpty { listOf(chapterText) }
            }
        }
    }

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

    LaunchedEffect(playbackProgress.value, isSeekingNarration) {
        if (!isSeekingNarration) {
            seekProgress = playbackProgress.value
        }
    }

    // Cleanup on leave
    val keepNarrationInBackground by rememberUpdatedState(narrationSettings.value.backgroundPlaybackEnabled)
    DisposableEffect(chapterUrl) {
        onDispose {
            sleepDetector.stopMonitoring()
            if (!keepNarrationInBackground) {
                ttsController.stop()
            }
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

    LaunchedEffect(isLoading, initialParagraphIndex, paragraphs.size) {
        if (!isLoading && paragraphs.isNotEmpty() && initialParagraphIndex > 0) {
            lazyListState.scrollToItem((initialParagraphIndex + 1).coerceAtMost(paragraphs.size))
        }
    }

    LaunchedEffect(lazyListState.firstVisibleItemIndex, isLoading) {
        if (!isLoading) {
            onProgress((lazyListState.firstVisibleItemIndex - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(isPlaying.value, autoScrollEnabled, ttsParagraphIndex.value, ttsWordIndex.value, paragraphs.size) {
        if (!isPlaying.value || !autoScrollEnabled || paragraphs.isEmpty()) return@LaunchedEffect
        val paragraphIndex = ttsParagraphIndex.value.takeIf { it >= 0 } ?: return@LaunchedEffect
        if (ttsWordIndex.value > 0 && ttsWordIndex.value % 8 != 0) return@LaunchedEffect
        val listIndex = (paragraphIndex + 2).coerceIn(0, paragraphs.size + 1)
        lazyListState.animateScrollToItem(listIndex)
        onProgress(paragraphIndex)
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
                    modifier = Modifier.fillMaxHeight().widthIn(max = 800.dp).align(Alignment.TopCenter),
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
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                    itemsIndexed(paragraphs) { index, paragraph ->
                        val highlightedPara = ttsParagraphIndex.value
                        val isHighlighted = isPlaying.value && index == highlightedPara

                        val highlightedWordIndex: Int = if (isHighlighted) ttsWordIndex.value else -1

                        val highlightColor by animateColorAsState(
                            targetValue = if (isHighlighted)
                                currentTheme.accentColor().copy(alpha = 0.10f)
                            else Color.Transparent,
                            animationSpec = tween(durationMillis = 300),
                            label = "paragraphHighlight"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(highlightColor)
                                .padding(horizontal = if (isHighlighted) 8.dp else 0.dp)
                        ) {
                            if (isHighlighted && highlightedWordIndex >= 0) {
                                // Word-by-word highlight using AnnotatedString
                                val words = paragraph.split(Regex("(?<=\\s)|(?=\\s)"))
                                val annotated = buildAnnotatedString(words, highlightedWordIndex, selectedTextColor, currentTheme.accentColor())
                                Text(
                                    text = annotated,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Serif,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.6f).sp,
                                        color = selectedTextColor
                                    ),
                                    modifier = Modifier.padding(bottom = 18.dp)
                                )
                            } else {
                                Text(
                                    text = paragraph,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Serif,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.6f).sp,
                                        color = selectedTextColor
                                    ),
                                    modifier = Modifier.padding(bottom = 18.dp)
                                )
                            }
                        }
                    }

                    if (!isLoading && paragraphs.isEmpty()) {
                        item {
                            Text(
                                text = "Chapter content unavailable.",
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
                            IconButton(
                                onClick = {
                                    if (!narrationSettings.value.backgroundPlaybackEnabled) {
                                        ttsController.stop()
                                    }
                                    onBack()
                                }
                            ) {
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

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Listening position",
                                        color = currentTheme.subTextColor(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        "${(seekProgress * 100).roundToInt()}%",
                                        color = currentTheme.subTextColor(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Slider(
                                    value = seekProgress.coerceIn(0f, 1f),
                                    onValueChange = { value ->
                                        isSeekingNarration = true
                                        seekProgress = value
                                    },
                                    onValueChangeFinished = {
                                        ttsController.seekToProgress(seekProgress)
                                        isSeekingNarration = false
                                    },
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = currentTheme.accentColor(),
                                        activeTrackColor = currentTheme.accentColor(),
                                        inactiveTrackColor = currentTheme.subTextColor().copy(alpha = 0.25f)
                                    )
                                )
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
                                            ttsController.updateSettings {
                                                it.copy(backgroundTitle = novelTitle, backgroundSubtitle = chapterTitle)
                                            }
                                            val hasPausedChunks = ttsChunkBoundaries.value.isNotEmpty() &&
                                                ttsChunkIndex.value < ttsChunkBoundaries.value.lastIndex
                                            if (hasPausedChunks) {
                                                ttsController.resume()
                                            } else if (!isLoading && chapterText.isNotBlank()) {
                                                ttsController.playText(chapterText)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (isBuffering.value) Icons.Default.GraphicEq
                                        else if (isPlaying.value) Icons.Default.PauseCircle
                                        else Icons.Default.PlayCircle,
                                        contentDescription = "Play/Pause narration",
                                        tint = currentTheme.accentColor(),
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            val setupStatus = voiceSetupStatus.value
                            if (isBuffering.value || setupStatus.shouldShow) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val percent = setupStatus.progressFraction
                                    Text(
                                        text = buildString {
                                            append(setupStatus.userMessage.ifBlank { "Preparing voice." })
                                            if (percent != null) {
                                                append(" ")
                                                append((percent * 100f).roundToInt())
                                                append("%")
                                            }
                                        },
                                        color = currentTheme.subTextColor(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth(0.72f)
                                            .height(3.dp)
                                            .padding(top = 6.dp),
                                        color = currentTheme.accentColor(),
                                        trackColor = currentTheme.cardColor().copy(alpha = 0.5f)
                                    )
                                    if (
                                        setupStatus.phase == KokoroVoiceSetupPhase.Error ||
                                        setupStatus.phase == KokoroVoiceSetupPhase.Fallback
                                    ) {
                                        TextButton(
                                            onClick = {
                                                if (!isLoading && chapterText.isNotBlank()) {
                                                    ttsController.playText(chapterText)
                                                }
                                            }
                                        ) {
                                            Text("Retry voice setup", color = currentTheme.accentColor())
                                        }
                                    }
                                }
                            }

                            ttsError.value?.takeIf { it.isNotBlank() }?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SwipeUp,
                                    contentDescription = null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Auto-scroll",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = autoScrollEnabled,
                                    onCheckedChange = { autoScrollEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = currentTheme.accentColor(),
                                        checkedTrackColor = currentTheme.accentColor().copy(alpha = 0.45f)
                                    )
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Background play",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = narrationSettings.value.backgroundPlaybackEnabled,
                                    onCheckedChange = { enabled ->
                                        ttsController.updateSettings {
                                            it.copy(
                                                backgroundPlaybackEnabled = enabled,
                                                backgroundTitle = novelTitle,
                                                backgroundSubtitle = chapterTitle
                                            )
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = currentTheme.accentColor(),
                                        checkedTrackColor = currentTheme.accentColor().copy(alpha = 0.45f)
                                    )
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Dynamic voices",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = narrationSettings.value.voiceMode == VoiceMode.Dynamic,
                                    onCheckedChange = { enabled ->
                                        ttsController.updateSettings {
                                            it.copy(voiceMode = if (enabled) VoiceMode.Dynamic else VoiceMode.NarratorOnly)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = currentTheme.accentColor(),
                                        checkedTrackColor = currentTheme.accentColor().copy(alpha = 0.45f)
                                    )
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.VolumeUp,
                                    null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(16.dp)
                                )
                                Slider(
                                    value = narrationSettings.value.narratorVolume,
                                    onValueChange = { value ->
                                        ttsController.updateSettings {
                                            it.copy(narratorVolume = value.coerceIn(0.25f, 1f))
                                        }
                                    },
                                    valueRange = 0.25f..1f,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = currentTheme.accentColor(),
                                        activeTrackColor = currentTheme.accentColor()
                                    )
                                )
                                Text(
                                    "${(narrationSettings.value.narratorVolume * 100).roundToInt()}%",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(42.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Atmosphere",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = narrationSettings.value.ambienceEnabled,
                                    onCheckedChange = { enabled ->
                                        ttsController.updateSettings { it.copy(ambienceEnabled = enabled) }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = currentTheme.accentColor(),
                                        checkedTrackColor = currentTheme.accentColor().copy(alpha = 0.45f)
                                    )
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SurroundSound,
                                    null,
                                    tint = currentTheme.subTextColor(),
                                    modifier = Modifier.size(16.dp)
                                )
                                Slider(
                                    value = narrationSettings.value.ambienceVolume,
                                    onValueChange = { value ->
                                        ttsController.updateSettings {
                                            it.copy(ambienceVolume = value.coerceIn(0f, 0.7f))
                                        }
                                    },
                                    valueRange = 0f..0.7f,
                                    enabled = narrationSettings.value.ambienceEnabled,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = currentTheme.accentColor(),
                                        activeTrackColor = currentTheme.accentColor()
                                    )
                                )
                                Text(
                                    "${(narrationSettings.value.ambienceVolume * 100).roundToInt()}%",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(42.dp)
                                )
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

private fun String.toReaderBlocks(): List<String> =
    split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { paragraph ->
            if (paragraph.length <= 520) {
                listOf(paragraph)
            } else {
                paragraph.splitReaderSentenceBlocks(maxChars = 420)
            }
        }

private fun String.splitReaderSentenceBlocks(maxChars: Int): List<String> {
    val sentences = split(Regex("""(?<=[.!?。！？…])\s+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (sentences.isEmpty()) return chunked(maxChars)

    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    for (sentence in sentences) {
        if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
            blocks.add(current.toString().trim())
            current.clear()
        }
        if (sentence.length > maxChars) {
            if (current.isNotEmpty()) {
                blocks.add(current.toString().trim())
                current.clear()
            }
            blocks.addAll(sentence.chunked(maxChars))
        } else {
            current.append(sentence).append(' ')
        }
    }
    if (current.isNotEmpty()) blocks.add(current.toString().trim())
    return blocks
}

private fun buildAnnotatedString(
    words: List<String>,
    highlightedIndex: Int,
    defaultColor: Color,
    accentColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var wordCount = 0
        for (token in words) {
            val isWord = token.trim().isNotEmpty()
            if (isWord) {
                if (wordCount == highlightedIndex) {
                    withStyle(
                        SpanStyle(
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            background = accentColor.copy(alpha = 0.25f)
                        )
                    ) {
                        append(token)
                    }
                } else {
                    withStyle(SpanStyle(color = defaultColor)) {
                        append(token)
                    }
                }
                wordCount++
            } else {
                append(token)
            }
        }
    }
}
