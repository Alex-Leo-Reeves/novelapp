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
 * The streamUrl can be either:
 *  - A direct .m3u8 / .mp4 URL → plays directly in ExoPlayer
 *  - An embed page URL (e.g. vidlink.pro/movie/123) → first scrapes the embed
 *    via a hidden WebView to extract a direct stream URL, then plays in ExoPlayer.
 *  - If scraping fails, falls back to a visible WebView showing the embed page.
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
    isAnime: Boolean,
    onBack: () -> Unit
) {
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
    // The only reason we use a WebView at all is to intercept .m3u8 video
    // streams that embed pages (VidLink, etc.) load. Once we have the .m3u8,
    // we play it in ExoPlayer for a smooth experience.
    val isDirectMedia = streamUrl.isDirectPlayableMediaUrl()
    val isWebEmbed = !isDirectMedia
    var retryKey by remember(streamUrl) { mutableStateOf(0) }

    // resolvedUrl: the URL actually played by ExoPlayer. When it's an embed
    // we try to scrape a direct .m3u8; if we fail, we null it out and show
    // a WebView fallback instead.
    var resolvedUrl by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var subtitlesJson by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var isResolving by remember(streamUrl, retryKey) { mutableStateOf(true) }
    var resolveError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    var needsWebViewFallback by remember(streamUrl, retryKey) { mutableStateOf(false) }
    var playerError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }

    // Resolve: if direct → use as-is; if embed → scrape via hidden WebView
    LaunchedEffect(streamUrl, retryKey) {
        if (isDirectMedia) {
            resolvedUrl = streamUrl
            isResolving = false
            return@LaunchedEffect
        }

        // It's an embed page — try to scrape a direct .m3u8 from it
        isResolving = true
        resolveError = null
        needsWebViewFallback = false
        
        var scraped: com.alexleoreeves.novelapp.ui.ScrapedStream? = null
        for (i in 0 until 2) { // Try up to 2 times
            scraped = extractStreamFromEmbed(context, streamUrl, timeoutMs = 25_000L)
            if (scraped != null) {
                break
            }
            if (i == 0) {
                // Wait briefly before retrying
                kotlinx.coroutines.delay(1000)
            }
        }

        if (scraped != null) {
            resolvedUrl = scraped.url
            subtitlesJson = scraped.subtitlesJson
            isResolving = false
        } else {
            // Scraping failed — will fall back to showing the embed in a visible WebView
            resolvedUrl = null
            subtitlesJson = null
            isResolving = false
            needsWebViewFallback = true
            resolveError = "Could not extract stream from embed."
        }
    }

    // ── ExoPlayer setup (keyed on resolvedUrl so it recreates when scrape completes) ──
    val exoPlayer = remember(resolvedUrl) {
        if (resolvedUrl != null) {
            val url = resolvedUrl!!
            val cache = NovelAppVideoCache.get(context)
            // Use the ORIGINAL embed URL (streamUrl) to generate headers so the CDNs get the correct Referer and Origin!
            val requestHeaders = streamUrl.playerHeaders()
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(PLAYER_USER_AGENT)
                .setDefaultRequestProperties(requestHeaders)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            // Netflix-style buffering: large background buffer, but resume quickly after rebuffer
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    60_000,  // minBufferMs (60s) — always try to stay 1 minute ahead
                    300_000, // maxBufferMs (5 mins) — buffer up to 5 mins ahead
                    1_500,   // bufferForPlaybackMs (1.5s) — start playing quickly
                    3_000    // bufferForPlaybackAfterRebufferMs (3s) — resume fast after rebuffer
                )
                .setTargetBufferBytes(150 * 1024 * 1024) // Allow up to 150MB of RAM for the buffer
                .setPrioritizeTimeOverSizeThresholds(true) // Never stop buffering just because of file size limit
                .build()
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
                .build()
                .apply {
                    val mediaItem = buildMediaItemWithSubtitles(url, subtitlesJson)
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

    // Audio codec recovery counter — the emulator's AAC decoder can timeout
    // with Error 0x80000000, so we auto-retry up to 2 times.
    var audioCodecRetries by remember(resolvedUrl, retryKey) { mutableStateOf(0) }
    val maxAudioCodecRetries = 2

    // Manual player restart trigger (used by Retry button in ExoPlayer phase).
    // This restarts the player WITHOUT re-scraping the embed URL.
    var playerRestartTrigger by remember(resolvedUrl, retryKey) { mutableStateOf(0) }

    var isPlayerBuffering by remember(resolvedUrl, retryKey) { mutableStateOf(true) }
    var playerReady by remember(resolvedUrl, retryKey) { mutableStateOf(false) }

    // Automatically retry on audio codec timeout errors (emulator issue)
    LaunchedEffect(audioCodecRetries) {
        if (audioCodecRetries in 1..maxAudioCodecRetries && exoPlayer != null) {
            delay(500L)
            exoPlayer?.stop()
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    // Manual player restart — triggered by Retry button, reuses the same resolvedUrl
    // WITHOUT going back to embed scraping. Preserves subtitle config.
    LaunchedEffect(playerRestartTrigger) {
        if (playerRestartTrigger > 0 && exoPlayer != null && resolvedUrl != null) {
            audioCodecRetries = 0
            playerError = null
            isPlayerBuffering = true
            delay(300L)
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlayer?.setMediaItem(buildMediaItemWithSubtitles(resolvedUrl!!, subtitlesJson))
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        if (exoPlayer != null) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isPlayerBuffering =
                        playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                    if (playbackState == Player.STATE_READY) playerError = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Determine if this is the 0x80000000 audio codec timeout (emulator issue)
                    val isAudioCodecTimeout = error.localizedMessage?.contains("Error 0x80000000") == true ||
                        error.cause?.toString()?.contains("0x80000000") == true ||
                        error.cause?.let { cause ->
                            if (cause is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException) {
                                cause.message?.contains("aac.decoder") == true
                            } else false
                        } == true

                    if (isAudioCodecTimeout && audioCodecRetries < maxAudioCodecRetries) {
                        playerError = "Audio codec glitch — retrying... (${audioCodecRetries + 1}/$maxAudioCodecRetries)"
                        audioCodecRetries++
                    } else {
                        playerError = error.localizedMessage ?: "Stream failed to load."
                    }
                    isPlayerBuffering = false
                }
            }
            exoPlayer.addListener(listener)
            onDispose { exoPlayer.removeListener(listener) }
        } else {
            onDispose { }
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
        if (playerError == null) {
            playerError = "Video is taking too long to load. Please click on retry."
        }
    }

    // ── UI State ──────────────────────────────────────────────────────────
    var showControls by remember { mutableStateOf(true) }
    var audioLanguage by remember { mutableStateOf("ja") }
    var subtitleMode by remember { mutableStateOf("en") }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }

    LaunchedEffect(showControls) {
        if (showControls) { delay(3000); showControls = false }
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

    // Audio language override — only for anime content (multi-track streams)
    if (isAnime) {
        LaunchedEffect(audioLanguage) {
            exoPlayer?.let { p ->
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon().setPreferredAudioLanguage(audioLanguage).build()
                
                // Count total audio tracks across all groups
                var totalAudioTracks = 0
                for (groupIndex in 0 until p.currentTracks.groups.size) {
                    if (p.currentTracks.groups[groupIndex].type == C.TRACK_TYPE_AUDIO) {
                        totalAudioTracks += p.currentTracks.groups[groupIndex].length
                    }
                }
                
                // Only override if there are multiple audio tracks available
                if (totalAudioTracks > 1) {
                    val targetLabel = when (audioLanguage) {
                        "en" -> "english"
                        "ja" -> "japanese"
                        else -> audioLanguage.lowercase()
                    }
                    for (groupIndex in 0 until p.currentTracks.groups.size) {
                        val group = p.currentTracks.groups[groupIndex]
                        if (group.type == C.TRACK_TYPE_AUDIO) {
                            for (trackIndex in 0 until group.length) {
                                val format = group.getTrackFormat(trackIndex)
                                val label = format.label?.lowercase() ?: ""
                                val lang = format.language?.lowercase() ?: ""
                                if (label.contains(targetLabel) || lang.contains(targetLabel) || lang == audioLanguage) {
                                    p.trackSelectionParameters = p.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(
                                            TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                                        )
                                        .build()
                                    return@let
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // When subtitle mode changes, enable/disable text tracks and select by label or language
    LaunchedEffect(subtitleMode, exoPlayer) {
        exoPlayer?.let { p ->
            if (subtitleMode == "off") {
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            } else {
                // Enable text tracks
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(subtitleMode)
                    .build()
                // Also try to find and select the track by label (VidLink uses labels like "English")
                val targetLabel = when (subtitleMode) {
                    "en" -> "english"
                    "ja" -> "japanese"
                    else -> subtitleMode.lowercase()
                }
                for (groupIndex in 0 until p.currentTracks.groups.size) {
                    val group = p.currentTracks.groups[groupIndex]
                    if (group.type == C.TRACK_TYPE_TEXT) {
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            val label = format.label?.lowercase() ?: ""
                            val lang = format.language?.lowercase() ?: ""
                            if (label.contains(targetLabel) || lang.contains(targetLabel) || lang == subtitleMode) {
                                p.trackSelectionParameters = p.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                                    )
                                    .build()
                                return@let
                            }
                        }
                    }
                }
            }
        }
    }

    // Auto-enable English subtitles when player first reaches READY state
    LaunchedEffect(exoPlayer, playerReady) {
        if (exoPlayer != null && playerReady && subtitleMode != "off") {
            // Small delay to let tracks populate
            delay(500)
            val p = exoPlayer
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            // Try to select English track by label
            for (groupIndex in 0 until p.currentTracks.groups.size) {
                val group = p.currentTracks.groups[groupIndex]
                if (group.type == C.TRACK_TYPE_TEXT) {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val label = format.label?.lowercase() ?: ""
                        val lang = format.language?.lowercase() ?: ""
                        if (label.contains("english") || lang == "en" || lang.contains("english")) {
                            p.trackSelectionParameters = p.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                                )
                                .build()
                            return@LaunchedEffect
                        }
                    }
                }
            }
        }
    }

    val providerName = streamUrl.providerName()

    // ── Render ────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Phase 1: Still scraping the embed for a direct stream
        if (isResolving) {
            PlayerLoadingOverlay(
                visible = true,
                title = episodeTitle,
                providerName = "Server",
                message = "Resolving high quality stream...",
                isError = false,
                onRetry = { retryKey++ },
                onBack = onBack
            )
        }
        // Phase 2: WebView fallback (scraping failed)
        else if (needsWebViewFallback) {
            var webLoading by remember(streamUrl, retryKey) { mutableStateOf(true) }
            var webError by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }

            LaunchedEffect(streamUrl, retryKey, webLoading) {
                if (webLoading) {
                    delay(25_000)
                    if (webLoading) {
                        webError = "Server is taking too long. Please click on retry."
                        webLoading = false
                    }
                }
            }

            key("webview-$retryKey") {
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
                                    if (view == null || decor == null) { callback?.onCustomViewHidden(); return }
                                    if (customView != null) { callback?.onCustomViewHidden(); return }
                                    customView = view; customViewCallback = callback
                                    decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                }
                                override fun onHideCustomView() {
                                    val decor = activity?.window?.decorView as? ViewGroup
                                    customView?.let { decor?.removeView(it) }; customView = null
                                    customViewCallback?.onCustomViewHidden(); customViewCallback = null
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { webLoading = true; webError = null }
                                override fun onPageFinished(view: WebView?, url: String?) { webLoading = false }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: ""
                                    return !url.isAllowedPlayerNavigation(streamUrl)
                                }
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val url = request?.url?.toString() ?: return null
                                    if (url.isBlockedSubResource()) {
                                        return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) { webLoading = false; webError = error?.description?.toString() ?: "Provider failed to load." }
                                }
                                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                    if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) { webLoading = false; webError = "$providerName returned ${errorResponse?.statusCode}." }
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (newProgress > 80) webLoading = false
                                }
                            }
                            loadUrl(streamUrl, streamUrl.playerHeaders())
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            PlayerLoadingOverlay(
                visible = webLoading || webError != null,
                title = episodeTitle,
                providerName = "Server",
                message = webError ?: "Resolving source...",
                isError = webError != null,
                onRetry = { webError = null; webLoading = true; retryKey++ },
                onBack = onBack
            )

            IconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
        else {
            val player = exoPlayer ?: return@Box
            var playbackSpeed by remember { mutableStateOf(1f) }
            var isSpeedLocked by remember { mutableStateOf(false) }
            var showSpeedPicker by remember { mutableStateOf(false) }

            LaunchedEffect(playbackSpeed) {
                player.playbackParameters = PlaybackParameters(playbackSpeed)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { showControls = !showControls }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.position.x > size.width * 0.6f && !isSpeedLocked) {
                                    var isLongPress = false
                                    try {
                                        withTimeout(500L) {
                                            var event = awaitPointerEvent()
                                            while (event.changes.any { it.pressed }) {
                                                event = awaitPointerEvent()
                                            }
                                        }
                                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                        isLongPress = true
                                    }

                                    if (isLongPress) {
                                        playbackSpeed = 2f
                                        var isLocked = false
                                        do {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            if (change.position.y < down.position.y - 100) {
                                                isLocked = true
                                                isSpeedLocked = true
                                                break
                                            }
                                        } while (event.changes.any { it.pressed })

                                        if (!isLocked) {
                                            playbackSpeed = 1f
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 32.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                            .clickable { 
                                isSpeedLocked = false
                                playbackSpeed = 1f
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isSpeedLocked) "2X SPEED (LOCKED)" else "2X SPEED",
                            color = currentTheme.accentColor(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                PlayerLoadingOverlay(
                    visible = isPlayerBuffering || playerError != null,
                    title = episodeTitle,
                    providerName = "NovelApp player",
                    message = playerError ?: "Buffering ahead...",
                    isError = playerError != null,
                    onRetry = { playerRestartTrigger++ },
                    onBack = onBack
                )

                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                        Row(
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                    if (isAnime) {
                                        OutlinedButton(onClick = { showAudioPicker = !showAudioPicker },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = BorderStroke(1.dp, Color.White.copy(0.5f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                            Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                            Text(if (audioLanguage == "ja") "JP Audio" else "EN Audio", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(onClick = { showSubtitlePicker = !showSubtitlePicker },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        border = BorderStroke(1.dp, Color.White.copy(0.5f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                        Icon(Icons.Default.ClosedCaption, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                        Text(when (subtitleMode) { "ja" -> "JP Subs"; "en" -> "EN Subs"; else -> "Subs Off" }, style = MaterialTheme.typography.labelSmall)
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

                AnimatedVisibility(visible = showAudioPicker, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Card(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 96.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Audio Language", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            LanguageOption1("🇯🇵  Japanese", audioLanguage == "ja") { audioLanguage = "ja"; showAudioPicker = false }
                            LanguageOption1("🇺🇸  English", audioLanguage == "en") { audioLanguage = "en"; showAudioPicker = false }
                        }
                    }
                }

                AnimatedVisibility(visible = showSubtitlePicker, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Card(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 96.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Subtitles", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            LanguageOption1("🇺🇸  English", subtitleMode == "en") { subtitleMode = "en"; showSubtitlePicker = false }
                            LanguageOption1("🇯🇵  Japanese", subtitleMode == "ja") { subtitleMode = "ja"; showSubtitlePicker = false }
                            LanguageOption1("⛔  Off", subtitleMode == "off") { subtitleMode = "off"; showSubtitlePicker = false }
                        }
                    }
                }

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

private fun String.isAllowedPlayerNavigation(initialUrl: String): Boolean {
    if (isBlank()) return false
    if (startsWith("about:", ignoreCase = true) || startsWith("data:", ignoreCase = true) || startsWith("blob:", ignoreCase = true)) return true
    val requestedHost = runCatching { Uri.parse(this).host.orEmpty().lowercase() }.getOrDefault("")
    val initialHost = runCatching { Uri.parse(initialUrl).host.orEmpty().lowercase() }.getOrDefault("")
    if (requestedHost.isBlank()) return false
    if (requestedHost == initialHost || requestedHost.endsWith(".$initialHost")) return true
    
    // Explicitly allow known video/embed providers just in case of cross-navigation
    val allowedDomains = listOf(
        "vidsrc", "nontongo", "multiembed", "streamingnow", "vidlink", 
        "youtube.com", "vimeo.com", "dailymotion.com",
        "gogoplay", "goload", "vidstreaming", "animepahe", "noobees",
        "dood", "streamwish", "filemoon", "mp4upload", "ninjastream"
    )
    if (allowedDomains.any { requestedHost.contains(it) }) return true

    // Block EVERYTHING ELSE to completely eliminate ad redirects and popups
    return false
}

private fun String.isBlockedSubResource(): Boolean {
    if (isBlank()) return false
    val requestedHost = runCatching { android.net.Uri.parse(this).host.orEmpty().lowercase() }.getOrDefault("")
    if (requestedHost.isBlank()) return false
    
    val adDomains = listOf(
        "adsco.re", "popads", "popcash", "propellerads", "exoclick", 
        "bebi.com", "doubleclick", "google-analytics", "disable-devtool",
        "googlesyndication.com", "adserver", "tracking"
    )
    return adDomains.any { requestedHost.contains(it) }
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
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isError) Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFF7A1A), modifier = Modifier.size(42.dp))
                else CircularProgressIndicator(color = Color(0xFFFF7A1A))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(providerName, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
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

private fun String.providerName(): String {
    val host = runCatching { Uri.parse(this).host.orEmpty() }.getOrDefault("").removePrefix("www.")
    return when {
        host.isBlank() -> "Embedded provider"
        "anineko" in host -> "Anineko"
        "animepahe" in host -> "AnimePahe"
        "vidlink" in host -> "VidLink"
        "nontongo" in host -> "Nontongo"
        "multiembed" in host || "streamingnow" in host -> "MultiEmbed"
        "vidsrcme" in host -> "VidSrc.me"
        "vidsrc.in" == host || host.endsWith(".vidsrc.in") -> "VidSrc.in"
        "vidsrc.to" == host || host.endsWith(".vidsrc.to") -> "VidSrc.to"
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
                    // Map VidLink labels like "English", "Japanese" to ISO 639-1 codes
                    val langCode = when (label.trim().lowercase()) {
                        "english" -> "en"
                        "japanese" -> "ja"
                        "spanish" -> "es"
                        "french" -> "fr"
                        "german" -> "de"
                        "portuguese" -> "pt"
                        "italian" -> "it"
                        "korean" -> "ko"
                        "chinese" -> "zh"
                        "arabic" -> "ar"
                        "hindi" -> "hi"
                        "russian" -> "ru"
                        else -> label.take(2).lowercase()
                    }
                    val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(file))
                        .setMimeType(mimeType)
                        .setLanguage(langCode)
                        .setLabel(label)
                        .setSelectionFlags(if (kind.contains("caption") || label.trim().lowercase() == "english") C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                    subtitleConfigs.add(config)
                }
            }
            if (subtitleConfigs.isNotEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return mediaItemBuilder.build()
}

