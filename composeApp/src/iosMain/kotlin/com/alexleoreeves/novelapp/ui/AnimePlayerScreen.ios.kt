@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private const val PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

@Composable
actual fun AnimePlayerScreen(
    streamUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    initialPositionMs: Long,
    onProgress: (Long) -> Unit,
    previewLimitMs: Long?,
    onPreviewFinished: () -> Unit,
    contentKind: String,
    subtitlesJson: String?,
    onBack: () -> Unit
) {
    val isLocalPath = remember(streamUrl) { streamUrl.isIosLocalMediaPath() }
    var retryKey by remember(streamUrl) { mutableStateOf(0) }
    var isLoading by remember(streamUrl, retryKey) { mutableStateOf(!isLocalPath) }
    var errorMessage by remember(streamUrl, retryKey) { mutableStateOf<String?>(null) }
    val providerName = streamUrl.animeProviderName()

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
        if (isLocalPath) {
            IosOfflinePlayer(
                localPath = streamUrl,
                modifier = Modifier.fillMaxSize(),
                onPlaybackEnded = onPreviewFinished
            )
        } else {
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
                            navigationDelegate = AnimePlayerNavigationDelegate(
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
                            val url = NSURL.URLWithString(streamUrl)
                                ?: NSURL.URLWithString("https://vidsrc.to")!!
                            loadRequest(NSURLRequest.requestWithURL(url)!!)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AnimatedVisibility(
            visible = isLoading || errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AnimePlayerLoadingOverlay(
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
private fun AnimePlayerLoadingOverlay(
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
                    tint = Color(0xFF00BFFF),
                    modifier = Modifier.size(42.dp)
                )
            } else {
                CircularProgressIndicator(color = Color(0xFF00BFFF))
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

private class AnimePlayerNavigationDelegate(
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
        val allowed = listOf(
            "vidsrc", "nontongo", "multiembed", "streamingnow", "vidlink",
            "youtube.com", "vimeo.com", "dailymotion.com", "autoembed"
        )
        if (allowed.any { host.contains(it) } || host.isEmpty()) {
            decisionHandler(platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }
    }
}

private fun String.isIosLocalMediaPath(): Boolean =
    startsWith("file://", ignoreCase = true) ||
        (startsWith("/") && !contains("://"))

private fun String.animeProviderName(): String {
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
