package com.alexleoreeves.novelapp.tv.ui.screens

import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * TV Embed Player Screen — full-screen WebView player for embed URLs.
 * Uses the exact same WebView approach as the working Android MaServerPlayerScreen.
 */
@Composable
fun TvEmbedPlayerScreen(
    embedUrl: String,
    title: String,
    onBack: () -> Unit,
    account: SavedUserAccount? = null,
    previewLimitMs: Long? = null,
    isEpisodic: Boolean = false,
    episodicFraction: Double = TV_EPISODIC_FREE_FRACTION,
    onUpgrade: () -> Unit = {}
) {
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var previewExpired by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webViewStartedAt by remember { mutableStateOf(0L) }

    val isPremium = account?.isPremium == true

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    // Poll video state from the WebView and enforce preview cap
    LaunchedEffect(embedUrl, previewLimitMs) {
        webViewStartedAt = System.currentTimeMillis()
        while (true) {
            val webView = webViewRef
            if (webView == null) {
                delay(200)
                continue
            }
            webView.evaluateJavascript(EMBED_VIDEO_STATE_JS) { raw ->
                val value = raw?.trim()?.trim('"')?.replace("\\\"", "\"") ?: return@evaluateJavascript
                runCatching {
                    val obj = JSONObject(value)
                    val positionMs = obj.optLong("currentTime", 0L)
                    val durationMs = obj.optLong("duration", 0L)
                    val paused = obj.optBoolean("paused", true)
                    if (obj.optBoolean("ready", false)) duration = durationMs
                    currentPosition = positionMs
                    isPlaying = !paused
                    if (!isPremium && !previewExpired) {
                        val limit = if (isEpisodic && durationMs > 0) {
                            (durationMs * episodicFraction).toLong().coerceAtLeast(1)
                        } else {
                            (previewLimitMs ?: TV_MOVIE_FREE_PREVIEW_MS).coerceAtMost(TV_MOVIE_FREE_PREVIEW_MS)
                        }
                        val positionForLimit = if (durationMs > 0) positionMs else (System.currentTimeMillis() - webViewStartedAt)
                        if (positionForLimit >= limit) {
                            previewExpired = true
                            webView.evaluateJavascript(EMBED_PAUSE_JS, null)
                        }
                    }
                }
            }
            delay(1000)
        }
    }

    fun playerSeekTo(positionMs: Long) {
        webViewRef?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){v.currentTime=${positionMs / 1000.0};}})()",
            null
        )
    }

    fun playerTogglePlay() {
        val wv = webViewRef ?: return
        // JS cannot reach video elements inside cross-origin iframes (VidLink, Nontongo, etc).
        // A real touch event dispatched to the WebView is treated as a genuine user gesture
        // and hits the rendered pixels directly — bypassing the cross-origin restriction.
        val w = wv.width.takeIf { it > 0 } ?: 1920
        val h = wv.height.takeIf { it > 0 } ?: 1080
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventDown = android.view.MotionEvent.obtain(
            downTime, downTime,
            android.view.MotionEvent.ACTION_DOWN,
            w / 2f, h / 2f, 0
        )
        val eventUp = android.view.MotionEvent.obtain(
            downTime, downTime + 100,
            android.view.MotionEvent.ACTION_UP,
            w / 2f, h / 2f, 0
        )
        wv.dispatchTouchEvent(eventDown)
        wv.dispatchTouchEvent(eventUp)
        eventDown.recycle()
        eventUp.recycle()
    }

    BackHandler { onBack() }

    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) {
                    if (previewExpired) {
                        when (event.key) {
                            Key.Back -> { onBack(); true }
                            else -> true
                        }
                    } else {
                        showControls = true
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.DirectionLeft -> { playerSeekTo(currentPosition - 10000); true }
                                Key.DirectionRight -> { playerSeekTo(currentPosition + 10000); true }
                                Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { playerTogglePlay(); true }
                                Key.MediaFastForward -> { playerSeekTo(currentPosition + 30000); true }
                                Key.MediaRewind -> { playerSeekTo(currentPosition - 15000); true }
                                Key.Back -> { onBack(); true }
                                else -> false
                            }
                        } else {
                            // Intercept KeyDown for navigation keys so focus doesn't jump
                            when (event.key) {
                                Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter,
                                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaFastForward, Key.MediaRewind -> true
                                else -> false
                            }
                        }
                    }
                } else false
            }
    ) {
        // TvEmbedPlayer — exact same WebView logic as working Android MaServerPlayerScreen
        TvEmbedPlayer(
            embedUrl = embedUrl,
            episodeTitle = title,
            onWebViewCreated = { webView -> webViewRef = webView },
            onBack = onBack
        )

        // Free preview ended gate
        if (previewExpired) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF05050A).copy(0.96f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Default.Lock, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(80.dp))
                    Text("Free Preview Ended", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
                    Text("Go premium to watch the full title.", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onUpgrade, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))) {
                        Text("Go Premium", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Controls overlay (Back + Title + Progress)
        AnimatedVisibility(
            visible = showControls && !previewExpired,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent, Color.Black.copy(0.7f)))
                )
            ) {
                // Top bar: Back + Title
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    var backFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onBack, shape = CircleShape,
                        color = if (backFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (backFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier.size(44.dp).onFocusChanged { backFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }

                // Center play/pause button
                Row(modifier = Modifier.fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    var ppFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { playerTogglePlay() }, shape = CircleShape,
                        color = if (ppFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (ppFocused) BorderStroke(3.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier.size(72.dp).onFocusChanged { ppFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                }

                // Bottom progress bar
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    val progress = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color(0xFF00BFFF), trackColor = Color.White.copy(0.15f))
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatEmbedTime(currentPosition), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(formatEmbedTime(duration), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private const val EMBED_VIDEO_STATE_JS =
    "(function(){var v=document.querySelector('video');" +
        "if(!v){return JSON.stringify({currentTime:0,duration:0,paused:true,ready:false});}" +
        "var d=(v.duration&&isFinite(v.duration))?v.duration*1000:0;" +
        "var c=(v.currentTime&&isFinite(v.currentTime))?v.currentTime*1000:0;" +
        "return JSON.stringify({currentTime:c,duration:d,paused:v.paused,ready:d>0});})()"

private const val EMBED_PAUSE_JS =
    "(function(){var v=document.querySelector('video');if(v){v.pause();v.muted=true;}})()"

private fun formatEmbedTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
