package com.alexleoreeves.novelapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val STREAM_PATTERNS = listOf(
    ".m3u8", ".mp4", ".mpd", ".webm", ".mkv", ".mov", ".ts"
)

/**
 * Tries to scrape a direct stream URL (.m3u8 / .mp4) from [embedUrl] using a
 * hidden in-memory WebView. Returns `null` if no stream is found within [timeoutMs].
 *
 * **Must be called on the main thread** (WebView requires it).
 */
data class ScrapedStream(
    val url: String,
    val subtitlesJson: String? = null
)

/**
 * User agents to rotate through for bypassing bot detection.
 */
private val SCRAPER_USER_AGENTS = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

@SuppressLint("SetJavaScriptEnabled")
suspend fun extractStreamFromEmbed(
    context: Context,
    embedUrl: String,
    timeoutMs: Long = 45_000L,
    userAgentIndex: Int = 0
): ScrapedStream? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var settled = false
        // Track the latest detected URL for multiple detection passes
        var latestDetectedUrl: String? = null

        fun deliver(url: String?) {
            if (settled) return
            // If we have a URL, prefer it; otherwise keep waiting briefly
            if (url == null && latestDetectedUrl != null && !settled) {
                // Give a small grace period for the page to stabilize
                return
            }
            settled = true
            if (cont.isActive) {
                val resultUrl = url ?: latestDetectedUrl
                if (resultUrl != null) cont.resume(ScrapedStream(resultUrl))
                else cont.resume(null)
            }
            try { webView?.destroy() } catch (_: Exception) {}
            webView = null
        }

        mainHandler.post {
            try {
                val userAgentToUse = SCRAPER_USER_AGENTS.getOrElse(userAgentIndex) { SCRAPER_USER_AGENTS.first() }

                val wv = WebView(context).apply {
                    @SuppressLint("VisibleForTests")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = userAgentToUse
                        blockNetworkLoads = false
                        allowContentAccess = true
                        allowFileAccess = false
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view == null) return

                            // Inject comprehensive JS scraper
                            view.evaluateJavascript(SCRAPE_JS_TEMPLATE, null)

                            // Also do a series of delayed scrapes to catch late-loading players
                            val delays = longArrayOf(1000L, 3000L, 6000L, 10000L, 15000L)
                            for (delayMs in delays) {
                                mainHandler.postDelayed({
                                    if (!settled && view != null) {
                                        // Try extraction from page sources as well
                                        view.evaluateJavascript(GET_SOURCES_JS, null)
                                    }
                                }, delayMs)
                            }

                            // Also attempt extraction from all <source> elements and video src attributes
                            mainHandler.postDelayed({
                                if (!settled && view != null) {
                                    view.evaluateJavascript("""
                                        (function() {
                                            // Direct extraction: find all media sources on the page
                                            var sources = [];
                                            
                                            // 1. Check <video> src
                                            document.querySelectorAll('video').forEach(function(v) {
                                                if (v.src && v.src.startsWith('http') && !v.src.startsWith('blob:')) {
                                                    sources.push(v.src);
                                                }
                                                if (v.currentSrc && v.currentSrc.startsWith('http') && !v.currentSrc.startsWith('blob:')) {
                                                    sources.push(v.currentSrc);
                                                }
                                                // Check <source> children
                                                v.querySelectorAll('source').forEach(function(s) {
                                                    if (s.src && s.src.startsWith('http') && !s.src.startsWith('blob:')) {
                                                        sources.push(s.src);
                                                    }
                                                });
                                            });
                                            
                                            // 2. Check <source> outside <video>
                                            document.querySelectorAll('source').forEach(function(s) {
                                                if (s.src && s.src.startsWith('http')) sources.push(s.src);
                                            });
                                            
                                            // 3. Check data attributes common in embed players
                                            document.querySelectorAll('[data-src]').forEach(function(el) {
                                                var val = el.getAttribute('data-src');
                                                if (val && val.startsWith('http')) sources.push(val);
                                            });
                                            document.querySelectorAll('[data-file]').forEach(function(el) {
                                                var val = el.getAttribute('data-file');
                                                if (val && val.startsWith('http')) sources.push(val);
                                            });
                                            document.querySelectorAll('[data-video]').forEach(function(el) {
                                                var val = el.getAttribute('data-video');
                                                if (val && val.startsWith('http')) sources.push(val);
                                            });
                                            
                                            // Deduplicate and report
                                            sources.filter(function(s, i) { return sources.indexOf(s) === i; }).forEach(function(src) {
                                                console.log('MAGIC_VIDEO_SRC=' + src);
                                            });
                                            
                                            // Also report current page URL (might be a stream redirect)
                                            if (window.location.href.includes('.m3u8') || window.location.href.includes('.mp4')) {
                                                console.log('MAGIC_VIDEO_SRC=' + window.location.href);
                                            }
                                        })();
                                    """.trimIndent(), null)
                                }
                            }, 2000L)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            val resourceUrl = url ?: return
                            if (isPlayableStreamUrl(resourceUrl) &&
                                STREAM_PATTERNS.any { resourceUrl.contains(it, ignoreCase = true) }) {
                                latestDetectedUrl = resourceUrl
                                // Only deliver immediately if it's been more than 3 seconds (gives page time to stabilize)
                                if (System.currentTimeMillis() > 3000L) {
                                    deliver(resourceUrl)
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && latestDetectedUrl == null) {
                                deliver(null)
                            }
                        }
                    }

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            if (msg.startsWith("MAGIC_VIDEO_SRC=")) {
                                val videoSrc = msg.removePrefix("MAGIC_VIDEO_SRC=").trim()
                                if (videoSrc.isNotBlank() && videoSrc.startsWith("http") && isPlayableStreamUrl(videoSrc)) {
                                    latestDetectedUrl = videoSrc
                                    if (System.currentTimeMillis() > 5000L) {
                                        deliver(videoSrc)
                                    }
                                }
                                return true
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                }
                webView = wv

                cont.invokeOnCancellation {
                    mainHandler.post {
                        try { wv.destroy() } catch (_: Exception) {}
                        webView = null
                    }
                }

                wv.loadUrl(embedUrl, buildEmbedHeaders(embedUrl, SCRAPER_USER_AGENTS.getOrElse(userAgentIndex) { SCRAPER_USER_AGENTS.first() }))
            } catch (e: Exception) {
                deliver(null)
            }
        }
    }
}

