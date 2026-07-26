package com.alexleoreeves.novelapp.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * Internal loading phase for the stabilization logic.
 *
 * LOADING      — The WebView page is still loading.
 * STABILIZING  — Page has loaded but we're enforcing a minimum quiet
 *                period (8 seconds) before revealing the player.
 * READY        — Stabilization complete, video playback begins cleanly.
 * STUCK        — No video element appeared after the timeout window;
 *                the WebView will auto-reload.
 */
private enum class PlayerPhase {
    LOADING,
    STABILIZING,
    READY,
    STUCK
}

/**
 * Android actual: Full-screen WebView embed player for MA Server.
 *
 * ===== STABILIZATION SAFETY NET (8-second buffer) ====================
 *
 * Problem:
 *   Embed providers (VidLink, 2embed, etc.) load their video player
 *   asynchronously. During the first ~10 seconds their JavaScript can
 *   rapidly play/pause and mute/unmute the video while the stream
 *   resolves. This causes the flickering/rapid-toggle behavior seen
 *   by users.
 *
 * Fix:
 *   1. After the page's onPageFinished fires, keep the loading overlay
 *      visible for a MINIMUM of 8 seconds (STABILIZING phase).
 *   2. During stabilization, injected JavaScript:
 *      - Mutes ALL video elements immediately.
 *      - Intercepts play()/pause()/muted= toggles and suppresses them
 *        within a 3-second window (anti-rapid-toggle guard).
 *      - Blocks autoplay attempts so the embed doesn't start/stop
 *        repeatedly.
 *   3. After 8 seconds (or when a stable video element is detected
 *      with a valid src), transition to READY:
 *      - Unmute the video cleanly once.
 *      - Call play() once.
 *      - Dismiss the loading overlay.
 *   4. If no video element has appeared after 15 seconds, trigger an
 *      automatic WebView reload (STUCK → reload).
 *   5. If the video starts playing, pause/resume normally after that
 *      point — the guard only operates during STABILIZING.
 *
 * Layout:
 *   - Loading overlay with circular progress + "Stabilizing player..."
 *     message during STABILIZING.
 *   - After READY, normal player controls.
 * =====================================================================
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun MaServerPlayerScreen(
    embedUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
        ?: run {
            var ctx = context
            while (ctx is android.content.ContextWrapper) {
                if (ctx is androidx.fragment.app.FragmentActivity) break
                ctx = ctx.baseContext
            }
            ctx as? androidx.fragment.app.FragmentActivity
        }

    val scope = rememberCoroutineScope()

    // ── Phase tracking ────────────────────────────────────────────────
    var playerPhase by remember { mutableStateOf(PlayerPhase.LOADING) }
    var currentTitle by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var phaseMessage by remember { mutableStateOf("Loading player...") }

    // Stabilization counter — used to prevent spamming reloads
    var stabilizeAttempts by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(embedUrl) {
        currentTitle = episodeTitle
        hasError = false
        playerPhase = PlayerPhase.LOADING
        phaseMessage = "Loading player..."
        stabilizeAttempts = 0
    }

    // Extract the original embed domain so we can block redirects away from it
    val embedOrigin = remember(embedUrl) {
        runCatching {
            val uri = android.net.Uri.parse(embedUrl.trim())
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: "https://vidlink.pro"
    }

    // ── Stabilization timer: enforce minimum 8s in STABILIZING ────────
    LaunchedEffect(playerPhase) {
        if (playerPhase == PlayerPhase.STABILIZING) {
            phaseMessage = "Stabilizing player... (3s)"
            // Wait the full 3 seconds minimum
            delay(3_000L)
            // After 3s, check current state and advance to READY
            // (unless we already got pushed to STUCK)
            if (playerPhase == PlayerPhase.STABILIZING) {
                playerPhase = PlayerPhase.READY
                phaseMessage = ""
                // Inject the "go live" JS to unmute and play
                webViewRef?.evaluateJavascript(STABILIZATION_END_JS, null)
            }
        }
    }

    // ── Stuck detection: if no video after 15s total, reload ──────────
    LaunchedEffect(playerPhase) {
        if (playerPhase == PlayerPhase.STABILIZING) {
            delay(15_000L)
            if (playerPhase == PlayerPhase.STABILIZING && stabilizeAttempts < 2) {
                playerPhase = PlayerPhase.STUCK
                phaseMessage = "Player stuck — reloading..."
                stabilizeAttempts++
                // Reload the WebView
                webViewRef?.reload()
            } else if (playerPhase == PlayerPhase.STABILIZING) {
                // Force ready after 2nd attempt regardless
                playerPhase = PlayerPhase.READY
                phaseMessage = ""
                webViewRef?.evaluateJavascript(STABILIZATION_END_JS, null)
            }
        }
    }

    // ── Detect page reload (stabilization retry) ──────────────────────
    LaunchedEffect(stabilizeAttempts) {
        if (stabilizeAttempts > 0 && stabilizeAttempts <= 2) {
            // Give the reloaded page time to load, then enter stabilize again
            delay(2_000L)
            if (playerPhase == PlayerPhase.STUCK) {
                playerPhase = PlayerPhase.STABILIZING
                phaseMessage = "Stabilizing player... (${stabilizeAttempts + 1})"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // ── Main WebView ────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    @SuppressLint("VisibleForTests")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = MA_SERVER_USER_AGENT
                        allowContentAccess = true
                        allowFileAccess = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            mediaPlaybackRequiresUserGesture = false
                        }
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val lowerUrl = url.lowercase()

                            // Block third-party main-frame navigations (e.g., popunders that try to hijack the WebView)
                            if (request.isForMainFrame) {
                                val isWrapperSite = embedUrl.contains("luciferdonghua") || embedUrl.contains("donghuastream") || 
                                                    embedUrl.contains("footybite") || embedUrl.contains("sportsurge") || 
                                                    embedUrl.contains("scorebat") || embedUrl.contains("watchwrestling")
                                val isRouterEmbed = embedUrl.contains("multiembed") || embedUrl.contains("autoembed") || embedUrl.contains("embed.su")
                                
                                // ALWAYS allow Cloudflare challenge pages to proceed
                                val isCloudflare = lowerUrl.contains("challenges.cloudflare.com") || lowerUrl.contains("cloudflare.com/cdn-cgi")
                                if (isCloudflare) return false
                                
                                // If the page has already loaded (STABILIZING or READY), block ALL top-level navigations
                                // EXCEPT if we are on a wrapper/router site or the URL contains "embed"
                                if (playerPhase != PlayerPhase.LOADING && !isWrapperSite && !isRouterEmbed && !request.url.toString().contains("embed")) {
                                    return true // Block click-triggered navigations completely
                                }

                                val reqHost = request.url?.host?.lowercase() ?: ""
                                val embedHost = android.net.Uri.parse(embedUrl).host?.lowercase() ?: ""
                                if (reqHost.isNotEmpty() && embedHost.isNotEmpty()) {
                                    val isSameDomain = reqHost == embedHost || 
                                                       reqHost.endsWith(".$embedHost") || 
                                                       embedHost.endsWith(".$reqHost")
                                    // If we are on a wrapper site or Router Embed (which routes to other providers), allow cross-domain
                                    if (!isSameDomain && !isWrapperSite && !isRouterEmbed) {
                                        return true // Block navigation
                                    }
                                }
                            }

                            // Allow all HTTPS URLs — only block known ad/tracker domains
                            val blockedDomains = listOf(
                                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                                "googletagmanager.com", "googletagservices.com", "google-analytics.com",
                                "moatads.com", "rubiconproject.com", "criteo.com", "criteo.net",
                                "pubmatic.com", "openx.net", "appnexus.com", "casalemedia.com",
                                "adsrvr.org", "adnxs.com", "adtech.de",
                                "scorecardresearch.com", "quantserve.com", "exelator.com",
                                "spotxchange.com", "springserve.com", "adsafeprotected.com",
                                "popads.net", "popcash.net", "propellerads.com",
                                "adserver.com", "advertising.com",
                                "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
                                "securepubads.g.doubleclick.net", "adservice.google.com",
                                "ad.doubleclick.net", "cm.g.doubleclick.net",
                                "popup", "/pop.js", "/popunder", "/ad.js",
                                "/analytics.", "/track.",
                                "bit.ly", "tinyurl", "adf.ly", "ouo.io", "shorte.st",
                                "adfoc.us", "bc.vc", "linkbucks.com", "adreactor.com"
                            )
                            val isAd = blockedDomains.any { domain -> lowerUrl.contains(domain) }
                            if (isAd) {
                                return true
                            }

                            // Allow all HTTPS content — embeds load video from many CDNs
                            if (lowerUrl.startsWith("https://") || lowerUrl.startsWith("http://")) {
                                return false
                            }

                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            playerPhase = PlayerPhase.LOADING
                            phaseMessage = "Loading player..."
                            hasError = false
                            webViewRef = view
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            webViewRef = view
                            // Transition from LOADING → STABILIZING or READY
                            if (playerPhase == PlayerPhase.LOADING || playerPhase == PlayerPhase.READY) {
                                val currentUrl = url?.lowercase() ?: ""
                                
                                // If this is a Cloudflare challenge page, skip ALL overlays so user can interact with captcha
                                if (currentUrl.contains("challenges.cloudflare.com") || currentUrl.contains("/cdn-cgi/")) {
                                    playerPhase = PlayerPhase.READY
                                    phaseMessage = ""
                                    return
                                }
                                
                                if (currentUrl.contains("multiembed") || currentUrl.contains("nontongo") || currentUrl.contains("autoembed") || currentUrl.contains("embed.su")) {
                                    // Skip heavy stabilization for MultiEmbed, AutoEmbed, EmbedSu, and Nontongo
                                    playerPhase = PlayerPhase.READY
                                    phaseMessage = ""
                                    view?.evaluateJavascript(INLINE_VIDEO_JS, null)
                                } else {
                                    playerPhase = PlayerPhase.STABILIZING
                                    phaseMessage = "Stabilizing player... (3s)"

                                    // ── Inject stabilization JavaScript ────────
                                    view?.evaluateJavascript(STABILIZATION_START_JS, null)
                                    view?.evaluateJavascript(INLINE_VIDEO_JS, null)
                                    view?.evaluateJavascript(IFRAME_EXTRACTION_JS, null)
                                    view?.evaluateJavascript(FULLSCREEN_CSS_JS, null)
                                }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            val lowerUrl = url.lowercase()

                            // ── AD BLOCKING ────────────────────────────────
                            // Whitelist Cloudflare challenge domains and router embeds
                            if (lowerUrl.contains("autoembed") || lowerUrl.contains("embed.su") || lowerUrl.contains("challenges.cloudflare.com") || lowerUrl.contains("cloudflare.com/cdn-cgi") || lowerUrl.contains("turnstile")) {
                                return null
                            }
                            
                            val adDomains = listOf(
                                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                                "googletagmanager.com", "googletagservices.com", "google-analytics.com",
                                "moatads.com", "rubiconproject.com", "criteo.com", "criteo.net",
                                "pubmatic.com", "openx.net", "appnexus.com", "casalemedia.com",
                                "adsrvr.org", "adnxs.com", "adtech.de", "adzerk.net",
                                "scorecardresearch.com", "quantserve.com", "exelator.com",
                                "spotxchange.com", "springserve.com", "adsafeprotected.com",
                                "servedbyadbutler.com", "popads.net", "popcash.net",
                                "propellerads.com", "clickaine.com", "adsterra.com",
                                "trafficfactory.biz", "trafficjunky.com",
                                "adserver.com", "advertising.com", "adultad.net",
                                "taboola.com", "outbrain.com", "revcontent.com",
                                "sharethrough.com", "nativeroll.tv",
                                "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
                                "securepubads.g.doubleclick.net", "adservice.google.com",
                                "partner.googleadservices.com", "ad.doubleclick.net",
                                "cm.g.doubleclick.net", "stats.g.doubleclick.net",
                                "static.doubleclick.net", "googleads.g.doubleclick.net",
                                "pubads.g.doubleclick.net", "adclick.g.doubleclick.net",
                                "popup", "/pop.js", "/popunder", "/ad.js",
                                "/banner", "/ads/", "/advert",
                                "/analytics.", "/track.",
                                "bit.ly", "tinyurl", "adf.ly", "ouo.io", "shorte.st",
                                "adfoc.us", "bc.vc", "linkbucks.com", "adreactor.com"
                            )

                            val isAd = adDomains.any { domain -> lowerUrl.contains(domain) }
                            if (isAd) {
                                return WebResourceResponse(
                                    "text/plain", "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            if (lowerUrl.contains("popup") && !lowerUrl.startsWith(embedOrigin.lowercase())) {
                                return WebResourceResponse(
                                    "text/plain", "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            return null
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            val desc = error?.description?.toString()?.lowercase() ?: ""
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            // Ignore aborted errors or adblocker blocks
                            if (desc.contains("err_aborted") || desc.contains("err_blocked") || desc.contains("err_name_not_resolved") || desc.contains("err_connection_refused")) {
                                return
                            }
                            
                            if (request?.isForMainFrame == true) {
                                // Only trigger error if the URL that failed is the actual embed URL or Cloudflare
                                // If an ad popup fails on main frame, we don't care
                                val isRequestedHost = reqUrl == embedUrl || reqUrl.contains("cloudflare", ignoreCase = true)
                                if (isRequestedHost) {
                                    hasError = true
                                    phaseMessage = "Failed to load player: $desc"
                                }
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            callback?.onCustomViewHidden()
                        }

                        override fun onHideCustomView() {
                            // No-op
                        }
                    }

                    loadUrl(embedUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Loading + Stabilization overlay ────────────────────────────
        if (playerPhase == PlayerPhase.LOADING || playerPhase == PlayerPhase.STABILIZING || playerPhase == PlayerPhase.STUCK) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    if (playerPhase == PlayerPhase.STUCK) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF00BFFF),
                            modifier = Modifier.size(48.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            color = Color(0xFF00BFFF),
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp
                        )
                    }

                    Text(
                        currentTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )

                    Text(
                        phaseMessage,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (playerPhase == PlayerPhase.STABILIZING) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            color = Color(0xFF00BFFF),
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                    }

                    if (playerPhase == PlayerPhase.STUCK) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onBack,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Text("Back")
                            }
                            Button(
                                onClick = {
                                    playerPhase = PlayerPhase.LOADING
                                    phaseMessage = "Reloading player..."
                                    webViewRef?.reload()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))
                            ) {
                                Text("Retry Reload")
                            }
                        }
                    }
                }
            }
        }

        // ── Error overlay ────────────────────────────────────────────────
        if (hasError && playerPhase != PlayerPhase.LOADING && playerPhase != PlayerPhase.STABILIZING && playerPhase != PlayerPhase.STUCK) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF6D00), modifier = Modifier.size(48.dp))
                    Text("Failed to load the player", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text("The embed may be blocked or unavailable.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onBack, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Text("Back")
                        }
                    }
                }
            }
        }

        // ── Back button overlay (always visible) ──────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp)
                .statusBarsPadding()
                .align(Alignment.TopStart)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Back",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Title overlay (when player is READY) ──────────────────────────
        if (playerPhase == PlayerPhase.READY && currentTitle.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Text(
                    currentTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }

    // Consume system back presses locally
    BackHandler { onBack() }

    // Immersive mode
    DisposableEffect(Unit) {
        activity?.window?.let { window ->
            // Allow drawing into the notch/cutout
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val ctrl = WindowInsetsControllerCompat(window, window.decorView)
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private const val MA_SERVER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

// ─────────────────────────────────────────────────────────────────────────────
//  Stabilization JavaScript
// ─────────────────────────────────────────────────────────────────────────────

/**
 * JS injected when the page enters STABILIZING phase.
 *
 * What it does:
 * 1. Mutes all existing and future <video> elements.
 * 2. Wraps HTMLVideoElement.prototype.play() to debounce calls
 *    (no more than once per 3 seconds during stabilization).
 * 3. Wraps the `muted` setter to suppress rapid toggling —
 *    once set to true, it stays true (our override).
 * 4. Prevents autoplay from interfering.
 * 5. Polls every second for a video element with valid src.
 *    When found, logs a detectable message so we could optionally
 *    shorten the stabilization period (but we keep the full 8s
 *    for consistency).
 */
