package com.alexleoreeves.novelapp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * iOS actual: asks the Swift host ([IosSystemUiBridge] → `ImmersiveBridge`) to
 * collapse the safe area and hide the status bar while the manga reader is
 * full-screen — mirroring Android's immersive system-bar hiding
 * (see MangaReaderSystemUi.android.kt).
 */
@Composable
actual fun MangaReaderSystemUiEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        IosSystemUiBridge.setImmersive(enabled)
        onDispose { IosSystemUiBridge.setImmersive(false) }
    }
}
