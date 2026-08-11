package com.alexleoreeves.novelapp.platform

import java.awt.Desktop
import java.net.URI

/**
 * Desktop (Windows) link opener — opens URLs (subscription checkout, APK
 * update links, etc.) in the default browser.
 */
class DesktopExternalLinkOpener : ExternalLinkOpener {
    override fun open(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }.onFailure {
            println("[DesktopLinkOpener] Could not open $url: ${it.message}")
        }
    }
}
