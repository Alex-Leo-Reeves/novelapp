@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS offline player — hosts an [AVPlayer] inside a `UIView` for local media
 * (`file://` mp4 or bundled m3u8 with relative segment/key URIs). AVPlayer
 * handles local HLS playlists natively, so no extra network is needed.
 */
@Composable
fun IosOfflinePlayer(
    localPath: String,
    modifier: Modifier = Modifier,
    onPlaybackEnded: () -> Unit = {}
) {
    val url = remember(localPath) {
        val clean = localPath.removePrefix("file://")
        NSURL.fileURLWithPath(clean)
    }

    UIKitView(
        factory = {
            val player = AVPlayer.playerWithURL(url)
            val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
            playerLayer.videoGravity = platform.AVFoundation.AVLayerVideoGravityResizeAspect

            val container = UIView(frame = CGRectZero.readValue())
            container.layer.addSublayer(playerLayer)
            playerLayer.frame = container.bounds

            // Auto-play once the layer is attached to the view hierarchy.
            dispatch_async(dispatch_get_main_queue()) {
                player.play()
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
