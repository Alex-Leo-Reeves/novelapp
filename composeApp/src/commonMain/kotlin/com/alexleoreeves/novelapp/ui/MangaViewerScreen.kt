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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.audio.KokoroNarrationController
import com.alexleoreeves.novelapp.audio.MangaOcrReader
import com.alexleoreeves.novelapp.audio.OcrTextPanel
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.MangaReaderSystemUiEffect
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    ttsController: KokoroNarrationController,
    initialPageIndex: Int = 0,
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
    val ocrReader = remember { MangaOcrReader() }

    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showOverlays by remember { mutableStateOf(true) }
    var scrollMode by remember { mutableStateOf(MangaScrollMode.WEBTOON) }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var usePureDarkReader by remember { mutableStateOf(true) }
    var preloadStatus by remember { mutableStateOf("Loading chapter pages...") }
    var preloadCompleted by remember { mutableStateOf(0) }
    var preloadTotal by remember { mutableStateOf(0) }

    // TTS and OCR Tracking
    var isOcrActive by remember { mutableStateOf(false) }
    var ocrPanels by remember { mutableStateOf<List<OcrTextPanel>>(emptyList()) }
    var activeBubbleIndex by remember { mutableStateOf(-1) }
    var currentPageIndex by remember { mutableStateOf(0) }
    var ocrReadingPageIndex by remember { mutableStateOf(-1) }
    var pageSizes by remember { mutableStateOf<Map<Int, IntSize>>(emptyMap()) }
    var ocrStatus by remember { mutableStateOf("Panel reader idle") }
    var skipEmptyOcrPages by remember { mutableStateOf(false) }
    var ocrReaderJob by remember { mutableStateOf<Job?>(null) }

    // Scroll states
    val lazyListState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val density = LocalDensity.current
    val readerBackground = if (usePureDarkReader) Color.Black else currentTheme.backgroundColor()

    MangaReaderSystemUiEffect(enabled = true)

    LaunchedEffect(Unit) {
        clearTemporaryMangaPageCache()
    }

    DisposableEffect(chapterUrl) {
        onDispose {
            ocrReaderJob?.cancel()
            isOcrActive = false
            activeBubbleIndex = -1
            ocrReadingPageIndex = -1
            ttsController.stop()
        }
    }

    // Load pages on launch
    LaunchedEffect(chapterUrl) {
        isLoading = true
        preloadStatus = "Loading chapter pages..."
        preloadCompleted = 0
        preloadTotal = 0
        val loadedPages = if (sourceName == "local") {
            chapterUrl.split(",")
        } else {
            repository.fetchMangaPages(chapterUrl, sourceName)
        }
        preloadTotal = loadedPages.size
        currentPageIndex = initialPageIndex.coerceAtLeast(0)
        pages = when {
            loadedPages.isEmpty() -> {
                preloadStatus = "No manga pages found."
                emptyList()
            }
            sourceName == "local" -> {
                preloadCompleted = loadedPages.size
                preloadStatus = "Chapter loaded."
                loadedPages
            }
            else -> {
                preloadStatus = "Caching pages for smooth reading..."
                runCatching {
                    cacheMangaChapterPages(
                        chapterKey = "$sourceName:$mangaTitle:$chapterTitle:$chapterUrl",
                        pageUrls = loadedPages,
                        persistent = false,
                        onProgress = { completed, total ->
                            preloadCompleted = completed.coerceAtMost(total)
                            preloadTotal = total
                            preloadStatus = "Caching page ${completed.coerceAtMost(total)} of $total..."
                        }
                    )
                }.getOrElse {
                    preloadStatus = "Using online pages; cache failed."
                    loadedPages
                }.also { cachedPages ->
                    if (cachedPages.isNotEmpty()) {
                        preloadCompleted = cachedPages.size
                        preloadTotal = cachedPages.size
                        preloadStatus = "Chapter cached for this session."
                    }
                }
            }
        }
        isLoading = false
    }

    LaunchedEffect(isLoading, pages.size, initialPageIndex, scrollMode) {
        if (!isLoading && pages.isNotEmpty()) {
            val target = initialPageIndex.coerceIn(0, pages.lastIndex)
            currentPageIndex = target
            if (scrollMode == MangaScrollMode.WEBTOON) {
                lazyListState.scrollToItem(target)
            } else {
                pagerState.scrollToPage(target)
            }
        }
    }

    // Sync currentPageIndex between Pager and Vertical List
    LaunchedEffect(pagerState.currentPage) {
        if (scrollMode != MangaScrollMode.WEBTOON) {
            currentPageIndex = pagerState.currentPage
        }
    }

    LaunchedEffect(lazyListState.firstVisibleItemIndex) {
        if (scrollMode == MangaScrollMode.WEBTOON) {
            currentPageIndex = lazyListState.firstVisibleItemIndex.coerceAtLeast(0)
        }
    }

    LaunchedEffect(currentPageIndex) {
        onProgress(currentPageIndex.coerceAtLeast(0))
    }

    LaunchedEffect(isOcrActive) {
        if (!isOcrActive) {
            ocrReaderJob?.cancel()
            ocrReaderJob = null
            activeBubbleIndex = -1
            ocrReadingPageIndex = -1
            ocrPanels = emptyList()
            ttsController.stop()
            return@LaunchedEffect
        }
        if (pages.isEmpty()) {
            isOcrActive = false
            ocrStatus = "No manga pages loaded."
            return@LaunchedEffect
        }

        ocrReaderJob?.cancel()
        ocrReaderJob = scope.launch {
            var pageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
            while (currentCoroutineContext().isActive && isOcrActive && pageIndex in pages.indices) {
                ocrReadingPageIndex = pageIndex
                activeBubbleIndex = -1
                ocrStatus = "Scanning page ${pageIndex + 1}..."
                val panels = ocrReader.recognizeTextFromUrl(pages[pageIndex])
                    .filter { it.text.isNotBlank() }
                    .sortedWith(compareBy<OcrTextPanel> { it.bounds.top }.thenBy { it.bounds.left })
                ocrPanels = panels

                if (panels.isEmpty()) {
                    ocrStatus = "No readable text found on page ${pageIndex + 1}."
                    if (skipEmptyOcrPages && autoScrollEnabled && pageIndex < pages.lastIndex) {
                        pageIndex += 1
                        if (scrollMode == MangaScrollMode.WEBTOON) {
                            lazyListState.animateScrollToItem(pageIndex)
                        } else {
                            pagerState.animateScrollToPage(pageIndex)
                        }
                        continue
                    }
                    break
                }

                panels.forEachIndexed { bubbleIndex, panel ->
                    if (!currentCoroutineContext().isActive || !isOcrActive) return@launch
                    activeBubbleIndex = bubbleIndex
                    ocrStatus = "Reading bubble ${bubbleIndex + 1} of ${panels.size} on page ${pageIndex + 1}"

                    if (scrollMode == MangaScrollMode.WEBTOON) {
                        val pageSize = pageSizes[pageIndex]
                        val scaleY = if (pageSize != null && panel.imageHeight > 0) {
                            pageSize.height.toFloat() / panel.imageHeight.toFloat()
                        } else {
                            1f
                        }
                        val targetY = (panel.bounds.top * scaleY).roundToInt()
                        lazyListState.animateScrollToItem(pageIndex, (targetY - 120).coerceAtLeast(0))
                    }

                    ttsController.readText(panel.text)
                }

                if (autoScrollEnabled && pageIndex < pages.lastIndex) {
                    pageIndex += 1
                    if (scrollMode == MangaScrollMode.WEBTOON) {
                        lazyListState.animateScrollToItem(pageIndex)
                    } else {
                        pagerState.animateScrollToPage(pageIndex)
                    }
                } else {
                    ocrStatus = "Panel reader finished."
                    break
                }
            }
            isOcrActive = false
            activeBubbleIndex = -1
            ocrReadingPageIndex = -1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBackground)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = currentTheme.accentColor())
                    Spacer(Modifier.height(14.dp))
                    Text(
                        preloadStatus,
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (preloadTotal > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (preloadCompleted.toFloat() / preloadTotal.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(0.62f),
                            color = currentTheme.accentColor(),
                            trackColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                }
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
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            itemsIndexed(pages) { index, pageUrl ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(readerBackground)
                                        .onGloballyPositioned { coordinates ->
                                            pageSizes = pageSizes + (index to coordinates.size)
                                        }
                                ) {
                                    AsyncImage(
                                        model = pageUrl,
                                        contentDescription = "Page ${index + 1}",
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    // Render speech bubble highlights when OCR active
                                    if (isOcrActive && index == currentPageIndex) {
                                        ocrPanels.forEachIndexed { pIdx, panel ->
                                            val renderedSize = pageSizes[index]
                                            val scaleX = if (renderedSize != null && panel.imageWidth > 0) {
                                                renderedSize.width.toFloat() / panel.imageWidth.toFloat()
                                            } else {
                                                1f
                                            }
                                            val scaleY = if (renderedSize != null && panel.imageHeight > 0) {
                                                renderedSize.height.toFloat() / panel.imageHeight.toFloat()
                                            } else {
                                                1f
                                            }
                                            val boxWidth = with(density) { (panel.bounds.width * scaleX).toDp() }
                                            val boxHeight = with(density) { (panel.bounds.height * scaleY).toDp() }
                                            Box(
                                                modifier = Modifier
                                                    .offset {
                                                        IntOffset(
                                                            (panel.bounds.left * scaleX).roundToInt(),
                                                            (panel.bounds.top * scaleY).roundToInt()
                                                        )
                                                    }
                                                    .size(
                                                        width = boxWidth,
                                                        height = boxHeight
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

            AnimatedVisibility(
                visible = preloadTotal > 0 && preloadCompleted in 1 until preloadTotal,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.74f),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            progress = { (preloadCompleted.toFloat() / preloadTotal.toFloat()).coerceIn(0f, 1f) },
                            color = currentTheme.accentColor(),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            preloadStatus,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
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
                                    if (isOcrActive) ocrStatus else "Auto-scroll and voice-act speech bubbles",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(0.5f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    isOcrActive = !isOcrActive
                                    if (!isOcrActive) {
                                        ocrReaderJob?.cancel()
                                        ttsController.stop()
                                        activeBubbleIndex = -1
                                        ocrReadingPageIndex = -1
                                        ocrStatus = "Panel reader stopped."
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

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                null,
                                tint = Color.White.copy(0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Skip empty OCR pages",
                                color = Color.White.copy(0.75f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = skipEmptyOcrPages,
                                onCheckedChange = { skipEmptyOcrPages = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = currentTheme.accentColor(),
                                    checkedTrackColor = currentTheme.accentColor().copy(alpha = 0.45f)
                                )
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DarkMode,
                                null,
                                tint = Color.White.copy(0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Pure dark manga reader",
                                color = Color.White.copy(0.75f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = usePureDarkReader,
                                onCheckedChange = { usePureDarkReader = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = currentTheme.accentColor(),
                                    checkedTrackColor = currentTheme.accentColor().copy(alpha = 0.45f)
                                )
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.SwipeUp,
                                null,
                                tint = Color.White.copy(0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Auto-scroll",
                                color = Color.White.copy(0.75f),
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
                    }
                }
            }
        }
    }
}
