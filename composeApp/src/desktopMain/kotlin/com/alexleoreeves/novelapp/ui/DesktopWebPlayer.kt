package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import kotlinx.coroutines.delay

/**
 * Runtime probe for JavaFX. The probe actually *constructs* a JFXPanel rather
 * than just checking for the class — constructing it forces the native FX
 * toolkit to load, so on hosts where JavaFX is present-but-not-runnable the
 * probe correctly reports false and every player falls back to browser-open
 * behavior instead of throwing on screen.
 */
internal object DesktopJavaFxCheck {
    val isAvailable: Boolean by lazy {
        runCatching {
            JfxPanelHolder()
        }.isSuccess
    }
}

/**
 * In-app HTML web player for desktop.
 *
 * Hosts a JavaFX WebView (equivalent to the WKWebView on iOS) inside a
 * SwingPanel, so embed providers (VidSrc / VidLink / MultiEmbed), YouTube
 * embeds, and direct-stream HTML wrappers play *inside* the app — no browser
 * hop. Includes a loading veil while the page spins up (mirrors iOS/Android
 * behavior) and a themed back button + title bar.
 *
 * Preview caps are enforced by the caller — each player screen owns its own
 * timer so free-preview semantics match the platform actuals.
 */
@Composable
internal fun DesktopWebPlayer(
    url: String,
    title: String,
    currentTheme: AppTheme,
    onBack: () -> Unit,
    fallback: @Composable () -> Unit
) {
    if (!DesktopJavaFxCheck.isAvailable) {
        fallback()
        return
    }

    val holder = remember(url) { JfxPanelHolder() }

    DisposableEffect(holder, url) {
        javax.swing.SwingUtilities.invokeLater { holder.load(url) }
        onDispose {
            javax.swing.SwingUtilities.invokeLater { holder.dispose() }
        }
    }

    var showLoadVeil by remember(url) { mutableStateOf(true) }
    LaunchedEffect(url) {
        if (showLoadVeil) {
            delay(15_000)
            showLoadVeil = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxSize()) {
            SwingPanel(
                factory = { holder.component },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showLoadVeil) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF00BFFF))
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    "Loading secure player...",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 72.dp, end = 16.dp, top = 22.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

/**
 * Shared "open in default browser" fallback surface. Used when JavaFX cannot
 * run or a stream type cannot render inside the WebView (e.g. raw HLS). Keeps
 * the desktop experience professional instead of a dead black screen.
 */
@Composable
internal fun BrowserOpenFallback(
    title: String,
    subtitle: String,
    url: String,
    accent: Color = Color(0xFF00BFFF),
    extraAction: (@Composable () -> Unit)? = null,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
            )
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
            extraAction?.invoke()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { openInDefaultBrowser(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open in browser")
                }
                TextButton(onClick = onBack) {
                    Text("Back", color = Color.White)
                }
            }
        }
    }
}

/**
 * All JavaFX objects live behind this wrapper so the heavy FX classes are only
 * touched on the FX thread. JFXPanel is the documented way to embed JavaFX in
 * Swing — constructing it on the Swing EDT (where Compose composition runs)
 * initializes the FX toolkit.
 */
private class JfxPanelHolder {
    private val jfxPanel = javafx.embed.swing.JFXPanel()
    private var webView: javafx.scene.web.WebView? = null
    val component: javafx.embed.swing.JFXPanel get() = jfxPanel

    fun load(url: String) {
        javafx.application.Platform.runLater {
            if (webView == null) {
                val view = javafx.scene.web.WebView()
                view.isContextMenuEnabled = false
                webView = view
                view.engine.userAgent = DESKTOP_WEBVIEW_USER_AGENT
                jfxPanel.scene = javafx.scene.Scene(view)
            }
            webView?.engine?.load(url)
        }
    }

    fun dispose() {
        javafx.application.Platform.runLater {
            webView?.engine?.load("about:blank")
            jfxPanel.scene = null
            webView = null
        }
    }
}

/** Builds a self-contained data: URL that plays a direct .mp4/.webm stream in
 * the WebView via a plain HTML5 <video> element. */
internal fun directVideoHtml(url: String): String {
    val encoded = url.split("?").first()
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;padding:0;background:#000;height:100%;}" +
        "video{width:100vw;height:100vh;object-fit:contain;background:#000;}" +
        "</style></head><body><video src=\"$encoded\" autoplay controls playsinline></video></body></html>"
}

/**
 * Builds a self-contained data: URL that plays an HLS .m3u8 stream in the
 * WebView via hls.js (loaded from CDN). This is what makes raw HLS playback
 * work in-app on desktop, matching the iOS AVPlayer / Android ExoPlayer HLS
 * support.
 */
internal fun hlsVideoHtml(url: String): String =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;padding:0;background:#000;height:100%;}" +
        "video{width:100vw;height:100vh;object-fit:contain;background:#000;}" +
        "</style></head><body>" +
        "<video id=\"v\" autoplay controls playsinline></video>" +
        "<script src=\"https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js\"></script>" +
        "<script>" +
        "if (Hls.isSupported()) {" +
        "  var hls = new Hls({liveDurationInfinity: true});" +
        "  hls.loadSource(\"$url\");" +
        "  hls.attachMedia(document.getElementById('v'));" +
        "  hls.on(Hls.Events.MANIFEST_PARSED, function () { document.getElementById('v').play(); });" +
        "} else if (document.getElementById('v').canPlayType('application/vnd.apple.mpegurl')) {" +
        "  document.getElementById('v').src = \"$url\";" +
        "}</script></body></html>"

private val DIRECT_MEDIA_EXTENSION = Regex("\\.(?:mp4|webm|mov|mkv|mpd)(?:\\?|#|$)", RegexOption.IGNORE_CASE)

internal fun isDirectMediaUrl(url: String): Boolean =
    DIRECT_MEDIA_EXTENSION.containsMatchIn(url.substringBefore("?").substringBefore("#"))

internal fun isHlsUrl(url: String): Boolean =
    url.substringBefore("?").substringBefore("#").endsWith(".m3u8")

private fun openInDefaultBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    }.onFailure {
        println("[DesktopPlayer] Could not open in browser: ${it.message}")
    }
}

private const val DESKTOP_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
