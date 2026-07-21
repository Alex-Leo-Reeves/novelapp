package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import com.alexleoreeves.novelapp.data.AppTheme

/**
 * Expect declaration — platform-specific full-screen anime video player.
 *
 * Android: Uses Media3 ExoPlayer in landscape immersive mode.
 * iOS: Stub (AVPlayer integration can be added).
 *
 * @param streamUrl       The HLS .m3u8 stream URL to play
 * @param episodeTitle    Display title shown in the player header
 * @param currentTheme    App theme reference
 * @param initialPositionMs Saved playback position to resume from
 * @param onProgress      Called periodically with playback position
 * @param onBack          Called when the user exits the player
 * @param contentKind     "anime" for Japanese anime, "donghua" for Chinese content,
 *                        empty string for regular content with no audio/sub controls
 */
@Composable
expect fun AnimePlayerScreen(
    streamUrl: String,
    episodeTitle: String,
    currentTheme: AppTheme,
    initialPositionMs: Long = 0L,
    onProgress: (Long) -> Unit = {},
    previewLimitMs: Long? = null,
    onPreviewFinished: () -> Unit = {},
    contentKind: String = "",
    subtitlesJson: String? = null,
    onBack: () -> Unit
)
