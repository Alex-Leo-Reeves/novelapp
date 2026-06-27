package com.alexleoreeves.novelapp

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

/**
 * Desktop entry point.
 * Launches the app in a maximized, titled window with minimum 1280x720 constraints.
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
        DesktopApp()
    }
}
