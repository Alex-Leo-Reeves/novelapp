package com.alexleoreeves.novelapp.tv.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

enum class PlayerPhase {
    LOADING,
    STABILIZING,
    READY,
    STUCK
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvEmbedPlayer(
    embedUrl: String,
    episodeTitle: String,
    onWebViewCreated: (WebView) -> Unit,
    onBack: () -> Unit
) {
    var playerPhase by remember { mutableStateOf(PlayerPhase.LOADING) }
    var hasError by remember { mutableStateOf(false) }
    var phaseMessage by remember { mutableStateOf("Loading player...") }
    var stabilizeAttempts by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val embedOrigin = remember(embedUrl) {
        runCatching {
            val uri = android.net.Uri.parse(embedUrl.trim())
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: "https://vidlink.pro"
    }

    // Back-to-back binge fix: the AndroidView factory runs ONCE, so a changed
    // embedUrl (auto-next / NEXT / PREV) must explicitly reload the SAME
    // WebView, otherwise the previous episode's page stays on screen.
    // State resets here happen for both the first load and every episode swap;
    // the reload below is skipped on the first composition (webViewRef is null
    // until the factory runs anyway).
    LaunchedEffect(embedUrl) {
        hasError = false
        playerPhase = PlayerPhase.LOADING
        phaseMessage = "Loading player..."
        stabilizeAttempts = 0
        if (webViewRef != null) {
            loadEmbedContent(webViewRef, embedUrl)
        }
    }

    // Stabilization timer: enforce minimum 3s in STABILIZING.
    // On READY we match the Android player (MaServerPlayerScreen) exactly:
    // STABILIZATION_END_JS unmutes and force-plays the video (top-level AND
    // inside iframes, with retries at 500ms/1500ms).
    LaunchedEffect(playerPhase) {
        if (playerPhase == PlayerPhase.STABILIZING) {
            phaseMessage = "Stabilizing player... (3s)"
            delay(3_000L)
            if (playerPhase == PlayerPhase.STABILIZING) {
                playerPhase = PlayerPhase.READY
                phaseMessage = ""
                webViewRef?.evaluateJavascript(STABILIZATION_END_JS, null)
            }
        }
    }

    // Stuck detection: if no video after 15s total, reload
    LaunchedEffect(playerPhase) {
        if (playerPhase == PlayerPhase.STABILIZING) {
            delay(15_000L)
            if (playerPhase == PlayerPhase.STABILIZING && stabilizeAttempts < 2) {
                playerPhase = PlayerPhase.STUCK
                phaseMessage = "Player stuck — reloading..."
                stabilizeAttempts++
                webViewRef?.reload()
            } else if (playerPhase == PlayerPhase.STABILIZING) {
                playerPhase = PlayerPhase.READY
                phaseMessage = ""
                webViewRef?.evaluateJavascript(STABILIZATION_END_JS, null)
            }
        }
    }

    // Detect page reload (stabilization retry)
    LaunchedEffect(stabilizeAttempts) {
        if (stabilizeAttempts > 0 && stabilizeAttempts <= 2) {
            delay(2_000L)
            if (playerPhase == PlayerPhase.STUCK) {
                playerPhase = PlayerPhase.STABILIZING
                phaseMessage = "Stabilizing player... (${stabilizeAttempts + 1})"
            }
        }
    }

    // Clean audio gesture on READY: clicks the screen center to start playback
    // (dismissing any iframe/embed play overlays) and sets volume to 1.0 clean.
    LaunchedEffect(playerPhase) {
        if (playerPhase == PlayerPhase.READY) {
            val view = webViewRef ?: return@LaunchedEffect
            delay(300L)
            simulateCenterClick(view)
            view.evaluateJavascript(CLEAN_AUDIO_UNMUTE_JS, null)
            delay(1000L)
            simulateCenterClick(view)
            view.evaluateJavascript(CLEAN_AUDIO_UNMUTE_JS, null)
        }
    }

    LaunchedEffect(embedUrl) {
        repeat(5) { attempt ->
            delay(2_000L * (attempt + 1))
            val view = webViewRef ?: return@repeat
            view.evaluateJavascript(CLEAN_AUDIO_UNMUTE_JS, null)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = MA_SERVER_USER_AGENT
                        allowContentAccess = true
                        allowFileAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
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
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val lowerUrl = url.lowercase()

                            if (request.isForMainFrame) {
                                val isWrapperSite = embedUrl.contains("luciferdonghua") || embedUrl.contains("donghuastream") ||
                                        embedUrl.contains("footybite") || embedUrl.contains("sportsurge") ||
                                        embedUrl.contains("scorebat") || embedUrl.contains("watchwrestling")
                                val isRouterEmbed = embedUrl.contains("multiembed") || embedUrl.contains("autoembed") ||
                                        embedUrl.contains("embed.su") || embedUrl.contains("vidlink") ||
                                        embedUrl.contains("vidsrc") || embedUrl.contains("smashystream") ||
                                        embedUrl.contains("2embed") || embedUrl.contains("nontongo")

                                val isCloudflare = lowerUrl.contains("challenges.cloudflare.com") || lowerUrl.contains("cloudflare.com/cdn-cgi")
                                if (isCloudflare) return false

                                if (playerPhase != PlayerPhase.LOADING && !isWrapperSite && !isRouterEmbed && !request.url.toString().contains("embed")) {
                                    return true
                                }

                                val reqHost = request.url?.host?.lowercase() ?: ""
                                val embedHost = android.net.Uri.parse(embedUrl).host?.lowercase() ?: ""
                                if (reqHost.isNotEmpty() && embedHost.isNotEmpty()) {
                                    val isSameDomain = reqHost == embedHost ||
                                            reqHost.endsWith(".$embedHost") ||
                                            embedHost.endsWith(".$reqHost")
                                    if (!isSameDomain && !isWrapperSite && !isRouterEmbed) {
                                        return true
                                    }
                                }
                            }

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
                            if (isAd) return true

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
                            onWebViewCreated(view!!)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            webViewRef = view
                            onWebViewCreated(view!!)

                            if (playerPhase == PlayerPhase.LOADING || playerPhase == PlayerPhase.READY) {
                                val currentUrl = url?.lowercase() ?: ""

                                // Cloudflare challenge — go READY so user can interact
                                if (currentUrl.contains("challenges.cloudflare.com") || currentUrl.contains("/cdn-cgi/")) {
                                    playerPhase = PlayerPhase.READY
                                    phaseMessage = ""
                                    return
                                }

                                // Nontongo, MultiEmbed, AutoEmbed, EmbedSu — skip heavy stabilization
                                // so the user can see and click their play button immediately
                                if (currentUrl.contains("multiembed") || currentUrl.contains("nontongo") ||
                                    currentUrl.contains("autoembed") || currentUrl.contains("embed.su") ||
                                    currentUrl.contains("smashystream")
                                ) {
                                    playerPhase = PlayerPhase.READY
                                    phaseMessage = ""
                                    view?.evaluateJavascript(INLINE_VIDEO_JS, null)
                                } else {
                                    playerPhase = PlayerPhase.STABILIZING
                                    phaseMessage = "Stabilizing player... (3s)"
                                    view?.evaluateJavascript(STABILIZATION_START_JS, null)
                                    view?.evaluateJavascript(INLINE_VIDEO_JS, null)
                                    view?.evaluateJavascript(IFRAME_EXTRACTION_JS, null)
                                    view?.evaluateJavascript(FULLSCREEN_CSS_JS, null)
                                }
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            val lowerUrl = url.lowercase()

                            if (lowerUrl.contains("autoembed") || lowerUrl.contains("embed.su") ||
                                lowerUrl.contains("vidsrc.cc") || lowerUrl.contains("vidsrc.to") ||
                                lowerUrl.contains("challenges.cloudflare.com") || lowerUrl.contains("cloudflare.com/cdn-cgi") ||
                                lowerUrl.contains("turnstile")
                            ) {
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
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (lowerUrl.contains("popup") && !lowerUrl.startsWith(embedOrigin.lowercase())) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            return null
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            val desc = error?.description?.toString()?.lowercase() ?: ""
                            val reqUrl = request?.url?.toString() ?: ""

                            if (desc.contains("err_aborted") || desc.contains("err_blocked") ||
                                desc.contains("err_name_not_resolved") || desc.contains("err_connection_refused")
                            ) {
                                return
                            }

                            if (request?.isForMainFrame == true) {
                                val isRequestedHost = reqUrl == embedUrl || reqUrl.contains("cloudflare", ignoreCase = true)
                                if (isRequestedHost) {
                                    hasError = true
                                    phaseMessage = "Failed to load player: $desc"
                                }
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            callback?.onCustomViewHidden()
                        }
                        override fun onHideCustomView() {}
                    }

                    webViewRef = this
                    onWebViewCreated(this)

                    val extraHeaders = embedRequestHeaders(embedUrl)
                    if (extraHeaders.isNotEmpty()) {
                        loadUrl(embedUrl, extraHeaders)
                    } else {
                        loadUrl(embedUrl)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading + Stabilization overlay
        if (playerPhase == PlayerPhase.LOADING || playerPhase == PlayerPhase.STABILIZING || playerPhase == PlayerPhase.STUCK) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
                    if (playerPhase == PlayerPhase.STUCK) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00BFFF), modifier = Modifier.size(48.dp))
                    } else {
                        CircularProgressIndicator(color = Color(0xFF00BFFF), modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                    }
                    Text(episodeTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(phaseMessage, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
                    if (playerPhase == PlayerPhase.STABILIZING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp), color = Color(0xFF00BFFF), trackColor = Color.White.copy(alpha = 0.15f))
                    }
                }
            }
        }

        // Error overlay
        if (hasError && playerPhase != PlayerPhase.LOADING && playerPhase != PlayerPhase.STABILIZING && playerPhase != PlayerPhase.STUCK) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF6D00), modifier = Modifier.size(48.dp))
                    Text("Failed to load the player", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text("The embed may be blocked or unavailable.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private const val MA_SERVER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * Referer/Origin headers for embed hosts that require them. Shared by the
 * initial load and the reload-on-episode-change path so both behave identically.
 */
private fun embedRequestHeaders(embedUrl: String): Map<String, String> {
    val extraHeaders = mutableMapOf<String, String>()
    if (embedUrl.contains("embed.su")) {
        extraHeaders["Referer"] = "https://embed.su/"
        extraHeaders["Origin"] = "https://embed.su"
    } else if (embedUrl.contains("autoembed")) {
        extraHeaders["Referer"] = "https://player.autoembed.cc/"
        extraHeaders["Origin"] = "https://player.autoembed.cc"
    } else if (embedUrl.contains("vidsrc.cc")) {
        extraHeaders["Referer"] = "https://vidsrc.cc/"
        extraHeaders["Origin"] = "https://vidsrc.cc"
    } else if (embedUrl.contains("vidsrc.to")) {
        extraHeaders["Referer"] = "https://vidsrc.to/"
        extraHeaders["Origin"] = "https://vidsrc.to"
    } else if (embedUrl.contains("smashystream")) {
        extraHeaders["Referer"] = "https://embed.smashystream.com/"
    }
    return extraHeaders
}

private const val STABILIZATION_START_JS = """
(function() {
    window.__STABILIZING = true;
    
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

private const val STABILIZATION_END_JS = """
(function() {
    window.__STABILIZING = false;
    
    function forcePlay() {
        var selectors = [
            '.play-button', '.jw-icon-display', '.vjs-big-play-button',
            '#start', '.plyr__control--overlaid', 'button[aria-label="Play"]',
            '.play-btn', '.btn-play', '[id*="play"]', '[class*="play"]',
            'button', '.play', '.jw-display-icon-display', '[aria-label*="play" i]'
        ];
        selectors.forEach(function(sel) {
            try {
                var els = document.querySelectorAll(sel);
                els.forEach(function(el) {
                    if (el && el.tagName !== 'VIDEO') el.click();
                });
            } catch(e) {}
        });

        document.querySelectorAll('video').forEach(function(v) {
            try {
                v.muted = false;
                v.volume = 1.0;
                if (v.paused) {
                    var p = v.play();
                    if (p) p.catch(function() {});
                }
            } catch(e) {}
        });
    }

    forcePlay();
    setTimeout(forcePlay, 1000);
})();
"""

private const val INLINE_VIDEO_JS = """
(function() {
    try {
        window.open = function() { return null; };
        window.alert = function() {};
        window.confirm = function() { return true; };
        window.prompt = function() { return null; };
        window.onbeforeunload = null;
    } catch(e) {}

    function neutralizeOverlays() {
        try {
            const elements = document.querySelectorAll('div, a, span');
            elements.forEach(el => {
                const style = window.getComputedStyle(el);
                const zIndex = parseInt(style.zIndex, 10);
                if (zIndex > 100 && (style.opacity === '0' || style.background === 'transparent' || style.backgroundColor === 'rgba(0, 0, 0, 0)')) {
                    if (el.tagName === 'A' || el.onclick || el.getAttribute('onclick')) {
                        el.style.pointerEvents = 'none';
                    }
                }
            });
        } catch(e) {}
    }

    function forceInline() {
        try {
            const videos = document.querySelectorAll('video');
            videos.forEach(v => {
                v.setAttribute('playsinline', '');
                v.setAttribute('webkit-playsinline', '');
                v.setAttribute('x-webkit-airplay', 'allow');
                v.setAttribute('autoplay', '');
            });
        } catch(e) {}
    }

    forceInline();
    neutralizeOverlays();
    const observer = new MutationObserver(() => {
        forceInline();
        neutralizeOverlays();
    });
    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
    }
})();
"""

private const val IFRAME_EXTRACTION_JS = """
(function() {
    var host = window.location.hostname;
    if (host.includes('luciferdonghua') || host.includes('donghuastream') || host.includes('animexin')) {
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

private const val FULLSCREEN_CSS_JS = """
(function() {
    var host = window.location.hostname;
    if (!host.includes('luciferdonghua') && !host.includes('donghuastream') && !host.includes('animexin')) {
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

private const val CLEAN_AUDIO_UNMUTE_JS = """
(function() {
    function unmuteIn(doc) {
        try {
            doc.querySelectorAll('video, audio').forEach(function(v) {
                try {
                    v.muted = false;
                    v.volume = 1.0;
                    if (v.paused) {
                        var p = v.play();
                        if (p) p.catch(function() {});
                    }
                } catch(e) {}
            });
            var playButtons = doc.querySelectorAll('button, .play, .play-button, .jw-icon-display, .vjs-big-play-button, .jw-display-icon-display, [aria-label*="play" i]');
            playButtons.forEach(function(btn) {
                try { btn.click(); } catch(e) {}
            });
        } catch(e) {}
    }
    unmuteIn(document);
    try {
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            try {
                var inner = frames[i].contentDocument || frames[i].contentWindow.document;
                if (inner) unmuteIn(inner);
            } catch(e) {}
        }
    } catch(e) {}
})();
"""

private fun simulateCenterClick(view: WebView) {
    val w = view.width.takeIf { it > 0 } ?: 1920
    val h = view.height.takeIf { it > 0 } ?: 1080
    val cx = w / 2f
    val cy = h / 2f
    val eventTime = android.os.SystemClock.uptimeMillis()
    val downEvent = android.view.MotionEvent.obtain(
        eventTime, eventTime, android.view.MotionEvent.ACTION_DOWN,
        cx, cy, 0
    )
    val upEvent = android.view.MotionEvent.obtain(
        eventTime, eventTime + 100, android.view.MotionEvent.ACTION_UP,
        cx, cy, 0
    )
    view.dispatchTouchEvent(downEvent)
    view.dispatchTouchEvent(upEvent)
    downEvent.recycle()
    upEvent.recycle()
}

private fun isDirectMediaStream(url: String): Boolean {
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
        lower.endsWith(".mkv") || lower.endsWith(".mpd") || lower.contains(".m3u8")
}

private fun loadEmbedContent(webView: WebView?, url: String) {
    if (webView == null || url.isBlank()) return
    if (isDirectMediaStream(url)) {
        val safeUrl = org.json.JSONObject.quote(url)
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
                <style>
                    body, html { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; display:flex; align-items:center; justify-content:center; }
                    video { width:100%; height:100%; object-fit:contain; }
                </style>
            </head>
            <body>
                <video id="player" controls autoplay playsinline webkit-playsinline></video>
                <script>
                    var video = document.getElementById('player');
                    var src = ${'$'}safeUrl;
                    if (src.indexOf('.m3u8') !== -1 && Hls.isSupported()) {
                        var hls = new Hls({ enableWorker: true, lowLatencyMode: true });
                        hls.loadSource(src);
                        hls.attachMedia(video);
                        hls.on(Hls.Events.MANIFEST_PARSED, function() {
                            video.play().catch(function(){});
                        });
                        hls.on(Hls.Events.ERROR, function(event, data) {
                            if (data.fatal) {
                                switch(data.type) {
                                    case Hls.ErrorTypes.NETWORK_ERROR: hls.startLoad(); break;
                                    case Hls.ErrorTypes.MEDIA_ERROR: hls.recoverMediaError(); break;
                                    default: hls.destroy(); break;
                                }
                            }
                        });
                    } else {
                        video.src = src;
                        video.play().catch(function(){});
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://novelapp.local/", html, "text/html", "UTF-8", null)
    } else {
        val extraHeaders = embedRequestHeaders(url)
        if (extraHeaders.isNotEmpty()) {
            webView.loadUrl(url, extraHeaders)
        } else {
            webView.loadUrl(url)
        }
    }
}