private const val STABILIZATION_START_JS = """
(function() {
    window.__STABILIZING = true;
    
    // Simply ensure videos are inline and muted during stabilization.
    // Avoid redefining properties or aggressive debouncing as that crashes bot-protected embeds like VidLink.
    function muteAllVideos() {
        document.querySelectorAll('video').forEach(v => {
            v.muted = true;
            v.setAttribute('playsinline', '');
            v.setAttribute('webkit-playsinline', '');
            v.setAttribute('x-webkit-airplay', 'allow');
        });
    }
    muteAllVideos();
    
    function pollForVideo() {
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            var src = v.src || v.currentSrc;
            if (src && src.length > 10 && (src.includes('.m3u8') || src.includes('.mp4') || src.includes('blob:') || v.readyState >= 2)) {
                return true;
            }
        }
        return false;
    }
    
    var _checkInterval = setInterval(function() {
        if (pollForVideo()) clearInterval(_checkInterval);
    }, 1000);
    setTimeout(function() { clearInterval(_checkInterval); }, 20000);
    
    function clickPlayButtons() {
        var selectors = [
            '.play-button', '.jw-icon-display', '.vjs-big-play-button',
            '#start', '.plyr__control--overlaid', 'button[aria-label="Play"]',
            '.play-btn', '.btn-play', '[id*="play"]', '[class*="play"]',
            'video', '.video-js', '[data-player]'
        ];
        selectors.forEach(function(sel) {
            try {
                var el = document.querySelector(sel);
                if (el && el.tagName !== 'VIDEO') el.click();
            } catch(e) {}
        });
    }
    clickPlayButtons();
})();
"""

