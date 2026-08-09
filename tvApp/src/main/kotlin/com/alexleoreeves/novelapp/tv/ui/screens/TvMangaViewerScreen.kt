package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvMangaViewerScreen(
    pages: List<String>,
    title: String,
    onBack: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var currentPage by remember { mutableStateOf(0) }
    var zoomScale by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    // Sync pager state
    LaunchedEffect(pagerState.currentPage) {
        currentPage = pagerState.currentPage
    }

    // Request focus so D-pad events are captured
    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (currentPage < pages.size - 1) {
                                scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (currentPage < pages.size - 1) {
                                scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                            }
                            true
                        }
                        Key.PageUp -> {
                            val jump = (currentPage - 5).coerceAtLeast(0)
                            scope.launch { pagerState.animateScrollToPage(jump) }
                            true
                        }
                        Key.PageDown -> {
                            val jump = (currentPage + 5).coerceAtMost(pages.size - 1)
                            scope.launch { pagerState.animateScrollToPage(jump) }
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            showControls = !showControls
                            true
                        }
                        Key.Back -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Manga pages (horizontal pager)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (pageIndex < pages.size) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val imageRequest = remember(pages[pageIndex]) {
                        val headers = if (pages[pageIndex].contains("webtoon")) {
                            NetworkHeaders.Builder()
                                .set("Referer", "https://www.webtoons.com")
                                .build()
                        } else {
                            NetworkHeaders.EMPTY
                        }
                        coil3.request.ImageRequest.Builder(context)
                            .data(pages[pageIndex])
                            .httpHeaders(headers)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "Page ${pageIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                            }
                    )
                } else {
                    Text("Page not available", color = Color.White.copy(0.5f))
                }
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.6f))
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    var backFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = if (backFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (backFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier
                            .size(44.dp)
                            .onFocusChanged { backFocused= it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp))
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
                        Text(
                            "Page ${currentPage + 1} of ${pages.size}",
                            color = Color.White.copy(0.6f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Zoom controls
                    var zoomOutFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(0.5f) },
                        shape = CircleShape,
                        color = if (zoomOutFocused) Purple500.copy(0.5f) else Color.Black.copy(0.6f),
                        border = if (zoomOutFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(40.dp)
                            .onFocusChanged { zoomOutFocused= it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ZoomOut, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }

                    Surface(
                        color = Color.Black.copy(0.6f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "${(zoomScale * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    var zoomInFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(3f) },
                        shape = CircleShape,
                        color = if (zoomInFocused) Purple500.copy(0.5f) else Color.Black.copy(0.6f),
                        border = if (zoomInFocused) BorderStroke(2.dp, Purple500) else null,
                        modifier = Modifier
                            .size(40.dp)
                            .onFocusChanged { zoomInFocused= it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ZoomIn, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Bottom index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Black.copy(0.6f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${currentPage + 1} / ${pages.size}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // D-pad hint
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 60.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HintBadge("\u2190 Prev")
                        HintBadge("\u2192 Next")
                        HintBadge("OK Toggle menu")
                    }
                }
            }
        }
    }
}

@Composable
private fun HintBadge(text: String) {
    Surface(
        color = Color.White.copy(0.08f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            color = Color.White.copy(0.5f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
