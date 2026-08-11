package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.data.extractStreamFromEmbed
import kotlinx.coroutines.delay

/**
 * Desktop (Windows) actual for the full-screen video player.
 *
 * Real in-app playback via the JavaFX WebView: direct .mp4/.webm streams play
 * through an HTML5 <video> wrapper, HLS .m3u8 streams play through an hls.js
 * wrapper, and every embed provider URL loads directly in the WebView —
 * equivalent to the iOS WKWebView / Android WebView implementations. When
 * JavaFX can't run on the host, each path degrades to a themed browser-open
 * surface instead of a dead screen.
 *
 * Free-preview caps are enforced with a wall-clock timer that pops back,
 * matching Android/iOS behavior.
 */
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
    // Free-preview cap: hard wall-clock timer that ends playback on all paths.
    LaunchedEffect(streamUrl, previewLimitMs) {
        val limit = previewLimitMs ?: return@LaunchedEffect
        delay(limit)
        onPreviewFinished()
    }

    var resolvedTarget by remember(streamUrl) {
        mutableStateOf<DesktopPlayerTarget?>(null)
    }
    var isResolving by remember(streamUrl) { mutableStateOf(true) }
    var resolveError by remember(streamUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(streamUrl) {
        isResolving = true
        resolveError = null
        resolvedTarget = runCatching {
            resolveDesktopPlayerTarget(streamUrl)
        }.onFailure {
            resolveError = it.message ?: "Could not resolve the stream."
        }.getOrNull()
        isResolving = false
    }

    when {
        isResolving -> DesktopPlayerLoading(episodeTitle, onBack)

        resolvedTarget != null -> {
            val target = resolvedTarget!!
            val webUrl = when (target) {
                is DesktopPlayerTarget.DirectVideo -> directVideoHtml(target.url)
                is DesktopPlayerTarget.Hls -> hlsVideoHtml(target.url)
                is DesktopPlayerTarget.Embed -> target.url
            }
            val browserUrl = when (target) {
                is DesktopPlayerTarget.DirectVideo -> target.url
                is DesktopPlayerTarget.Hls -> target.url
                is DesktopPlayerTarget.Embed -> target.url
            }
            DesktopWebPlayer(
                url = webUrl,
                title = episodeTitle,
                currentTheme = currentTheme,
                onBack = onBack,
                fallback = {
                    BrowserOpenFallback(
                        title = episodeTitle,
                        subtitle = if (target is DesktopPlayerTarget.Embed)
                            "Embedded playback isn't available on this device. Opening the player in your default browser."
                        else
                            "Video playback isn't available on this device. Opening the stream in your default browser.",
                        url = browserUrl,
                        accent = Color(0xFF00BFFF),
                        onBack = onBack
                    )
                }
            )
        }

        else -> BrowserOpenFallback(
            title = episodeTitle,
            subtitle = resolveError ?: "This stream could not be played on desktop.",
            url = streamUrl,
            accent = Color(0xFF00BFFF),
            onBack = onBack
        )
    }
}

/**
 * Resolution pipeline for a desktop stream URL. Mirrors the priority the
 * Android/iOS players use:
 *
 *  1. Direct .mp4/.webm/.mov/.mkv/.mpd  → HTML5 <video> (JavaFX MediaPlayer path)
 *  2. Direct .m3u8 HLS                   → hls.js wrapper
 *  3. Anything else (embed providers)    → scrape the page for a direct media
 *     URL; fall back to loading the page itself in the WebView
 */
private suspend fun resolveDesktopPlayerTarget(streamUrl: String): DesktopPlayerTarget? {
    if (streamUrl.isBlank()) return null

    if (isDirectMediaUrl(streamUrl)) return DesktopPlayerTarget.DirectVideo(streamUrl)
    if (isHlsUrl(streamUrl)) return DesktopPlayerTarget.Hls(streamUrl)

    // Try to extract a direct .m3u8/.mp4 URL out of the embed page scripts.
    val direct = runCatching {
        extractStreamFromEmbed(streamUrl, timeoutMs = 30_000L)
            ?.takeIf { isDirectMediaUrl(it) || isHlsUrl(it) }
    }.getOrNull()

    return when {
        direct != null && isDirectMediaUrl(direct) -> DesktopPlayerTarget.DirectVideo(direct)
        direct != null && isHlsUrl(direct) -> DesktopPlayerTarget.Hls(direct)
        else -> DesktopPlayerTarget.Embed(streamUrl)
    }
}

private sealed interface DesktopPlayerTarget {
    data class DirectVideo(val url: String) : DesktopPlayerTarget
    data class Hls(val url: String) : DesktopPlayerTarget
    data class Embed(val url: String) : DesktopPlayerTarget
}

@Composable
private fun DesktopPlayerLoading(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFF00BFFF))
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2
            )
            Text(
                "Resolving stream...",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Whether [target] should be treated as a browser fallback surface. */
internal fun isExternalPlayerTarget(url: String): Boolean =
    !isDirectMediaUrl(url) && !isHlsUrl(url)
