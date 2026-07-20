package com.alexleoreeves.novelapp.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay

@Composable
actual fun YouTubePlayerScreen(
    videoId: String,
    title: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(true) }
    var youTubePlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentTime by remember { mutableStateOf(0f) }
    var videoDuration by remember { mutableStateOf(0f) }

    // Lock to landscape, immersive mode
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val ctrl = WindowInsetsControllerCompat(window, window.decorView)
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { showControls = !showControls }
    ) {
        AndroidView(
            factory = { ctx ->
                YouTubePlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    enableAutomaticInitialization = false
                    (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(this)

                    initialize(object : AbstractYouTubePlayerListener() {
                        override fun onReady(ytPlayer: YouTubePlayer) {
                            youTubePlayer = ytPlayer
                            ytPlayer.loadVideo(videoId, 0f)
                            ytPlayer.play()
                        }

                        override fun onCurrentSecond(ytPlayer: YouTubePlayer, second: Float) {
                            currentTime = second
                        }

                        override fun onVideoDuration(ytPlayer: YouTubePlayer, duration: Float) {
                            videoDuration = duration
                        }

                        override fun onStateChange(ytPlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                            isPlaying = state == PlayerConstants.PlayerState.PLAYING
                        }
                    }, true)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.45f))
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        youTubePlayer?.pause()
                        onBack()
                    }) {
                        Icon(
                            Icons.Default.ArrowBack, null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }

                // Center play/pause
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            youTubePlayer?.let { p ->
                                p.seekTo(maxOf(0f, currentTime - 10f))
                            }
                            showControls = true
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                    }
                    IconButton(
                        onClick = {
                            youTubePlayer?.let { p ->
                                if (isPlaying) p.pause() else p.play()
                            }
                            showControls = true
                        },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            null,
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    IconButton(
                        onClick = {
                            youTubePlayer?.let { p ->
                                p.seekTo(currentTime + 10f)
                            }
                            showControls = true
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                    }
                }

                // Bottom progress bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    val progress = if (videoDuration > 0f) (currentTime / videoDuration).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = progress,
                        onValueChange = { value ->
                            youTubePlayer?.let { p ->
                                p.seekTo(value * videoDuration)
                            }
                            showControls = true
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(0.3f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatYouTubeTime(currentTime),
                            color = Color.White.copy(0.8f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            formatYouTubeTime(videoDuration),
                            color = Color.White.copy(0.8f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun formatYouTubeTime(seconds: Float): String {
    val totalSecs = seconds.toInt()
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}
