package com.alexleoreeves.novelapp.platform

/**
 * Lets the iOS actual of `MangaReaderSystemUiEffect` tell the SwiftUI host
 * (see `iosApp/iosApp/iOSApp.swift`, class `ImmersiveBridge`) to enter/leave
 * immersive full-screen mode for the manga reader — the iOS equivalent of
 * Android hiding the system bars (see MangaReaderSystemUi.android.kt).
 *
 * iOS-only file — Android/desktop never see this object.
 */
object IosSystemUiBridge {
    private var listener: ((Boolean) -> Unit)? = null

    /** Called by the Swift host when the Compose view controller is created. */
    fun setListener(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
    }

    /** Called by MangaReaderSystemUiEffect whenever immersive mode changes. */
    fun setImmersive(enabled: Boolean) {
        listener?.invoke(enabled)
    }
}
