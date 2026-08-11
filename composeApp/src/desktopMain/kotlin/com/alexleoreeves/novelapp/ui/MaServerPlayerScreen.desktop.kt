package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.alexleoreeves.novelapp.data.AppTheme
import kotlinx.coroutines.delay

/**
 * Desktop (Windows) actual for the MA Server embed player.
 *
 * Real in-app playback: the embed provider (VidSrc / VidLink / MultiEmbed /
 * Nontongo / AutoEmbed) loads inside a JavaFX WebView hosted in the Compose
 * window — equivalent to the iOS WKWebView implementation. When JavaFX can't
 * run on the host, it degrades to the themed browser-open surface instead of a
 * dead screen.
 *
 * Free-preview caps are enforced with a wall-clock timer that pops back,
 * matching Android/iOS behavior.
 */
@Composable
actual fun MaServerPlayerScreen(
    embedUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    previewLimitMs: Long?,
    onBack: () -> Unit
) {
    LaunchedEffect(embedUrl, previewLimitMs) {
        val limit = previewLimitMs ?: return@LaunchedEffect
        delay(limit)
        onBack()
    }

    DesktopWebPlayer(
        url = embedUrl,
        title = episodeTitle,
        currentTheme = currentTheme,
        onBack = onBack,
        fallback = {
            BrowserOpenFallback(
                title = episodeTitle,
                subtitle = rememberProviderName(embedUrl),
                url = embedUrl,
                accent = androidx.compose.ui.graphics.Color(0xFF00BFFF),
                onBack = onBack
            )
        }
    )
}

@Composable
private fun rememberProviderName(embedUrl: String): String {
    return androidx.compose.runtime.remember(embedUrl) {
        val host = runCatching {
            java.net.URI(embedUrl.trim()).host?.removePrefix("www.")
        }.getOrNull() ?: return@remember "Embedded server"
        when {
            "vidsrc" in host -> "VidSrc"
            "vidlink" in host -> "VidLink"
            "multiembed" in host || "streamingnow" in host -> "MultiEmbed"
            "nontongo" in host -> "Nontongo"
            "autoembed" in host -> "AutoEmbed"
            "embed" in host -> "Embed server"
            else -> host
        }
    }
}
