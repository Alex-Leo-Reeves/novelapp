package com.alexleoreeves.novelapp.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.io.File
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

    val isDirectMedia = streamUrl.isDirectPlayableMediaUrl()
    val isWebEmbed = streamUrl.startsWith("http", ignoreCase = true) && !isDirectMedia
    var retryKey by remember(streamUrl) { mutableStateOf(0) }
    var playerError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var isPlayerBuffering by remember(streamUrl, retryKey) { mutableStateOf(true) }

    // ── ExoPlayer setup ───────────────────────────────────────────────────
    val exoPlayer = remember(streamUrl, retryKey) {
        if (!isWebEmbed || isDirectMedia) {
            val cache = NovelAppVideoCache.get(context)
            val requestHeaders = streamUrl.playerHeaders()
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(PLAYER_USER_AGENT)
                .setDefaultRequestProperties(requestHeaders)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,
                    90_000,
                    1_500,
                    4_000
                )
                .build()
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
                .build()
                .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                if (initialPositionMs > 0L) seekTo(initialPositionMs)
                playWhenReady = true
            }
        } else {
            null
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isPlayerBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                    if (playbackState == Player.STATE_READY) playerError = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    playerError = error.localizedMessage ?: "Stream failed to load."
                    isPlayerBuffering = false
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
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

    // Apply audio language to ExoPlayer
    LaunchedEffect(audioLanguage) {
        exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage(audioLanguage)
                .build()
        }
    }

    // Apply subtitle track to ExoPlayer
    LaunchedEffect(subtitleMode) {
        exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
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
    }

    if (isWebEmbed) {
        var webLoading by remember(streamUrl, retryKey) { mutableStateOf(true) }
        var webError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
        val providerName = streamUrl.providerName()

        LaunchedEffect(streamUrl, retryKey, previewLimitMs) {
            val limit = previewLimitMs ?: return@LaunchedEffect
            delay(limit)
            onPreviewFinished()
        }

        LaunchedEffect(streamUrl, retryKey, webLoading) {
            if (webLoading) {
                delay(18_000)
                if (webLoading) {
                    webError = "$providerName is taking too long to respond. Try another provider or episode."
                    webLoading = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            key(retryKey) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportMultipleWindows(false)
                                javaScriptCanOpenWindowsAutomatically = true
                                loadsImagesAutomatically = true
                                allowContentAccess = true
                                allowFileAccess = false
                                userAgentString = PLAYER_USER_AGENT
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            }
                            webChromeClient = object : WebChromeClient() {
                                private var customView: View? = null
                                private var customViewCallback: CustomViewCallback? = null

                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    val decor = activity?.window?.decorView as? ViewGroup
                                    if (view == null || decor == null) {
                                        callback?.onCustomViewHidden()
                                        return
                                    }
                                    if (customView != null) {
                                        callback?.onCustomViewHidden()
                                        return
                                    }
                                    customView = view
                                    customViewCallback = callback
                                    decor.addView(
                                        view,
                                        ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                }

                                override fun onHideCustomView() {
                                    val decor = activity?.window?.decorView as? ViewGroup
                                    customView?.let { decor?.removeView(it) }
                                    customView = null
                                    customViewCallback?.onCustomViewHidden()
                                    customViewCallback = null
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    webLoading = true
                                    webError = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    webLoading = false
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: ""
                                    return !url.isAllowedPlayerNavigation(streamUrl)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        webLoading = false
                                        webError = error?.description?.toString() ?: "Provider failed to load."
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                                        webLoading = false
                                        webError = "$providerName returned ${errorResponse?.statusCode ?: "an error"}."
                                    }
                                }
                            }
                            loadUrl(
                                streamUrl,
                                streamUrl.playerHeaders()
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            PlayerLoadingOverlay(
                visible = webLoading || webError != null,
                title = episodeTitle,
                providerName = providerName,
                message = webError ?: "Loading secure player...",
                isError = webError != null,
                onRetry = {
                    webError = null
                    webLoading = true
                    retryKey++
                },
                onBack = onBack
            )

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    } else {
        val player = exoPlayer ?: return
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
                    this.player = player
                    useController = false   // We use our own custom controls
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        PlayerLoadingOverlay(
            visible = isPlayerBuffering || playerError != null,
            title = episodeTitle,
            providerName = "NovelApp player",
            message = playerError ?: "Buffering ahead...",
            isError = playerError != null,
            onRetry = {
                playerError = null
                isPlayerBuffering = true
                retryKey++
            },
            onBack = onBack
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
                            player.seekTo(maxOf(0L, player.currentPosition - 10_000L))
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
                    val isPlaying by produceState(initialValue = player.isPlaying) {
                        while (true) {
                            value = player.isPlaying
                            delay(300)
                        }
                    }
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
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
                            player.seekTo(player.currentPosition + 10_000L)
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
                            value = player.currentPosition
                            delay(500)
                        }
                    }
                    val duration = player.duration.takeIf { it > 0 } ?: 1L
                    Slider(
                        value = position.toFloat() / duration.toFloat(),
                        onValueChange = { pct ->
                            player.seekTo((pct * duration).toLong())
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

private fun String.isDirectPlayableMediaUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
        clean.endsWith(".mkv") ||
        clean.endsWith(".mov") ||
        !startsWith("http", ignoreCase = true)
}

private fun String.isAllowedPlayerNavigation(initialUrl: String): Boolean {
    if (isBlank()) return false
    if (startsWith("about:", ignoreCase = true) || startsWith("data:", ignoreCase = true) || startsWith("blob:", ignoreCase = true)) {
        return true
    }
    val requestedHost = runCatching { Uri.parse(this).host.orEmpty().lowercase() }.getOrDefault("")
    val initialHost = runCatching { Uri.parse(initialUrl).host.orEmpty().lowercase() }.getOrDefault("")
    if (requestedHost.isBlank()) return false
    if (requestedHost == initialHost || requestedHost.endsWith(".$initialHost")) return true

    // Block obvious ad/tracker domains
    val blockedDomains = listOf("doubleclick.net", "googleadservices.com", "pagead2", "popads", "popcash")
    if (blockedDomains.any { requestedHost.contains(it) }) return false

    // Allow all streaming, embed, and player domains
    return listOf(
        "anineko", "anizara", "vivibebe", "bibiemb", "otakuhg", "otakuvid", "playmogo",
        "kwik", "dood", "vidsrc", "vidlink", "autoembed", "stream", "embed", "tmdb", "themoviedb",
        "kisskh", "dramacool", "kimcartoon", "fastani", "vidplay", "filemoon",
        "rapidvideo", "voe", "cloudflare", "jwpcdn", "jwplatform", "cdnjs",
        "bootstrapcdn", "googleapis", "gstatic",
        "font", "ajax.googleapis", "static", "cdn", "player", "media",
        "video", "m3u8", "hls", "mp4", "aniwatch", "zoro", "anime"
    ).any { requestedHost.contains(it) }
}

@Composable
private fun PlayerLoadingOverlay(
    visible: Boolean,
    title: String,
    providerName: String,
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isError) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = Color(0xFFFF7A1A),
                        modifier = Modifier.size(42.dp)
                    )
                } else {
                    CircularProgressIndicator(color = Color(0xFFFF7A1A))
                }
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    providerName,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    message,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onBack,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Back")
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7A1A))
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private object NovelAppVideoCache {
    private var cache: SimpleCache? = null

    fun get(context: android.content.Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, "video-cache"),
                LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
                StandaloneDatabaseProvider(context)
            ).also { cache = it }
        }
    }
}

