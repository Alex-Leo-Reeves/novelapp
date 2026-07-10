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

// ─────────────────────────────────────────────────────────────────────────────
//  Domains that are always allowed to load inside our hidden scraper WebView.
//  Everything else is silently blocked (ad networks, trackers, popup targets).
// ─────────────────────────────────────────────────────────────────────────────
private val ALLOWED_EMBED_DOMAINS = setOf(
    "embed.su",
    "vidsrc.me",
    "vidsrc.cc",
    "vidsrc.to",
    "vidlink.pro",
    "autoembed.cc",
    "2embed.cc",
    // CDN / stream delivery hosts that are safe
    "akamaized.net",
    "fastly.net",
    "cloudfront.net",
    "cdn.jwplayer.com",
    "content.jwplatform.com",
    "jwpltx.com",
    "googlevideo.com",
    "cdn.plyr.io",
    "vidcdn.stream",
    "player.vimeo.com",
    "bunny.net",
    "b-cdn.net",
)

// File extensions and URL patterns that are definitively playable by ExoPlayer.
private val STREAM_PATTERNS = listOf(".m3u8", ".mp4", ".mpd", ".ts", "/hls/", "/dash/", "/manifest/")

/**
 * Tries to scrape a direct stream URL (.m3u8 / .mp4) from [embedUrl] using a
 * hidden in-memory WebView.  Ad redirects are blocked; only allowed embed domains
 * may load.  Returns `null` if no stream is found within [timeoutMs].
 *
 * **Must be called on the main thread** (WebView requires it).
 */
@SuppressLint("SetJavaScriptEnabled")
suspend fun extractStreamFromEmbed(
    context: Context,
    embedUrl: String,
    timeoutMs: Long = 22_000L
): String? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var settled = false

        fun deliver(url: String?) {
            if (settled) return
            settled = true
            mainHandler.post {
                try { webView?.destroy() } catch (_: Exception) {}
                webView = null
            }
            if (cont.isActive) cont.resume(url)
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
                            val url = request?.url?.toString() ?: return true
                            val host = request.url?.host ?: return true
                            val allowed = ALLOWED_EMBED_DOMAINS.any { domain ->
                                host == domain || host.endsWith(".$domain")
                            }
                            if (!allowed) return true
                            if (STREAM_PATTERNS.any { url.contains(it, ignoreCase = true) } &&
                                isPlayableStreamUrl(url)) {
                                deliver(url)
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""
                            if (isPlayableStreamUrl(url) &&
                                STREAM_PATTERNS.any { url.contains(it, ignoreCase = true) }) {
                                deliver(url)
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            val allowed = ALLOWED_EMBED_DOMAINS.any { domain ->
                                host == domain || host.endsWith(".$domain")
                            }
                            if (!allowed && url.startsWith("http")) {
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
    val lower = url.substringBefore("?").lowercase()
    return lower.endsWith(".m3u8") ||
        lower.endsWith(".mp4") ||
        lower.endsWith(".mpd") ||
        lower.endsWith(".webm") ||
        lower.contains("/hls/") ||
        lower.contains("/dash/") ||
        lower.contains("/manifest/")
}

private fun buildEmbedHeaders(embedUrl: String): Map<String, String> {
    val host = runCatching {
        val uri = android.net.Uri.parse(embedUrl)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault("https://embed.su")
    return mapOf(
        "User-Agent" to SCRAPER_USER_AGENT,
        "Referer" to "$host/",
        "Origin" to host,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
}

private const val SCRAPER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
