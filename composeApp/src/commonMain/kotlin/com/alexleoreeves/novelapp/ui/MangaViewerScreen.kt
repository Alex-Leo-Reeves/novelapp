package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.audio.GeminiTtsController
import com.alexleoreeves.novelapp.audio.MangaOcrReader
import com.alexleoreeves.novelapp.audio.OcrTextPanel
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaViewerScreen(
    chapterUrl: String,
    mangaTitle: String,
    chapterTitle: String,
    sourceName: String,
    currentTheme: AppTheme,
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
    val ttsController = remember { GeminiTtsController(BuildKonfig.GEMINI_API_KEY) }
    val ocrReader = remember { MangaOcrReader() }

    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showOverlays by remember { mutableStateOf(true) }
    var scrollMode by remember { mutableStateOf(MangaScrollMode.WEBTOON) }

    // TTS and OCR Tracking
    val isTtsPlaying = ttsController.isPlaying.collectAsState()
    var isOcrActive by remember { mutableStateOf(false) }
    var ocrPanels by remember { mutableStateOf<List<OcrTextPanel>>(emptyList()) }
    var activeBubbleIndex by remember { mutableStateOf(-1) }
    var currentPageIndex by remember { mutableStateOf(0) }

    // Scroll states
    val lazyListState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // Load pages on launch
    LaunchedEffect(chapterUrl) {
        isLoading = true
        pages = if (sourceName == "local") {
            chapterUrl.split(",")
        } else {
            repository.fetchMangaPages(chapterUrl, sourceName)
        }
        isLoading = false
    }

    // Sync currentPageIndex between Pager and Vertical List
    LaunchedEffect(pagerState.currentPage) {
        if (scrollMode != MangaScrollMode.WEBTOON) {
            currentPageIndex = pagerState.currentPage
        }
    }

    // AI Panel Reader Auto-Scroll & Read loop
    LaunchedEffect(isOcrActive, currentPageIndex) {
        if (isOcrActive && pages.isNotEmpty()) {
            val pageUrl = pages[currentPageIndex]
            ocrPanels = ocrReader.recognizeTextFromUrl(pageUrl)
            if (ocrPanels.isNotEmpty()) {
                activeBubbleIndex = 0
            } else {
                // No text bubbles on page, proceed to next page automatically
                delay(2000)
                if (currentPageIndex < pages.size - 1) {
                    if (scrollMode == MangaScrollMode.WEBTOON) {
                        lazyListState.animateScrollToItem(currentPageIndex + 1)
                    } else {
                        pagerState.animateScrollToPage(currentPageIndex + 1)
                    }
                } else {
                    isOcrActive = false
                }
            }
        }
    }

    // Read active speech bubble
    LaunchedEffect(activeBubbleIndex) {
        if (isOcrActive && activeBubbleIndex in ocrPanels.indices) {
            val panel = ocrPanels[activeBubbleIndex]
            
            // Auto-scroll the view to center on speech bubble coordinates
            if (scrollMode == MangaScrollMode.WEBTOON) {
                // Scroll in vertical mode
                val targetY = panel.bounds.top.roundToInt()
                lazyListState.animateScrollToItem(currentPageIndex, targetY - 100)
            }

            // Play narration
            ttsController.readText(panel.text)
        }
    }

    // Listen to TTS completion to skip to next speech bubble
    LaunchedEffect(isTtsPlaying.value) {
        if (isOcrActive && !isTtsPlaying.value && activeBubbleIndex >= 0) {
            if (activeBubbleIndex < ocrPanels.size - 1) {
                activeBubbleIndex++
            } else {
                // Read all bubbles on this page, swipe/scroll to next page!
                if (currentPageIndex < pages.size - 1) {
                    currentPageIndex++
                    if (scrollMode == MangaScrollMode.WEBTOON) {
                        lazyListState.animateScrollToItem(currentPageIndex)
                    } else {
                        pagerState.animateScrollToPage(currentPageIndex)
                    }
                } else {
                    isOcrActive = false
                    activeBubbleIndex = -1
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Manga reads best on pure black
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = currentTheme.accentColor())
            }
        } else {
            // ── Reader Viewport ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { showOverlays = !showOverlays }
                    }
            ) {
                when (scrollMode) {
                    MangaScrollMode.WEBTOON -> {
                        // Webtoon vertical scrolling
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(pages) { index, pageUrl ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    AsyncImage(
                                        model = pageUrl,
                                        contentDescription = "Page ${index + 1}",
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    // Render speech bubble highlights when OCR active
                                    if (isOcrActive && index == currentPageIndex) {
                                        ocrPanels.forEachIndexed { pIdx, panel ->
                                            Box(
                                                modifier = Modifier
                                                    .offset {
                                                        IntOffset(
                                                            panel.bounds.left.roundToInt(),
                                                            panel.bounds.top.roundToInt()
                                                        )
                                                    }
                                                    .size(
                                                        width = panel.bounds.width.dp,
                                                        height = panel.bounds.height.dp
                                                    )
                                                    .border(
                                                        width = if (pIdx == activeBubbleIndex) 3.dp else 1.5.dp,
                                                        color = if (pIdx == activeBubbleIndex)
                                                            currentTheme.accentColor()
                                                        else Color.White.copy(0.4f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    MangaScrollMode.RTL -> {
                        // Traditional Japanese RTL horizontal swipes
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { pageIndex ->
                                AsyncImage(
                                    model = pages[pageIndex],
                                    contentDescription = "Page ${pageIndex + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    MangaScrollMode.LTR -> {
                        // Comic book LTR horizontal swipes
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { pageIndex ->
                            AsyncImage(
                                model = pages[pageIndex],
                                contentDescription = "Page ${pageIndex + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // ── Animated Top Overlay Bar ─────────────────────────────────────
            AnimatedVisibility(
                visible = showOverlays,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = Color.Black.copy(0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                mangaTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(0.6f),
                                maxLines = 1
                            )
                            Text(
                                chapterTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ── Animated Bottom Overlay Bar ──────────────────────────────────
            AnimatedVisibility(
                visible = showOverlays,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = Color.Black.copy(0.88f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(14.dp)
                    ) {
                        // Direction/Scroll mode toggles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MangaScrollMode.values().forEach { mode ->
                                val isSelected = scrollMode == mode
                                Text(
                                    text = mode.displayName,
                                    color = if (isSelected) currentTheme.accentColor() else Color.White.copy(0.5f),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) currentTheme.accentColor().copy(0.2f)
                                            else Color.Transparent
                                        )
                                        .clickable { scrollMode = mode }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // AI speech Recognition panel play/pause
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "AI Panel Reader",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Auto-scroll and voice-act speech bubbles",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(0.5f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    isOcrActive = !isOcrActive
                                    if (!isOcrActive) {
                                        ttsController.stop()
                                        activeBubbleIndex = -1
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        if (isOcrActive) currentTheme.accentColor()
                                        else Color.White.copy(0.15f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    if (isOcrActive) Icons.Default.HearingDisabled
                                    else Icons.Default.Hearing,
                                    contentDescription = "Voice narration toggle",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