private fun String.providerName(): String {
    val host = runCatching { Uri.parse(this).host.orEmpty() }.getOrDefault("")
        .removePrefix("www.")
    return when {
        host.isBlank() -> "Embedded provider"
        "anineko" in host -> "Anineko"
        "animepahe" in host -> "AnimePahe"
        "vidlink" in host -> "VidLink"
        "autoembed" in host -> "AutoEmbed"
        "vidsrc" in host -> "VidSrc"
        "embed" in host -> "Embed provider"
        "dramacool" in host -> "DramaCool"
        "kisskh" in host -> "KissKH"
        "kimcartoon" in host -> "KimCartoon"
        else -> host
    }
}

private fun String.playerReferer(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull()
    val scheme = uri?.scheme ?: "https"
    val host = uri?.host ?: return "https://vidlink.pro/"
    return "$scheme://$host/"
}

private fun String.playerOrigin(): String {
    val uri = runCatching { Uri.parse(this) }.getOrNull()
    val scheme = uri?.scheme ?: "https"
    val host = uri?.host ?: return "https://vidlink.pro"
    return "$scheme://$host"
}

private fun String.playerHeaders(): Map<String, String> =
    mapOf(
        "User-Agent" to PLAYER_USER_AGENT,
        "Referer" to playerReferer(),
        "Origin" to playerOrigin(),
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

private const val PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
