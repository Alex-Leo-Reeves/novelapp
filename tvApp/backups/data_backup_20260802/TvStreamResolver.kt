package com.alexleoreeves.novelapp.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Ported from the Android app's EmbedSuStreamScraper — TV needs the same
 * WebView-based embed scraping so vidsrc/vidlink/etc. servers play real streams.
 */

data class ScrapedTvStream(
    val url: String,
    val subtitlesJson: String? = null
)

private val STREAM_PATTERNS = listOf(".m3u8", ".mp4", ".mpd", ".webm", ".mkv", ".mov", ".ts")

private val SCRAPER_USER_AGENTS = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

@SuppressLint("SetJavaScriptEnabled")
suspend fun extractTvStreamFromEmbed(
    context: Context,
    embedUrl: String,
    timeoutMs: Long = 45_000L,
    userAgentIndex: Int = 0
): ScrapedTvStream? = withContext(Dispatchers.Main) {
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var settled = false
            var latestDetectedUrl: String? = null

            fun deliver(url: String?) {
                if (settled) return
                settled = true
                if (cont.isActive) {
                    val resultUrl = url ?: latestDetectedUrl
                    if (resultUrl != null) cont.resume(ScrapedTvStream(resultUrl))
                    else cont.resume(null)
                }
                try { webView?.destroy() } catch (_: Exception) {}
                webView = null
            }

            try {
                val userAgentToUse = SCRAPER_USER_AGENTS.getOrElse(userAgentIndex) { SCRAPER_USER_AGENTS.first() }

                val wv = WebView(context).apply {
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
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            val resourceUrl = url ?: return
                            if (isTvPlayableStreamUrl(resourceUrl) &&
                                STREAM_PATTERNS.any { resourceUrl.contains(it, ignoreCase = true) }
                            ) {
                                latestDetectedUrl = resourceUrl
                                if (System.currentTimeMillis() > 3000L) deliver(resourceUrl)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view == null) return
                            view.evaluateJavascript(SCRAPE_JS, null)
                            val delays = longArrayOf(1000L, 3000L, 6000L, 10000L, 15000L)
                            for (delayMs in delays) {
                                mainHandler.postDelayed({
                                    if (!settled && webView != null) {
                                        webView?.evaluateJavascript("""
                                            (function() {
                                                var sources = [];
                                                document.querySelectorAll('video').forEach(function(v) {
                                                    if (v.src && v.src.startsWith('http') && !v.src.startsWith('blob:')) sources.push(v.src);
                                                    if (v.currentSrc && v.currentSrc.startsWith('http') && !v.currentSrc.startsWith('blob:')) sources.push(v.currentSrc);
                                                });
                                                document.querySelectorAll('source').forEach(function(s) {
                                                    if (s.src && s.src.startsWith('http')) sources.push(s.src);
                                                });
                                                document.querySelectorAll('[data-src],[data-file],[data-video]').forEach(function(el) {
                                                    var val = el.getAttribute('data-src') || el.getAttribute('data-file') || el.getAttribute('data-video');
                                                    if (val && val.startsWith('http')) sources.push(val);
                                                });
                                                sources.filter(function(s, i) { return sources.indexOf(s) === i; }).forEach(function(src) {
                                                    console.log('MAGIC_VIDEO_SRC=' + src);
                                                });
                                                if (window.location.href.includes('.m3u8') || window.location.href.includes('.mp4')) {
                                                    console.log('MAGIC_VIDEO_SRC=' + window.location.href);
                                                }
                                            })();
                                        """.trimIndent(), null)
                                    }
                                }, delayMs)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && latestDetectedUrl == null) deliver(null)
                        }
                    }

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            if (msg.startsWith("MAGIC_VIDEO_SRC=")) {
                                val videoSrc = msg.removePrefix("MAGIC_VIDEO_SRC=").trim()
                                if (videoSrc.isNotBlank() && videoSrc.startsWith("http") && isTvPlayableStreamUrl(videoSrc)) {
                                    latestDetectedUrl = videoSrc
                                    if (System.currentTimeMillis() > 5000L) deliver(videoSrc)
                                }
                                return true
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                }
                webView = wv

                cont.invokeOnCancellation {
                    try { wv.destroy() } catch (_: Exception) {}
                    webView = null
                }

                wv.loadUrl(embedUrl, buildEmbedHeaders(embedUrl, userAgentToUse))
            } catch (e: Exception) {
                deliver(null)
            }
        }
    }
}

