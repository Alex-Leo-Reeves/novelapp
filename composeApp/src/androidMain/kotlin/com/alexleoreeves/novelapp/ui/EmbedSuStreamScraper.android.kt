package com.alexleoreeves.novelapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

// Removed ALLOWED_EMBED_DOMAINS list as it was blocking necessary CDNs for VidLink WASM crypto

// File extensions and URL patterns that are definitively playable by ExoPlayer.
private val STREAM_PATTERNS = listOf(".m3u8", ".mp4", ".mpd", ".ts", "/hls/", "/dash/", "/manifest/")

/**
 * Tries to scrape a direct stream URL (.m3u8 / .mp4) from [embedUrl] using a
 * hidden in-memory WebView.  Ad redirects are blocked; only allowed embed domains
 * may load.  Returns `null` if no stream is found within [timeoutMs].
 *
 * **Must be called on the main thread** (WebView requires it).
 */
data class ScrapedStream(
    val url: String,
    val subtitlesJson: String? = null
)

@SuppressLint("SetJavaScriptEnabled")
suspend fun extractStreamFromEmbed(
    context: Context,
    embedUrl: String,
    timeoutMs: Long = 15_000L
): ScrapedStream? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var settled = false
        var capturedSubtitles: String? = null

        fun deliver(url: String?) {
            if (settled) return
            settled = true
            
            // If we found a URL but subtitles haven't been captured yet, wait a tiny bit to avoid the race condition
            val delayMs = if (url != null && capturedSubtitles == null) 850L else 0L
            mainHandler.postDelayed({
                if (cont.isActive) {
                    if (url != null) cont.resume(ScrapedStream(url, capturedSubtitles))
                    else cont.resume(null)
                }
                try { webView?.destroy() } catch (_: Exception) {}
                webView = null
            }, delayMs)
        }

        mainHandler.post {
            try {
                val wv = WebView(context).apply {
                    @SuppressLint("VisibleForTests")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = SCRAPER_USER_AGENT
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
                        ): Boolean {
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.hasInjectedScraper) return;
                                    window.hasInjectedScraper = true;
                                    const origFetch = window.fetch;
                                    window.fetch = async function() {
                                        const response = await origFetch.apply(this, arguments);
                                        const reqUrl = typeof arguments[0] === 'string' ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                                        if (reqUrl && reqUrl.includes('api/b/')) {
                                            response.clone().json().then(data => {
                                                const tracks = data?.stream?.tracks || data?.tracks;
                                                if (tracks && Array.isArray(tracks)) {
                                                    console.log('MAGIC_SUBTITLES=' + JSON.stringify(tracks));
                                                }
                                            }).catch(e => {});
                                        }
                                        return response;
                                    };
                                    const origOpen = window.XMLHttpRequest.prototype.open;
                                    window.XMLHttpRequest.prototype.open = function() {
                                        this.addEventListener('load', function() {
                                            if (this.responseURL && this.responseURL.includes('api/b/')) {
                                                try {
                                                    const data = JSON.parse(this.responseText);
                                                    const tracks = data?.stream?.tracks || data?.tracks;
                                                    if (tracks && Array.isArray(tracks)) {
                                                        console.log('MAGIC_SUBTITLES=' + JSON.stringify(tracks));
                                                    }
                                                } catch(e) {}
                                            }
                                        });
                                        origOpen.apply(this, arguments);
                                    };
                                    window.addEventListener('message', function(e) {
                                        try {
                                            let d = e.data;
                                            if (typeof d === 'string') d = JSON.parse(d);
                                            const type = d?.type || d?.event;
                                            if (type && (type.includes('vidlink') || type.includes('stream') || type === 'ready')) {
                                                const tracks = d.tracks || d.data?.tracks || d.stream?.tracks;
                                                if (tracks && Array.isArray(tracks)) {
                                                    console.log('MAGIC_SUBTITLES=' + JSON.stringify(tracks));
                                                }
                                            }
                                        } catch(err) {}
                                    });
                                })();
                            """.trimIndent(), null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("""
                                (function() {
                                    // Try to click play buttons
                                    const selectors = ['.play-button', '.jw-icon-display', '.vjs-big-play-button', '#start', '.plyr__control--overlaid', 'button[aria-label="Play"]', '.play-btn'];
                                    selectors.forEach(sel => {
                                        const btn = document.querySelector(sel);
                                        if (btn) btn.click();
                                    });
                                    
                                    // Fallback: click the center of the screen
                                    const clickEvent = new MouseEvent('click', {
                                        view: window,
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: window.innerWidth / 2,
                                        clientY: window.innerHeight / 2
                                    });
                                    document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2)?.dispatchEvent(clickEvent);
                                    
                                    // Poll for video elements — Donghua players often set src after page load
                                    function checkVideoSrc() {
                                        const videos = document.querySelectorAll('video');
                                        for (const v of videos) {
                                            const src = v.src || v.currentSrc;
                                            if (src && (src.includes('.m3u8') || src.includes('.mp4') || src.includes('.mpd') || src.includes('/hls/') || src.includes('/dash/'))) {
                                                if (!src.startsWith('blob:')) {
                                                    console.log('MAGIC_VIDEO_SRC=' + src);
                                                }
                                            }
                                            // Also check <source> children
                                            v.querySelectorAll('source').forEach(s => {
                                                if (s.src && (s.src.includes('.m3u8') || s.src.includes('.mp4'))) {
                                                    console.log('MAGIC_VIDEO_SRC=' + s.src);
                                                }
                                            });
                                            v.play().catch(e => {});
                                        }
                                        // Also check iframes
                                        document.querySelectorAll('iframe').forEach(iframe => {
                                            try {
                                                const iDoc = iframe.contentDocument || iframe.contentWindow?.document;
                                                if (iDoc) {
                                                    iDoc.querySelectorAll('video').forEach(v => {
                                                        const src = v.src || v.currentSrc;
                                                        if (src && !src.startsWith('blob:') && (src.includes('.m3u8') || src.includes('.mp4'))) {
                                                            console.log('MAGIC_VIDEO_SRC=' + src);
                                                        }
                                                    });
                                                }
                                            } catch(e) {}
                                        });
                                    }
                                    // Poll several times to catch late-loaded sources
                                    setTimeout(checkVideoSrc, 1000);
                                    setTimeout(checkVideoSrc, 3000);
                                    setTimeout(checkVideoSrc, 6000);
                                    setTimeout(checkVideoSrc, 10000);
                                })();
                            """.trimIndent(), null)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            if (isPlayableStreamUrl(url) &&
                                STREAM_PATTERNS.any { url.contains(it, ignoreCase = true) }) {
                                deliver(url)
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return null
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) deliver(null)
                        }
                    }

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            if (msg.startsWith("MAGIC_SUBTITLES=")) {
                                val json = msg.removePrefix("MAGIC_SUBTITLES=").trim()
                                if (json.isNotBlank()) capturedSubtitles = json
                                return true
                            }
                            if (msg.startsWith("MAGIC_VIDEO_SRC=")) {
                                val videoSrc = msg.removePrefix("MAGIC_VIDEO_SRC=").trim()
                                if (videoSrc.isNotBlank() && videoSrc.startsWith("http")) {
                                    deliver(videoSrc)
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

                wv.loadUrl(embedUrl, buildEmbedHeaders(embedUrl))
            } catch (e: Exception) {
                deliver(null)
            }
        }
    }
}

/** Returns `true` if the URL looks like something ExoPlayer can directly play. */
fun isPlayableStreamUrl(url: String): Boolean {
    val pathLower = url.substringBefore("?").lowercase()
    return pathLower.endsWith(".m3u8") ||
        pathLower.endsWith(".mp4") ||
        pathLower.endsWith(".mpd") ||
        pathLower.endsWith(".webm") ||
        pathLower.endsWith(".ts") ||
        pathLower.contains("/hls/") ||
        pathLower.contains("/dash/") ||
        pathLower.contains("/manifest/") ||
        pathLower.contains("/playlist.m3u8") ||
        pathLower.contains("/index.m3u8") ||
        pathLower.contains("/master.m3u8")
}

private fun buildEmbedHeaders(embedUrl: String): Map<String, String> {
    val uri = runCatching { android.net.Uri.parse(embedUrl) }.getOrNull()
    val origin = if (uri != null) "${uri.scheme}://${uri.host}" else "https://google.com"
    val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else "https://google.com/"
    
    return mapOf(
        "User-Agent" to SCRAPER_USER_AGENT,
        "Referer" to referer,
        "Origin" to origin,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
}

private const val SCRAPER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
