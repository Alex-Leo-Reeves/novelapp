package com.alexleoreeves.novelapp

import androidx.compose.ui.window.ComposeUIViewController
import com.alexleoreeves.novelapp.platform.IosExternalLinkOpener
import com.alexleoreeves.novelapp.platform.IosUserSessionStore

fun MainViewController() = ComposeUIViewController {
    App(
        userSessionStore = IosUserSessionStore(),
        linkOpener = IosExternalLinkOpener()
    )
}