fun isTvPlayableStreamUrl(url: String): Boolean {
    val fullLower = url.lowercase()
    val pathLower = url.substringBefore("?").substringBefore("#").lowercase()
    if (pathLower.endsWith(".m3u8") || pathLower.endsWith(".mp4") || pathLower.endsWith(".mpd") ||
        pathLower.endsWith(".webm") || pathLower.endsWith(".mkv") || pathLower.endsWith(".mov") || pathLower.endsWith(".ts")) {
        return true
    }
    if (fullLower.contains("/v1/proxy?data=")) return true
    if (fullLower.contains(".m3u8") || fullLower.contains(".mp4") || fullLower.contains(".mpd") || fullLower.contains(".webm")) return true
    if (fullLower.contains("/dash/") || fullLower.contains("/stream/") || fullLower.contains("/playlist/") || fullLower.contains("/segment")) return true
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

private const val SCRAPE_JS = """
(function() {
    function report(url) {
        if (url && url.startsWith('http') && !url.startsWith('blob:')) {
            console.log('MAGIC_VIDEO_SRC=' + url);
        }
    }
    function checkNativeVideo() {
        document.querySelectorAll('video').forEach(function(v) {
            report(v.src); report(v.currentSrc);
            v.querySelectorAll('source').forEach(function(s) { report(s.src); });
        });
        document.querySelectorAll('source').forEach(function(s) { report(s.src); });
    }
    function checkJWPlayer() {
        try {
            if (typeof jwplayer !== 'undefined') {
                var players = (jwplayer.getAll && jwplayer.getAll()) || [];
                if (players.length === 0 && jwplayer()) players = [jwplayer()];
                players.forEach(function(p) {
                    try {
                        var playlist = p.getPlaylist();
                        if (playlist && playlist.length > 0) {
                            playlist.forEach(function(item) {
                                if (item.sources) item.sources.forEach(function(s) { report(s.file || s.url || s.src); });
                                report(item.file || item.url || item.src);
                            });
                        }
                    } catch(e) {}
                });
            }
        } catch(e) {}
    }
    function checkVideoJS() {
        try {
            if (typeof videojs !== 'undefined') {
                document.querySelectorAll('.video-js').forEach(function(el) {
                    try {
                        var player = videojs(el.id || el);
                        if (player) {
                            report(player.currentSrc());
                            var sources = player.currentSources();
                            if (sources) sources.forEach(function(s) { report(s.src); });
                        }
                    } catch(e) {}
                });
            }
        } catch(e) {}
    }
    function checkHLSjs() {
        try {
            if (typeof Hls !== 'undefined') {
                document.querySelectorAll('video').forEach(function(v) { if (v.hls) report(v.hls.url); });
                if (window.__hls_instances) window.__hls_instances.forEach(function(h) { report(h.url); });
            }
        } catch(e) {}
    }
    function checkDataAttributes() {
        document.querySelectorAll('[data-file],[data-src],[data-url],[data-video],[data-hls]').forEach(function(el) {
            var val = el.getAttribute('data-file') || el.getAttribute('data-src') || el.getAttribute('data-url') || el.getAttribute('data-video') || el.getAttribute('data-hls');
            report(val);
        });
    }
    function checkScriptTags() {
        try {
            document.querySelectorAll('script').forEach(function(script) {
                var text = script.textContent || '';
                var matches = text.match(/https?:\\\/\\\/[^\\"'\s]+(?:\.m3u8|\.mp4)[^\\"'\s]*/g);
                if (matches) matches.forEach(function(m) { report(m.replace(/\\\//g, '/')); });
            });
        } catch(e) {}
    }
    function clickPlayButtons() {
        document.querySelectorAll('.jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid, button[aria-label="Play"], .play-button, .play-btn, #start, [class*="play"], [id*="play"]').forEach(function(btn) {
            try { if (btn.tagName !== 'VIDEO') btn.click(); } catch(e) {}
        });
        setTimeout(function() {
            try { var el = document.elementFromPoint(window.innerWidth/2, window.innerHeight/2); if (el) el.click(); } catch(e) {}
        }, 500);
    }
    checkNativeVideo(); checkJWPlayer(); checkVideoJS(); checkHLSjs(); checkDataAttributes(); checkScriptTags(); clickPlayButtons();
    setTimeout(function() { checkNativeVideo(); checkJWPlayer(); checkHLSjs(); checkDataAttributes(); }, 2000);
    setTimeout(function() { checkNativeVideo(); checkJWPlayer(); checkHLSjs(); }, 5000);
    setTimeout(function() { checkNativeVideo(); checkJWPlayer(); }, 10000);
})();
"""
