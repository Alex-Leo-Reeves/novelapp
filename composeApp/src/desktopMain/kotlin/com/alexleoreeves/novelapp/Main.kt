package com.alexleoreeves.novelapp

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.alexleoreeves.novelapp.platform.AppUpdateTarget
import com.alexleoreeves.novelapp.platform.DesktopExternalLinkOpener
import com.alexleoreeves.novelapp.platform.DesktopUserSessionStore

/**
 * Desktop (Windows) entry point.
 *
 * Boots the *same* shared Compose Multiplatform app the Android APK, iOS IPA,
 * and Android TV builds run — Discover, NMC novels, Sports, Universal Read,
 * Downloads, You (profile/auth/premium), OTP flows, cloud sync, narration —
 * with a desktop session store and browser link opener wired in.
 */
fun main() = application {
    val state = rememberWindowState(
        placement = WindowPlacement.Maximized,
        size = DpSize(1440.dp, 900.dp)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Watch Anime · Read Novels · Read Manga — All in One",
        state = state,
        resizable = true,
    ) {
        // Set minimum window size in window properties
        window.minimumSize = java.awt.Dimension(1280, 720)

        val sessionStore = remember { DesktopUserSessionStore() }
        val linkOpener = remember { DesktopExternalLinkOpener() }

        App(
            userSessionStore = sessionStore,
            linkOpener = linkOpener,
            updateTarget = AppUpdateTarget.DESKTOP
        )
    }
}
