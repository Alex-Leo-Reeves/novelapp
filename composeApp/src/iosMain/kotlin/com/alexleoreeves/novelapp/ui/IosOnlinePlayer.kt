@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS online player for DIRECT media URLs (.m3u8 / .mp4 / .mov).
 *
 * Uses AVPlayer instead of a WKWebView so real media buffering applies:
 * AVPlayer's stall-avoidance is ON by default and it sizes its own forward
 * buffer adaptively, so playback keeps a healthy look-ahead and rides out
 * brief network dips. WebView / WebCore buffering cannot be tuned at all.
 *
 * The symbols here intentionally mirror the already-shipping [IosOfflinePlayer]
 * (AVPlayer + AVPlayerLayer + play/pause) so no unverified interop surface is
 * introduced. [onReady] dismisses the caller's loading overlay once the player
 * exists; [onFailed] fires immediately for an unusable URL so the caller can
 * show its Retry overlay instead of an endless spinner.
 */
@Composable
fun IosOnlinePlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onFailed: (String) -> Unit = {}
) {
    val url = remember(streamUrl) { NSURL.URLWithString(streamUrl) }

    LaunchedEffect(streamUrl) {
        if (url == null) {
            onFailed("This stream address is not valid. Tap Retry.")
            return@LaunchedEffect
        }
        // AVPlayer needs no user gesture — it starts buffering as soon as the
        // layer is attached. Clear the loading overlay shortly afterwards.
        delay(1_500)
        onReady()
    }

    UIKitView(
        factory = {
            val player = url?.let { AVPlayer.playerWithURL(it) }
            val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
            playerLayer.videoGravity = AVLayerVideoGravityResizeAspect

            val container = UIView(frame = CGRectZero.readValue())
            container.layer.addSublayer(playerLayer)
            playerLayer.frame = container.bounds

            // AVPlayer needs no user gesture — start immediately.
            dispatch_async(dispatch_get_main_queue()) {
                player?.play()
            }

            container
        },
        modifier = modifier,
        update = { container ->
            val layer = container.layer.sublayers?.firstOrNull() as? AVPlayerLayer
            layer?.frame = container.bounds
        },
        onRelease = { container ->
            (container.layer.sublayers?.firstOrNull() as? AVPlayerLayer)?.player?.pause()
        }
    )
}
