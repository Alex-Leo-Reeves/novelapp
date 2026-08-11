package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * iOS actual — YouTube player backed by WKWebView navigating to
 * https://www.youtube.com/embed/<videoId> with autoplay and muted=false.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun YouTubePlayerScreen(
    videoId: String,
    title: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
) {
    var retryKey by remember(videoId) { mutableStateOf(0) }
    var isLoading by remember(videoId, retryKey) { mutableStateOf(true) }
    var errorMessage by remember(videoId, retryKey) { mutableStateOf<String?>(null) }

    val embedUrl = remember(videoId) {
        "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
    }

    LaunchedEffect(videoId, retryKey, isLoading) {
        if (isLoading) {
            delay(20_000)
            if (isLoading) {
                errorMessage = "YouTube is taking too long to respond. Check your connection and retry."
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        key(retryKey) {
            UIKitView(
                factory = {
                    val config = WKWebViewConfiguration().apply {
                        allowsInlineMediaPlayback = true
                        mediaTypesRequiringUserActionForPlayback = 0u
                    }
                    WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
                        setOpaque(false)
                        backgroundColor = platform.UIKit.UIColor.blackColor
                        customUserAgent = YOUTUBE_USER_AGENT
                        navigationDelegate = YouTubeNavigationDelegate(
                            onStarted = {
                                isLoading = true
                                errorMessage = null
                            },
                            onFinished = {
                                isLoading = false
                            },
                            onFailed = { message ->
                                isLoading = false
                                errorMessage = message
                            }
                        )
                        loadRequest(embedUrl.toYouTuBeRequest())
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = isLoading || errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            YouTubeLoadingOverlay(
                title = title,
                message = errorMessage ?: "Loading YouTube player...",
                isError = errorMessage != null,
                onRetry = {
                    errorMessage = null
                    isLoading = true
                    retryKey++
                },
                onBack = onBack
            )
        }

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
}

@Composable
private fun YouTubeLoadingOverlay(
    title: String,
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isError) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(42.dp)
                )
            } else {
                CircularProgressIndicator(color = Color(0xFFFF3B30))
            }
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Text(
                "YouTube",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                message,
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

private class YouTubeNavigationDelegate(
    private val onStarted: () -> Unit,
    private val onFinished: () -> Unit,
    private val onFailed: (String) -> Unit
) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        onStarted()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: platform.Foundation.NSError) {
        onFailed(withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: platform.Foundation.NSError) {
        onFailed(withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: platform.WebKit.WKNavigationAction,
        decisionHandler: (platform.WebKit.WKNavigationActionPolicy) -> Unit
    ) {
        val host = decidePolicyForNavigationAction.request.URL?.host?.lowercase() ?: ""
        if ("youtube.com" in host || "youtube-nocookie.com" in host ||
            "googlevideo.com" in host || "ytimg.com" in host
        ) {
            decisionHandler(platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }
}

private fun String.toYouTuBeRequest(): NSMutableURLRequest {
    val url = NSURL.URLWithString(this) ?: NSURL.URLWithString("https://www.youtube.com")!!
    return NSMutableURLRequest.requestWithURL(url).apply {
        allHTTPHeaderFields = mapOf(
            "User-Agent" to YOUTUBE_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }
}

private const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
</｜｜DSML｜｜>
</｜｜DSML｜｜>
