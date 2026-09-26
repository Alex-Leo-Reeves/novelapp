@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Uses AVPlayer instead of a WKWebView so real media buffering controls apply:
 *  - automaticallyWaitsToMinimizeStalling keeps AVPlayer's stall-avoidance
 *    on: it pre-buffers ahead instead of playing into an empty buffer and
 *    pausing.
 *  - preferredForwardBufferDuration asks AVPlayer to keep ~90 s of media
 *    cached ahead of the playhead, so brief network dips never pause
 *    playback (WebView/WebCore buffering is not tunable at all).
 *
 * [onReady] fires once playback actually starts; [onFailed] fires on a
 * player error or if nothing has started within the watchdog window, so the
 * caller can show its Retry overlay instead of an endless spinner.
 */
@Composable
fun IosOnlinePlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onFailed: (String) -> Unit = {}
) {
    var playerRef by remember(streamUrl) { mutableStateOf<AVPlayer?>(null) }
    var settled by remember(streamUrl) { mutableStateOf(false) }

    UIKitView(
        factory = {
            val url = NSURL.URLWithString(streamUrl)
            val player = url?.let { AVPlayer.playerWithURL(it) }
            runCatching {
                player?.automaticallyWaitsToMinimizeStalling = true
                player?.currentItem?.preferredForwardBufferDuration = 90.0
            }
            playerRef = player

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
            playerRef = null
        }
    )

    // Readiness / failure watchdog — polls plain AVPlayer properties (rate,
    // error) instead of KVO so no enum/observer interop is involved.
    LaunchedEffect(streamUrl) {
        var elapsed = 0
        while (elapsed < 45) {
            delay(1_000)
            elapsed++
            val player = playerRef ?: continue
            val item = player.currentItem
            if (item?.error != null) {
                if (!settled) {
                    settled = true
                    onFailed("The stream failed to play. Tap Retry.")
                }
                break
            }
            if (player.rate > 0f) {
                if (!settled) {
                    settled = true
                    onReady()
                }
                break
            }
        }
        if (!settled) {
            settled = true
            onFailed("Stream is taking too long to respond. Tap Retry.")
        }
    }
}
