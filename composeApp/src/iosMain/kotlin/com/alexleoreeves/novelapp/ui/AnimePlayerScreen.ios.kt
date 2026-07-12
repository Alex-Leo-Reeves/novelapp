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

@OptIn(ExperimentalForeignApi::class)
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
    var retryKey by remember(streamUrl) { mutableStateOf(0) }
    var isLoading by remember(streamUrl, retryKey) { mutableStateOf(true) }
    var errorMessage by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    val providerName = streamUrl.providerName()

    LaunchedEffect(streamUrl, retryKey, isLoading) {
        if (isLoading) {
            delay(18_000)
            if (isLoading) {
                errorMessage = "$providerName is taking too long to respond. Try another provider or episode."
                isLoading = false
            }
        }
    }

    LaunchedEffect(streamUrl, retryKey, previewLimitMs) {
        val limit = previewLimitMs ?: return@LaunchedEffect
        delay(limit)
        onPreviewFinished()
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
                        customUserAgent = PLAYER_USER_AGENT
                        navigationDelegate = PlayerNavigationDelegate(
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
                        loadRequest(streamUrl.toPlayerRequest())
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
            PlayerLoadingOverlay(
                title = episodeTitle,
                providerName = providerName,
                message = errorMessage ?: "Loading secure player...",
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
private fun PlayerLoadingOverlay(
    title: String,
    providerName: String,
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
                OutlinedButton(onClick = onBack) {
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

private class PlayerNavigationDelegate(
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
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString ?: ""
        val host = decidePolicyForNavigationAction.request.URL?.host?.lowercase() ?: ""
        
        // Let it start with some initial host logic if needed, but for simplicity we just allow whitelist.
        val allowedDomains = listOf(
            "vidsrc", "nontongo", "multiembed", "streamingnow", "vidlink", 
            "youtube.com", "vimeo.com", "dailymotion.com"
        )
        
        if (allowedDomains.any { host.contains(it) }) {
            decisionHandler(platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }
}

private fun String.toPlayerRequest(): NSMutableURLRequest {
    val url = NSURL.URLWithString(this) ?: NSURL.URLWithString("https://vidsrc.to")!!
    return NSMutableURLRequest.requestWithURL(url).apply {
        allHTTPHeaderFields = playerHeaders()
    }
}

private fun String.playerHeaders(): Map<String, String> =
    mapOf(
        "User-Agent" to PLAYER_USER_AGENT,
        "Referer" to playerReferer(),
        "Origin" to playerOrigin(),
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

private fun String.playerReferer(): String = "${playerOrigin()}/"

private fun String.playerOrigin(): String {
    val url = NSURL.URLWithString(this)
    val scheme = url?.scheme ?: "https"
    val host = url?.host ?: "vidsrc.to"
    return "$scheme://$host"
}

private fun String.providerName(): String {
    val host = NSURL.URLWithString(this)?.host?.removePrefix("www.") ?: return "Embedded provider"
    return when {
        "vidlink" in host -> "VidLink"
        "nontongo" in host -> "Nontongo"
        "multiembed" in host || "streamingnow" in host -> "MultiEmbed"
        "vidsrcme" in host -> "VidSrc.me"
        "vidsrc.in" == host || host.endsWith(".vidsrc.in") -> "VidSrc.in"
        "vidsrc.to" == host || host.endsWith(".vidsrc.to") -> "VidSrc.to"
        "autoembed" in host -> "AutoEmbed"
        "vidsrc" in host -> "VidSrc"
        "embed" in host -> "Embed provider"
        else -> host
    }
}

private const val PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