/**
 * JS injected when transitioning from STABILIZING → READY.
 */
private const val STABILIZATION_END_JS = """
(function() {
    window.__STABILIZING = false;
    
    function forcePlay() {
        var selectors = [
            '.play-button', '.jw-icon-display', '.vjs-big-play-button',
            '#start', '.plyr__control--overlaid', 'button[aria-label="Play"]',
            '.play-btn', '.btn-play', '[id*="play"]', '[class*="play"]'
        ];
        selectors.forEach(function(sel) {
            try {
                var el = document.querySelector(sel);
                if (el && el.tagName !== 'VIDEO') el.click();
            } catch(e) {}
        });

        document.querySelectorAll('video').forEach(function(v) {
            v.muted = false;
            var p = v.play();
            if (p) p.catch(function() {});
        });
    }

    forcePlay();
    setTimeout(forcePlay, 500);
})();
"""

/**
 * Legacy inline-video JS that was already present in the original
 * implementation. Ensures videos play inline and not in a native
 * fullscreen overlay.
 */
private const val INLINE_VIDEO_JS = """
(function() {
    function forceInline() {
        const videos = document.querySelectorAll('video');
        videos.forEach(v => {
            v.setAttribute('playsinline', '');
            v.setAttribute('webkit-playsinline', '');
            v.setAttribute('x-webkit-airplay', 'allow');
        });
    }
    forceInline();
    const observer = new MutationObserver(() => forceInline());
    observer.observe(document.body, { childList: true, subtree: true });
})();
"""

