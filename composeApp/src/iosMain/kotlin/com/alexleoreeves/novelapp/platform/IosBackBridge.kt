package com.alexleoreeves.novelapp.platform

/**
 * Bridge between the iOS system edge-swipe gesture
 * (see `iosApp/iosApp/iOSApp.swift`, `UIScreenEdgePanGestureRecognizer`) and the
 * shared Compose back stack.
 *
 * Swift calls [triggerBack] when the user swipes in from the left edge; the iOS
 * actual of `PlatformBackHandler` registers/unregisters the currently-enabled
 * handler while screens push/pop. When no handler is registered the gesture is a
 * no-op, so the root screen is never affected.
 *
 * iOS-only file — Android/desktop use their own `PlatformBackHandler` actuals
 * and never see this object.
 */
object IosBackBridge {
    private var handler: (() -> Unit)? = null

    fun register(handler: () -> Unit) {
        this.handler = handler
    }

    fun unregister() {
        handler = null
    }

    /** Invokes the active back handler. @return true when the gesture was consumed. */
    fun triggerBack(): Boolean {
        val active = handler ?: return false
        active()
        return true
    }
}