/** Returns `true` if the URL looks like something ExoPlayer can directly play. */
fun isPlayableStreamUrl(url: String): Boolean {
    val fullLower = url.lowercase()
    val pathLower = url.substringBefore("?").substringBefore("#").lowercase()

    // Standard extension-based check
    if (pathLower.endsWith(".m3u8") ||
        pathLower.endsWith(".mp4") ||
        pathLower.endsWith(".mpd") ||
        pathLower.endsWith(".webm") ||
        pathLower.endsWith(".mkv") ||
        pathLower.endsWith(".mov") ||
        pathLower.endsWith(".ts")) {
        return true
    }

    // Check query params for embedded stream URLs
    if (fullLower.contains(".m3u8") || fullLower.contains(".mp4") || fullLower.contains(".mpd") || fullLower.contains(".webm")) {
        return true
    }

    // Detect common stream-path patterns
    if (fullLower.contains("/dash/") || fullLower.contains("/stream/") || fullLower.contains("/playlist/") || fullLower.contains("/segment")) {
        return true
    }

    return false
}

private fun buildEmbedHeaders(embedUrl: String, userAgent: String = SCRAPER_USER_AGENTS.first()): Map<String, String> {
    val uri = runCatching { android.net.Uri.parse(embedUrl) }.getOrNull()
    val origin = if (uri != null) "${uri.scheme}://${uri.host}" else "https://google.com"
    val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else "https://google.com/"

    return mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Origin" to origin,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )
}

/**
 * Comprehensive JS injected into scraped pages to detect video sources
 * from all major embed players (JWPlayer, VideoJS, Plyr, HLS.js, Clappr,
 * FlowPlayer, Shaka Player, native HTML5 video, etc.)
 */
