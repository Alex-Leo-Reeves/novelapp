package com.alexleoreeves.novelapp.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
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
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.alexleoreeves.novelapp.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android actual: Full-screen immersive ExoPlayer with:
 *  - Landscape lock on enter, restored on exit
 *  - Auto-hiding overlay controls (3-second idle timeout)
 *  - Audio language picker: Japanese (ja) / English (en)
 *  - Subtitle track picker: Japanese (ja) / English (en) / Off
 *  - Seek bar with 10s skip forward/back
 */
@Composable
actual fun AnimePlayerScreen(
    streamUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // ── Orientation & Immersive Mode ──────────────────────────────────────
    DisposableEffect(Unit) {
        // Force landscape
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Hide system bars
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

    // ── ExoPlayer setup ───────────────────────────────────────────────────
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // ── UI State ──────────────────────────────────────────────────────────
    var showControls by remember { mutableStateOf(true) }
    var audioLanguage by remember { mutableStateOf("ja") }   // "ja" or "en"
    var subtitleMode by remember { mutableStateOf("en") }    // "ja", "en", or "off"
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }

    // Auto-hide controls after 3 seconds of no interaction
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Apply audio language to ExoPlayer
    LaunchedEffect(audioLanguage) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguage(audioLanguage)
            .build()
    }

    // Apply subtitle track to ExoPlayer
    LaunchedEffect(subtitleMode) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .apply {
                if (subtitleMode == "off") {
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    setPreferredTextLanguage(subtitleMode)
                }
            }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(indication = null, interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }) { showControls = !showControls }
    ) {
        // ── Video View ────────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false   // We use our own custom controls
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Custom Overlay Controls ───────────────────────────────────────
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top bar — title + back + settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        episodeTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }

                // Center controls — Skip back / Play-Pause / Skip forward
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip back 10s
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo(maxOf(0L, exoPlayer.currentPosition - 10_000L))
                            showControls = true
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "Skip back 10s",
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Play / Pause
                    val isPlaying by produceState(initialValue = exoPlayer.isPlaying) {
                        while (true) {
                            value = exoPlayer.isPlaying
                            delay(300)
                        }
                    }
                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showControls = true
                        },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Skip forward 10s
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                            showControls = true
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "Skip forward 10s",
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Bottom bar — Seek + Audio/Subtitle controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    // Seek bar
                    val position by produceState(initialValue = 0L) {
                        while (true) {
                            value = exoPlayer.currentPosition
                            delay(500)
                        }
                    }
                    val duration = exoPlayer.duration.takeIf { it > 0 } ?: 1L
                    Slider(
                        value = position.toFloat() / duration.toFloat(),
                        onValueChange = { pct ->
                            exoPlayer.seekTo((pct * duration).toLong())
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Time label
                        Text(
                            "${formatMs(position)} / ${formatMs(duration)}",
                            color = Color.White.copy(0.8f),
                            style = MaterialTheme.typography.labelSmall
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Audio language button
                            OutlinedButton(
                                onClick = { showAudioPicker = !showAudioPicker },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(0.5f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.VolumeUp,
                                    null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (audioLanguage == "ja") "JP Audio" else "EN Audio",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            // Subtitle button
                            OutlinedButton(
                                onClick = { showSubtitlePicker = !showSubtitlePicker },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(0.5f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.ClosedCaption,
                                    null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when (subtitleMode) {
                                        "ja" -> "JP Subs"
                                        "en" -> "EN Subs"
                                        else -> "Subs Off"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Audio Language Picker Popup ───────────────────────────────────
        AnimatedVisibility(
            visible = showAudioPicker,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Card(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 96.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Audio Language",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    LanguageOption(
                        label = "🇯🇵  Japanese",
                        selected = audioLanguage == "ja",
                        onClick = { audioLanguage = "ja"; showAudioPicker = false }
                    )
                    LanguageOption(
                        label = "🇺🇸  English",
                        selected = audioLanguage == "en",
                        onClick = { audioLanguage = "en"; showAudioPicker = false }
                    )
                }
            }
        }

        // ── Subtitle Picker Popup ─────────────────────────────────────────
        AnimatedVisibility(
            visible = showSubtitlePicker,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Card(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 96.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Subtitles",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    LanguageOption(
                        label = "🇺🇸  English",
                        selected = subtitleMode == "en",
                        onClick = { subtitleMode = "en"; showSubtitlePicker = false }
                    )
                    LanguageOption(
                        label = "🇯🇵  Japanese",
                        selected = subtitleMode == "ja",
                        onClick = { subtitleMode = "ja"; showSubtitlePicker = false }
                    )
                    LanguageOption(
                        label = "⛔  Off",
                        selected = subtitleMode == "off",
                        onClick = { subtitleMode = "off"; showSubtitlePicker = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (selected) {
            Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