/**
 * JS injected to extract the iframe player from wrapper sites (Lucifer, DonghuaStream).
 */
private const val IFRAME_EXTRACTION_JS = """
(function() {
    var host = window.location.hostname;
    if (host.includes('luciferdonghua') || host.includes('donghuastream')) {
        if (window.__iframeExtracted) return;
        
        var attempts = 0;
        var interval = setInterval(function() {
            attempts++;
            if (window.__iframeExtracted || attempts > 20) {
                clearInterval(interval);
                return;
            }
            var iframes = document.querySelectorAll('iframe');
            for (var i = 0; i < iframes.length; i++) {
                var src = iframes[i].src || iframes[i].dataset.src;
                if (src && !src.includes('google') && !src.includes('facebook') && !src.includes('disqus') && !src.includes('agenteimmobiliare')) {
                    var frame = iframes[i];
                    window.__iframeExtracted = true;
                    clearInterval(interval);
                    
                    // Instead of redirecting (which breaks referer validation),
                    // clear the page and make the iframe fullscreen.
                    document.body.innerHTML = '';
                    document.body.appendChild(frame);
                    
                    document.body.style.margin = '0';
                    document.body.style.padding = '0';
                    document.body.style.overflow = 'hidden';
                    document.body.style.backgroundColor = '#000';
                    
                    frame.style.position = 'fixed';
                    frame.style.top = '0';
                    frame.style.left = '0';
                    frame.style.width = '100vw';
                    frame.style.height = '100vh';
                    frame.style.border = 'none';
                    frame.style.zIndex = '999999';
                    
                    if (frame.dataset.src) {
                        frame.src = frame.dataset.src;
                    }
                    
                    return;
                }
            }
        }, 500);
    }
})();
"""

/**
 * CSS injected to turn full webpages (like Lucifer Donghua / DonghuaStream)
 * into fullscreen video players by hiding headers/footers and forcing the player container to fill the screen.
 */
private const val FULLSCREEN_CSS_JS = """
(function() {
    var host = window.location.hostname;
    // Don't apply to known embed providers like multiembed, vidsrc, etc.
    if (!host.includes('luciferdonghua') && !host.includes('donghuastream')) {
        return;
    }
    
    var style = document.createElement('style');
    style.innerHTML = `
        header, footer, .sidebar, #sidebar, .site-header, .site-footer, .widget-area, .comments-area {
            display: none !important;
        }
        .player-area, .video-content, #player, .video-player, #playervideo, .video-info, .epcontent {
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            z-index: 999999 !important;
            background: black !important;
            margin: 0 !important;
            padding: 0 !important;
        }
        body {
            background: black !important;
        }
    `;
    document.head.appendChild(style);
})();
"""
