package com.alexleoreeves.novelapp.tv.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvPlayerScreen(
    streamUrl: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isWebEmbed by remember {
        mutableStateOf(
            streamUrl.contains("vidsrc") || streamUrl.contains("embed") ||
            streamUrl.contains("kisskh") || streamUrl.contains("dramacool") ||
            streamUrl.contains("kimcartoon") || streamUrl.contains("flixhq")
        )
    }

    val exoPlayer = remember {
        if (!isWebEmbed) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            duration = this@apply.duration
                        }
                    }
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        errorMsg = error.localizedMessage ?: "Playback error"
                    }
                })
            }
        } else null
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.stop()
            exoPlayer?.release()
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    // Update position periodically when controls visible
    LaunchedEffect(showControls) {
        while (showControls && exoPlayer != null) {
            currentPosition = exoPlayer.currentPosition
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    showControls = true
                    when (event.key) {
                        Key.DirectionLeft -> {
                            exoPlayer?.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionRight -> {
                            exoPlayer?.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            exoPlayer?.let {
                                if (it.isPlaying) it.pause() else it.play()
                            }
                            true
                        }
                        Key.MediaPlayPause -> {
                            exoPlayer?.let {
                                if (it.isPlaying) it.pause() else it.play()
                            }
                            true
                        }
                        Key.MediaFastForward -> {
                            exoPlayer?.seekTo((exoPlayer.currentPosition + 30000).coerceAtMost(exoPlayer.duration))
                            true
                        }
                        Key.MediaRewind -> {
                            exoPlayer?.seekTo((exoPlayer.currentPosition - 15000).coerceAtLeast(0))
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
        if (isWebEmbed) {
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportMultipleWindows(false)
                            allowFileAccess = false
                            allowContentAccess = false
                        }
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: ""
                                return !url.contains("vidsrc") && !url.contains("kisskh") &&
                                       !url.contains("dramacool") && !url.contains("kimcartoon") &&
                                       !url.contains("flixhq") && !url.contains("themoviedb") &&
                                       !url.contains("tmdb")
                            }
                        }
                        loadUrl(streamUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Error overlay
        if (errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(64.dp))
                    Text("Playback Error", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(errorMsg!!, color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
                    var backFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF)),
                        modifier = Modifier.onFocusChanged { backFocused = it }
                    ) {
                        Text("Go Back", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls && errorMsg == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(0.7f),
                                Color.Transparent,
                                Color.Black.copy(0.7f)
                            )
                        )
                    )
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var backFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = if (backFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (backFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier
                            .size(44.dp)
                            .onFocusChanged { backFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Center controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 15s
                    var rwFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { exoPlayer?.seekTo((exoPlayer.currentPosition - 15000).coerceAtLeast(0)) },
                        shape = CircleShape,
                        color = if (rwFocused) Color(0xFF00BFFF).copy(0.5f) else Color.Black.copy(0.5f),
                        border = if (rwFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier
                            .size(56.dp)
                            .onFocusChanged { rwFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(Modifier.width(24.dp))

                    // Play/Pause
                    var ppFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            exoPlayer?.let {
                                if (it.isPlaying) it.pause() else it.play()
                            }
                        },
                        shape = CircleShape,
                        color = if (ppFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (ppFocused) BorderStroke(3.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { ppFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(24.dp))

                    // Forward 30s
                    var ffFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { exoPlayer?.seekTo((exoPlayer.currentPosition + 30000).coerceAtMost(exoPlayer.duration)) },
                        shape = CircleShape,
                        color = if (ffFocused) Color(0xFF00BFFF).copy(0.5f) else Color.Black.copy(0.5f),
                        border = if (ffFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier
                            .size(56.dp)
                            .onFocusChanged { ffFocused = it }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Forward30, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }

                // Bottom bar with progress
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    val progress = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = Color(0xFF00BFFF),
                        trackColor = Color.White.copy(0.15f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPosition), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(formatTime(duration), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                // D-pad hint
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 60.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        HintBadge("\u2190 10s")
                        HintBadge("\u2192 10s")
                        HintBadge("\u23F8 Play/Pause")
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

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
