package com.alexleoreeves.novelapp.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.accentColor
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Android actual: Full-screen immersive ExoPlayer.
 *
 * Subtitles are enabled FROM THE START by setting track selection parameters
 * BEFORE prepare() — this ensures English subtitles show on any content that
 * has them in the manifest.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun AnimePlayerScreen(
    streamUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    initialPositionMs: Long,
    onProgress: (Long) -> Unit,
    previewLimitMs: Long?,
    onPreviewFinished: () -> Unit,
    contentKind: String,
    onBack: () -> Unit
) {
    val isDonghua = contentKind.equals("donghua", ignoreCase = true)
    val isAnime = contentKind.equals("anime", ignoreCase = true)
    // Show audio/subtitle controls only for anime and donghua content.
    // For Server 1-4 movie/TV content (contentKind is ""), hide controls and force English.
    val showAudioSubControls = isAnime || isDonghua
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // ── Orientation & Immersive Mode ──────────────────────────────────────
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

    // ── Scrape embed URLs for a direct stream ─────────────────────────────
    var retryKey by remember(streamUrl) { mutableStateOf(0) }
    var resolvedUrl by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var resolvedSourceUrl by remember(streamUrl, retryKey) { mutableStateOf(streamUrl) }
    var subtitlesJson by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var isResolving by remember(streamUrl, retryKey) { mutableStateOf(true) }
    var resolveError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var resolveFailed by remember(streamUrl, retryKey) { mutableStateOf(false) }
    var playerError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }

    // Audio language — default
    val defaultAudioLang = when {
        isDonghua -> "zh"
        isAnime -> "ja"
        else -> "en"
    }

    // UI state
    var showControls by remember { mutableStateOf(true) }
    var audioLanguage by remember { mutableStateOf(defaultAudioLang) }
    var subtitleMode by remember { mutableStateOf("en") }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }

    // Resolve stream URL via embed scraping
    LaunchedEffect(streamUrl, retryKey) {
        isResolving = true
        resolveError = null
        resolveFailed = false
        playerError = null

        val candidate = streamUrl.trim()
        if (candidate.isBlank()) {
            resolvedUrl = null
            resolvedSourceUrl = streamUrl
            subtitlesJson = null
            isResolving = false
            resolveFailed = true
            resolveError = "Could not load this stream."
            return@LaunchedEffect
        }

        if (candidate.isDirectPlayableMediaUrl()) {
            resolvedUrl = candidate
            resolvedSourceUrl = candidate
            subtitlesJson = null
            isResolving = false
            return@LaunchedEffect
        }

        var scraped: com.alexleoreeves.novelapp.ui.ScrapedStream? = null
        for (i in 0 until 2) {
            scraped = extractStreamFromEmbed(context, candidate, timeoutMs = 30_000L)
            if (scraped != null) break
            if (i == 0) delay(1000)
        }

        if (scraped != null) {
            resolvedUrl = scraped.url
            resolvedSourceUrl = candidate
            subtitlesJson = scraped.subtitlesJson
            isResolving = false
            return@LaunchedEffect
        }

        resolvedUrl = null
        resolvedSourceUrl = streamUrl
        subtitlesJson = null
        isResolving = false
        resolveFailed = true
        resolveError = "Could not load this stream."
    }

    // ── ExoPlayer setup ──────────────────────────────────────────────────
    // KEY FIX: Set subtitle/audio track preferences BEFORE prepare() so
    // ExoPlayer picks the correct tracks from the manifest
    val exoPlayer = remember(resolvedUrl, resolvedSourceUrl, subtitlesJson, subtitleMode, audioLanguage) {
        if (resolvedUrl != null) {
            val url = resolvedUrl!!
            val cache = NovelAppVideoCache.get(context)
            val requestHeaders = resolvedSourceUrl.playerHeaders()
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(PLAYER_USER_AGENT)
                .setDefaultRequestProperties(requestHeaders)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, 300_000, 1_500, 3_000)
                .setTargetBufferBytes(150 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val mediaItem = buildMediaItemWithSubtitles(url, subtitlesJson)

            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
                .build()
                .apply {
                    // ── CRITICAL: Set subtitle preferences BEFORE prepare() ──
                    val subEnabled = subtitleMode != "off"
                    val params = trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subEnabled)
                        .setPreferredAudioLanguage(audioLanguage)
                        .apply {
                            if (subEnabled) {
                                setPreferredTextLanguage(subtitleMode)
                            }
                        }
                        .build()
                    trackSelectionParameters = params

                    setMediaItem(mediaItem)
                    prepare()
                    if (initialPositionMs > 0L) seekTo(initialPositionMs)
                    playWhenReady = true
                }
        } else null
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    // Audio codec recovery
    var audioCodecRetries by remember(resolvedUrl, retryKey) { mutableStateOf(0) }
    val maxAudioCodecRetries = 2
    var playerRestartTrigger by remember(resolvedUrl, retryKey) { mutableStateOf(0) }
    var isPlayerBuffering by remember(resolvedUrl, retryKey) { mutableStateOf(true) }
    var playerReady by remember(resolvedUrl, retryKey) { mutableStateOf(false) }

    LaunchedEffect(audioCodecRetries) {
        if (audioCodecRetries in 1..maxAudioCodecRetries && exoPlayer != null) {
            delay(500L)
            exoPlayer?.stop()
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    LaunchedEffect(playerRestartTrigger) {
        if (playerRestartTrigger > 0 && exoPlayer != null && resolvedUrl != null) {
            audioCodecRetries = 0
            playerError = null
            isPlayerBuffering = true
            delay(300L)
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            val mediaItem = buildMediaItemWithSubtitles(resolvedUrl!!, subtitlesJson)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    val playerListener = remember(exoPlayer) {
        if (exoPlayer != null) {
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isPlayerBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                    if (playbackState == Player.STATE_READY) {
                        playerReady = true
                        isPlayerBuffering = false
                        playerError = null
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val isAudioCodecTimeout = error.localizedMessage?.contains("Error 0x80000000") == true ||
                        error.cause?.toString()?.contains("0x80000000") == true
                    if (isAudioCodecTimeout && audioCodecRetries < maxAudioCodecRetries) {
                        playerError = "Audio codec glitch — retrying... (${audioCodecRetries + 1}/$maxAudioCodecRetries)"
                        audioCodecRetries++
                    } else {
                        playerError = error.localizedMessage ?: "Stream failed to load."
                    }
                    isPlayerBuffering = false
                }
            }.also { exoPlayer.addListener(it) }
        } else null
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.let { player ->
                (playerListener as? Player.Listener)?.let { player.removeListener(it) }
            }
        }
    }

    // Buffering timeout
    val loadingTimeoutMs = 30_000L
    var bufferingTooLong by remember(resolvedUrl, retryKey) { mutableStateOf(false) }
    LaunchedEffect(isPlayerBuffering, retryKey) {
        if (!isPlayerBuffering || playerReady) { bufferingTooLong = false; return@LaunchedEffect }
        var elapsed = 0L
        while (elapsed < loadingTimeoutMs) {
            delay(2_500)
            elapsed += 2_500
            if (!isPlayerBuffering || playerReady) { bufferingTooLong = false; return@LaunchedEffect }
        }
        bufferingTooLong = true
        if (playerError == null) playerError = "Video is taking too long to load. Please click on retry."
    }

    // Progress tracking
    LaunchedEffect(exoPlayer) {
        if (exoPlayer != null) {
            while (true) {
                onProgress(exoPlayer.currentPosition)
                if (previewLimitMs != null && exoPlayer.currentPosition >= previewLimitMs) {
                    exoPlayer.pause()
                    onPreviewFinished()
                    break
                }
                delay(2500)
            }
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) { delay(3000); showControls = false }
    }

    // ── Audio language change (post-prepare override) ─────────────────────
    if (showAudioSubControls) {
        LaunchedEffect(audioLanguage) {
            val p = exoPlayer ?: return@LaunchedEffect
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon().setPreferredAudioLanguage(audioLanguage).build()
        }
    }

    // ── Subtitle mode change (post-prepare override) ──────────────────────
    LaunchedEffect(subtitleMode) {
        val p = exoPlayer ?: return@LaunchedEffect
        if (subtitleMode == "off") {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(subtitleMode)
                .build()
        }
    }

    // ── Render ────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isResolving) {
            PlayerLoadingOverlay(
                visible = true, title = episodeTitle,
                message = "Resolving high quality stream...", isError = false,
                onRetry = { retryKey++ }, onBack = onBack
            )
        } else if (resolveFailed) {
            PlayerLoadingOverlay(
                visible = true, title = episodeTitle,
                message = resolveError ?: "Could not load this stream.", isError = true,
                onRetry = { retryKey++ }, onBack = onBack
            )
        } else {
            val player = exoPlayer ?: return@Box
            var playbackSpeed by remember { mutableStateOf(1f) }
            var isSpeedLocked by remember { mutableStateOf(false) }
            var showSpeedPicker by remember { mutableStateOf(false) }

            LaunchedEffect(playbackSpeed) {
                player.playbackParameters = PlaybackParameters(playbackSpeed)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize().background(Color.Black)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { showControls = !showControls }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (playbackSpeed > 1f) {
                    Box(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                            .clickable { isSpeedLocked = false; playbackSpeed = 1f }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (isSpeedLocked) "2X SPEED (LOCKED)" else "2X SPEED",
                            color = currentTheme.accentColor(), fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                    }
                }

                PlayerLoadingOverlay(
                    visible = isPlayerBuffering || playerError != null,
                    title = episodeTitle,
                    message = playerError ?: "Buffering ahead...",
                    isError = playerError != null,
                    onRetry = { playerRestartTrigger++ }, onBack = onBack
                )

                AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                        Row(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                            Spacer(Modifier.width(8.dp))
                            Text(episodeTitle, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                        }

                        Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player.seekTo(maxOf(0L, player.currentPosition - 10_000L)); showControls = true }, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                            }
                            val isPlaying by produceState(initialValue = player.isPlaying) { while (true) { value = player.isPlaying; delay(300) } }
                            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play(); showControls = true }, modifier = Modifier.size(72.dp)) {
                                Icon(if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                            }
                            IconButton(onClick = { player.seekTo(player.currentPosition + 10_000L); showControls = true }, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                            }
                        }

                        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)) {
                            val position by produceState(initialValue = 0L) { while (true) { value = player.currentPosition; delay(500) } }
                            val duration = player.duration.takeIf { it > 0 } ?: 1L
                            Slider(value = position.toFloat() / duration.toFloat(), onValueChange = { player.seekTo((it * duration).toLong()); showControls = true },
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${formatMs(position)} / ${formatMs(duration)}", color = Color.White.copy(0.8f), style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (showAudioSubControls) {
                                        val audioLabel = when {
                                            isDonghua && audioLanguage == "zh" -> "CN Audio"
                                            isDonghua && audioLanguage == "en" -> "EN Audio"
                                            audioLanguage == "ja" -> "JP Audio"
                                            audioLanguage == "en" -> "EN Audio"
                                            else -> "Audio"
                                        }
                                        OutlinedButton(onClick = { showAudioPicker = !showAudioPicker },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = BorderStroke(1.dp, Color.White.copy(0.5f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                            Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                            Text(audioLabel, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    if (showAudioSubControls) {
                                        val subLabel = when {
                                            isDonghua && subtitleMode == "zh" -> "CN Subs"
                                            isDonghua && subtitleMode == "en" -> "EN Subs"
                                            isDonghua && subtitleMode == "off" -> "Subs Off"
                                            subtitleMode == "ja" -> "JP Subs"
                                            subtitleMode == "en" -> "EN Subs"
                                            else -> "Subs Off"
                                        }
                                        OutlinedButton(onClick = { showSubtitlePicker = !showSubtitlePicker },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = BorderStroke(1.dp, Color.White.copy(0.5f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                            Icon(Icons.Default.ClosedCaption, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                            Text(subLabel, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(onClick = { showSpeedPicker = !showSpeedPicker },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        border = BorderStroke(1.dp, Color.White.copy(0.5f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                        Text(if (playbackSpeed == 2f) "2x" else "1x", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // Audio picker
                AnimatedVisibility(visible = showAudioPicker, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Card(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 96.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Audio Language", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            if (isDonghua) {
                                LanguageOption1("🇨🇳  Chinese", audioLanguage == "zh") { audioLanguage = "zh"; showAudioPicker = false }
                            } else {
                                LanguageOption1("🇯🇵  Japanese", audioLanguage == "ja") { audioLanguage = "ja"; showAudioPicker = false }
                            }
                            LanguageOption1("🇺🇸  English", audioLanguage == "en") { audioLanguage = "en"; showAudioPicker = false }
                        }
                    }
                }

                // Subtitle picker
                AnimatedVisibility(visible = showSubtitlePicker, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Card(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 96.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Subtitles", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            LanguageOption1("🇺🇸  English", subtitleMode == "en") { subtitleMode = "en"; showSubtitlePicker = false }
                            if (isDonghua) {
                                LanguageOption1("🇨🇳  Chinese", subtitleMode == "zh") { subtitleMode = "zh"; showSubtitlePicker = false }
                            } else {
                                LanguageOption1("🇯🇵  Japanese", subtitleMode == "ja") { subtitleMode = "ja"; showSubtitlePicker = false }
                            }
                            LanguageOption1("⛔  Off", subtitleMode == "off") { subtitleMode = "off"; showSubtitlePicker = false }
                        }
                    }
                }

                // Speed picker
                AnimatedVisibility(visible = showSpeedPicker, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Card(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 96.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Speed", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            LanguageOption1("1x (Normal)", playbackSpeed == 1f) { playbackSpeed = 1f; isSpeedLocked = false; showSpeedPicker = false }
                            LanguageOption1("2x (Fast)", playbackSpeed == 2f) { playbackSpeed = 2f; isSpeedLocked = true; showSpeedPicker = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption1(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (selected) Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
    }
}

private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun String.isDirectPlayableMediaUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mpd") ||
           clean.endsWith(".webm") || clean.endsWith(".mkv") || clean.endsWith(".mov") || clean.endsWith(".ts") ||
           contains("/hls/", ignoreCase = true) || contains("/dash/", ignoreCase = true) || contains("/manifest/", ignoreCase = true) ||
           !startsWith("http", ignoreCase = true)
}

@Composable
private fun PlayerLoadingOverlay(
    visible: Boolean,
    title: String,
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isError) Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFF7A1A), modifier = Modifier.size(42.dp))
                else CircularProgressIndicator(color = Color(0xFFFF7A1A))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(message, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back")
                    }
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7A1A))) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry")
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private object NovelAppVideoCache {
    private var cache: SimpleCache? = null
    fun get(context: android.content.Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(context.cacheDir, "video-cache"), LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L), StandaloneDatabaseProvider(context)).also { cache = it }
    }
}

private fun String.playerReferer(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull()
    return if (uri != null) "${uri.scheme}://${uri.host}/" else "https://google.com/"
}

private fun String.playerOrigin(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull()
    return if (uri != null) "${uri.scheme}://${uri.host}" else "https://google.com"
}

private fun String.playerHeaders(): Map<String, String> = mapOf(
    "User-Agent" to PLAYER_USER_AGENT,
    "Referer" to playerReferer(),
    "Origin" to playerOrigin(),
    "Accept" to "*/*",
    "Accept-Language" to "en-US,en;q=0.9"
)

private const val PLAYER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildMediaItemWithSubtitles(url: String, subtitlesJson: String?): MediaItem {
    val mediaItemBuilder = MediaItem.Builder().setUri(url)
    if (subtitlesJson != null) {
        try {
            val jsonArray = org.json.JSONArray(subtitlesJson)
            val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
            for (i in 0 until jsonArray.length()) {
                val track = jsonArray.getJSONObject(i)
                val file = track.optString("file", "")
                val label = track.optString("label", "Unknown")
                val kind = track.optString("kind", "")
                if (file.isNotBlank() && file.startsWith("http")) {
                    val mimeType = if (file.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                    val langCode = when (label.trim().lowercase()) {
                        "english" -> "en"; "japanese" -> "ja"; "spanish" -> "es"
                        "french" -> "fr"; "german" -> "de"; "portuguese" -> "pt"
                        "italian" -> "it"; "korean" -> "ko"; "chinese" -> "zh"
                        "arabic" -> "ar"; "hindi" -> "hi"; "russian" -> "ru"
                        else -> label.take(2).lowercase()
                    }
                    val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(file))
                        .setMimeType(mimeType).setLanguage(langCode).setLabel(label)
                        .setSelectionFlags(if (label.lowercase().trim() == "english") C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                    subtitleConfigs.add(config)
                }
            }
            if (subtitleConfigs.isNotEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    return mediaItemBuilder.build()
}