private const val SCRAPE_JS_TEMPLATE = """
(function() {
    // Helper to report a discovered URL
    function report(url) {
        if (url && url.startsWith('http') && !url.startsWith('blob:')) {
            console.log('MAGIC_VIDEO_SRC=' + url);
        }
    }
    
    // 1. Native HTML5 <video> elements and <source> children
    function checkNativeVideo() {
        document.querySelectorAll('video').forEach(function(v) {
            report(v.src);
            report(v.currentSrc);
            v.querySelectorAll('source').forEach(function(s) { report(s.src); });
        });
        document.querySelectorAll('source').forEach(function(s) { report(s.src); });
    }
    
    // 2. JW Player API detection
    function checkJWPlayer() {
        try {
            if (typeof jwplayer !== 'undefined') {
                var players = jwplayer && jwplayer.getAll ? jwplayer.getAll() : [];
                if (players.length === 0 && jwplayer()) players = [jwplayer()];
                players.forEach(function(p) {
                    try {
                        var playlist = p.getPlaylist();
                        if (playlist && playlist.length > 0) {
                            playlist.forEach(function(item) {
                                if (item.sources) {
                                    item.sources.forEach(function(s) {
                                        report(s.file || s.url || s.src);
                                    });
                                }
                                report(item.file || item.url || item.src);
                            });
                        }
                        var state = p.getState ? p.getState() : '';
                        if (state === 'playing' || state === 'paused') {
                            p.play();
                        }
                    } catch(e) {}
                });
            }
        } catch(e) {}
    }
    
    // 3. VideoJS player detection
    function checkVideoJS() {
        try {
            if (typeof videojs !== 'undefined') {
                var players = document.querySelectorAll('.video-js');
                players.forEach(function(el) {
                    try {
                        var player = videojs(el.id || el);
                        if (player) {
                            var src = player.currentSrc();
                            report(src);
                            var sources = player.currentSources();
                            if (sources) {
                                sources.forEach(function(s) { report(s.src); });
                            }
                        }
                    } catch(e) {}
                });
            }
        } catch(e) {}
    }
    
    // 4. Plyr player detection
    function checkPlyr() {
        try {
            if (typeof Plyr !== 'undefined') {
                document.querySelectorAll('.plyr, [data-plyr]').forEach(function(el) {
                    try {
                        var player = new Plyr(el);
                        report(player.source);
                    } catch(e) {}
                });
            }
            // Also check data-plyr-provider and data-plyr-embed-id
            document.querySelectorAll('[data-plyr-provider]').forEach(function(el) {
                var provider = el.getAttribute('data-plyr-provider');
                var id = el.getAttribute('data-plyr-embed-id');
                if (provider && id) {
                    // Plyr usually sets the source via JS on the video element
                }
            });
        } catch(e) {}
    }
    
    // 5. HLS.js instances
    function checkHLSjs() {
        try {
            if (typeof Hls !== 'undefined') {
                document.querySelectorAll('video').forEach(function(v) {
                    if (v.hls) {
                        report(v.hls.url);
                    }
                });
                // Also check global hls instances
                if (window.__hls_instances) {
                    window.__hls_instances.forEach(function(hls) {
                        report(hls.url);
                    });
                }
            }
        } catch(e) {}
    }
    
    // 6. Shaka Player
    function checkShaka() {
        try {
            if (typeof shaka !== 'undefined' && shaka.Player) {
                document.querySelectorAll('video[data-shaka-player]').forEach(function(v) {
                    try {
                        var player = new shaka.Player(v);
                        report(player.getAssetUri());
                    } catch(e) {}
                });
            }
        } catch(e) {}
    }
    
    // 7. Clappr player
    function checkClappr() {
        try {
            document.querySelectorAll('[data-player], .clappr-player').forEach(function(el) {
                var src = el.getAttribute('data-src') || el.getAttribute('src');
                report(src);
            });
        } catch(e) {}
    }
    
    // 8. FlowPlayer
    function checkFlowPlayer() {
        try {
            if (typeof flowplayer !== 'undefined') {
                flowplayer(function(api) {
                    try {
                        var src = api.video.src;
                        report(src);
                    } catch(e) {}
                });
            }
        } catch(e) {}
    }
    
    // 9. Data attributes (common pattern in embed providers)
    function checkDataAttributes() {
        document.querySelectorAll('[data-file], [data-src], [data-url], [data-video], [data-hls]').forEach(function(el) {
            var val = el.getAttribute('data-file') || el.getAttribute('data-src') || el.getAttribute('data-url') || el.getAttribute('data-video') || el.getAttribute('data-hls');
            report(val);
        });
    }
    
    // 10. Check for JW Player playlist item (alternative method)
    function checkJWPlaylist() {
        try {
            // Some pages store the playlist in a script tag
            document.querySelectorAll('script').forEach(function(script) {
                var text = script.textContent || script.innerText || '';
                if (text.includes('jwplayer') || text.includes('playlist')) {
                    var matches = text.match(/https?:\\\/\\\/[^\\"'\s]+(?:\.m3u8|\.mp4)[^\\"'\s]*/g);
                    if (matches) {
                        matches.forEach(function(m) { report(m.replace(/\\\//g, '/')); });
                    }
                    // Also match JSON-style URLs
                    var jsonMatches = text.match(/"file"\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)"/g);
                    if (jsonMatches) {
                        jsonMatches.forEach(function(m) {
                            try {
                                var url = JSON.parse('{' + m + '}').file;
                                report(url);
                            } catch(e) {}
                        });
                    }
                }
            });
        } catch(e) {}
    }
    
    // 11. window.__NUXT__ / window.__NEXT_DATA__ (Vue/Next.js sites)
    function checkSSRData() {
        try {
            if (window.__NUXT__) {
                var data = JSON.stringify(window.__NUXT__);
                var matches = data.match(/https?:\\\/\\\/[^\\"'\s]+(?:\.m3u8|\.mp4)[^\\"'\s]*/g);
                if (matches) {
                    matches.forEach(function(m) { report(m.replace(/\\\//g, '/')); });
                }
            }
            if (window.__NEXT_DATA__) {
                var data = JSON.stringify(window.__NEXT_DATA__);
                var matches = data.match(/https?:\\\/\\\/[^\\"'\s]+(?:\.m3u8|\.mp4)[^\\"'\s]*/g);
                if (matches) {
                    matches.forEach(function(m) { report(m.replace(/\\\//g, '/')); });
                }
            }
        } catch(e) {}
    }
    
    // 12. Click play buttons to trigger stream loading
    function clickPlayButtons() {
        var btns = document.querySelectorAll('.jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid, button[aria-label="Play"], .play-button, .play-btn, #start, [class*="play"], [id*="play"]');
        btns.forEach(function(btn) {
            try {
                if (btn.tagName !== 'VIDEO') btn.click();
            } catch(e) {}
        });
        // Also dispatch click at center
        setTimeout(function() {
            try {
                var el = document.elementFromPoint(window.innerWidth/2, window.innerHeight/2);
                if (el) el.click();
            } catch(e) {}
        }, 500);
    }
    
    // Run all checks
    checkNativeVideo();
    checkJWPlayer();
    checkVideoJS();
    checkPlyr();
    checkHLSjs();
    checkShaka();
    checkClappr();
    checkFlowPlayer();
    checkDataAttributes();
    checkJWPlaylist();
    checkSSRData();
    clickPlayButtons();
    
    // Re-run after delays to catch late-loading players
    setTimeout(function() {
        checkNativeVideo();
        checkJWPlayer();
        checkHLSjs();
        checkDataAttributes();
        checkJWPlaylist();
    }, 2000);
    setTimeout(function() {
        checkNativeVideo();
        checkJWPlayer();
        checkHLSjs();
    }, 5000);
    setTimeout(function() {
        checkNativeVideo();
        checkJWPlayer();
    }, 10000);
})();
"""

/**
 * Lightweight JS to poll current page sources - run periodically after load.
 */
private const val GET_SOURCES_JS = """
(function() {
    document.querySelectorAll('video').forEach(function(v) {
        if (v.src && v.src.startsWith('http') && !v.src.startsWith('blob:')) {
            console.log('MAGIC_VIDEO_SRC=' + v.src);
        }
        if (v.currentSrc && v.currentSrc.startsWith('http') && !v.currentSrc.startsWith('blob:')) {
            console.log('MAGIC_VIDEO_SRC=' + v.currentSrc);
        }
    });
    document.querySelectorAll('source').forEach(function(s) {
        if (s.src && s.src.startsWith('http')) {
            console.log('MAGIC_VIDEO_SRC=' + s.src);
        }
    });
    // After scrolling, iframes may lazy-load
    window.scrollTo(0, 100);
    setTimeout(function() { window.scrollTo(0, 0); }, 500);
})();
"""
