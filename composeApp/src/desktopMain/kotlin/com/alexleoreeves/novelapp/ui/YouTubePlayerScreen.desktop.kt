package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.alexleoreeves.novelapp.data.AppTheme

/**
 * Desktop (Windows) actual for the YouTube player.
 *
 * Real in-app playback: the YouTube embed player loads inside the JavaFX
 * WebView — equivalent to the iOS WKWebView implementation. When JavaFX can't
 * run, it degrades to the themed browser-open surface opening the watch URL.
 */
@Composable
actual fun YouTubePlayerScreen(
    videoId: String,
    title: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
) {
    val embedUrl = remember(videoId) {
        "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
    }
    val watchUrl = remember(videoId) { "https://www.youtube.com/watch?v=$videoId" }

    DesktopWebPlayer(
        url = embedUrl,
        title = title,
        currentTheme = currentTheme,
        onBack = onBack,
        fallback = {
            BrowserOpenFallback(
                title = title,
                subtitle = "YouTube playback opens in your default browser when in-app playback is unavailable.",
                url = watchUrl,
                accent = Color(0xFFFF3B30),
                onBack = onBack
            )
        }
    )
}
