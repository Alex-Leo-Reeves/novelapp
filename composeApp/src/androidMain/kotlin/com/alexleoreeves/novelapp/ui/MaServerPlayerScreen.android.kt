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
import java.io.ByteArrayInputStream

/**
 * Android actual: Full-screen WebView embed player for MA Server.
 * Loads the embed URL with:
 * - JavaScript enabled
 * - Aggressive ad/tracker blocking (via shouldInterceptRequest)
 * - External redirect blocking (keep user inside the embed domain)
 * - Inline video playback (prevents fullscreen freeze)
 * - Auto-play after page load
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

    var isLoading by remember { mutableStateOf(true) }
    var currentTitle by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(embedUrl) {
        currentTitle = episodeTitle
        hasError = false
        isLoading = true
    }

    // Extract the original embed domain so we can block redirects away from it
    val embedOrigin = remember(embedUrl) {
        runCatching {
            val uri = android.net.Uri.parse(embedUrl.trim())
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: "https://vidlink.pro"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
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

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val lowerUrl = url.lowercase()

                            // Block external redirects — keep user inside the embed domain
                            // VidFast, Videasy, 111Movies all try to redirect to ad/promotional pages
                            if (!lowerUrl.startsWith(embedOrigin.lowercase())) {
                                // Allow vidlink.pro subdomains and CDNs needed for playback
                                val allowedExternal = listOf(
                                    "vidlink.pro",
                                    "googlevideo.com",
                                    "youtube.com",
                                    "ytimg.com",
                                    "vimeo.com",
                                    "vimeocdn.com",
                                    "cloudflare.com",
                                    "cloudfront.net",
                                    "akamaihd.net",
                                    "amazonaws.com",
                                    "m3u8",
                                    ".m3u8",
                                    ".mp4"
                                )
                                val isAllowed = allowedExternal.any { lowerUrl.contains(it) }
                                if (!isAllowed && !lowerUrl.startsWith(embedOrigin.lowercase())) {
                                    // Block the redirect — this prevents ad/promotional pages from loading
                                    return true
                                }
                            }
                            return false // Allow navigation within the embed domain
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            hasError = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            // Force all videos to play inline to prevent fullscreen freeze
                            view?.evaluateJavascript("""
                                (function() {
                                    // Force all existing and future videos to play inline
                                    function forceInline() {
                                        const videos = document.querySelectorAll('video');
                                        videos.forEach(v => {
                                            v.setAttribute('playsinline', '');
                                            v.setAttribute('webkit-playsinline', '');
                                            v.setAttribute('x-webkit-airplay', 'allow');
                                            v.muted = true;
                                            v.play().catch(() => {});
                                            setTimeout(() => { v.muted = false; }, 200);
                                        });
                                    }
                                    forceInline();
                                    
                                    // Observer for dynamically added videos
                                    const observer = new MutationObserver(() => forceInline());
                                    observer.observe(document.body, { childList: true, subtree: true });
                                    
                                    // Click any play buttons
                                    const playSelectors = [
                                        '.play-button', '.jw-icon-display', '.vjs-big-play-button',
                                        '#start', '.plyr__control--overlaid', 'button[aria-label="Play"]',
                                        '.play-btn', '.btn-play', '[id*="play"]', '[class*="play"]',
                                        'video', '.video-js', '[data-player]'
                                    ];
                                    playSelectors.forEach(sel => {
                                        try {
                                            const el = document.querySelector(sel);
                                            if (el && el.tagName !== 'VIDEO') el.click();
                                        } catch(e) {}
                                    });
                                    
                                    // Click center to dismiss overlays
                                    setTimeout(() => {
                                        document.elementFromPoint(window.innerWidth/2, window.innerHeight/2)?.click();
                                    }, 500);
                                })();
                            """.trimIndent(), null)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            val lowerUrl = url.lowercase()

                            // ── AD BLOCKING ────────────────────────────────
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
                                "analytics.", "track.",
                                // Block known redirect/tracker patterns
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

                            // Block popup-like URL patterns that are off-domain
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
                            isLoading = false
                            if (request?.isForMainFrame == true) hasError = true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            // Do NOT call super — this prevents the native fullscreen overlay
                            // that causes the freeze. Instead videos play inline via playsinline attr.
                            // If the callback is not invoked, the video element may stay in fullscreen
                            // mode. We invoke it immediately to keep video inline.
                            callback?.onCustomViewHidden()
                        }

                        override fun onHideCustomView() {
                            // No-op: we never show custom view, so nothing to hide
                        }
                    }

                    loadUrl(embedUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Loading indicator ────────────────────────────────────────────
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp)
                    .align(Alignment.TopCenter)
            ) {
                LinearProgressIndicator(
                    progress = { 0.5f },
                    modifier = Modifier.fillMaxWidth(),
                    color = currentTheme.accentColor(),
                    trackColor = Color.Transparent
                )
            }
        }

        // ── Error overlay ────────────────────────────────────────────────
        if (hasError) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        // ── Back button overlay ──────────────────────────────────────────
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

        // ── Title overlay ────────────────────────────────────────────────
        if (currentTitle.isNotBlank()) {
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

    // Immersive mode
    DisposableEffect(Unit) {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val ctrl = WindowInsetsControllerCompat(window, window.decorView)
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
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
