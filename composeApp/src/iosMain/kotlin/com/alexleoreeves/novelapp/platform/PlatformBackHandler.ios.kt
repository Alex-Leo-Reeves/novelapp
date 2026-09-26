package com.alexleoreeves.novelapp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * iOS actual: exposes the currently-enabled back handler to [IosBackBridge] so
 * the Swift edge-swipe gesture can pop screens the same way the Android system
 * back gesture does. `enabled = false` (root screen / nothing to pop) leaves the
 * bridge unregistered and the gesture becomes a no-op.
 */
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (enabled) {
            IosBackBridge.register { currentOnBack() }
        }
        onDispose { IosBackBridge.unregister() }
    }
}
