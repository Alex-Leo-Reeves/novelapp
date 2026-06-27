package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import com.alexleoreeves.novelapp.ui.theme.AppTheme

/**
 * Expect declaration — platform-specific full-screen anime video player.
 *
 * Android: Uses Media3 ExoPlayer in landscape immersive mode.
 * iOS: Stub (AVPlayer integration can be added).
 *
 * @param streamUrl     The HLS .m3u8 stream URL to play
 * @param episodeTitle  Display title shown in the player header
 * @param currentTheme  App theme reference
 * @param onBack        Called when the user exits the player
 */
@Composable
expect fun AnimePlayerScreen(
    streamUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
)
