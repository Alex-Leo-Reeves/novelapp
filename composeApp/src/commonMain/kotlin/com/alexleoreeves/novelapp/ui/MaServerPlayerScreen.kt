package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import com.alexleoreeves.novelapp.data.AppTheme

/**
 * Expect declaration — platform-specific MA Server WebView embed player.
 *
 * Android: Uses WebView with JavaScript enabled, ad-blocking,
 * and full-screen orientation.
 *
 * This is NOT the same as AnimePlayerScreen (ExoPlayer).
 * This loads an embed URL directly in a WebView with ad blocking.
 *
 * @param embedUrl     The embed provider URL to load (e.g. vidsrc.to/embed/movie/123)
 * @param episodeTitle Display title shown in the player header
 * @param currentTheme App theme reference
 * @param onBack       Called when the user exits the player
 */
@Composable
expect fun MaServerPlayerScreen(
    embedUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
)
