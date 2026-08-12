package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS link opener.
 *
 * ⚠️ iOS has NO auto-update / auto-install. iOS cannot self-install an IPA
 * outside the App Store / device management profile, so the update button
 * only opens the IPA download URL (AppReleaseConfig.IOS_DOWNLOAD_URL — a
 * PERMANENT channel; never change it for a routine release). The user
 * downloads the IPA and installs it with their sideloading tool.
 *
 * Never add "install IPA automatically" logic here — it will not work.
 */
class IosExternalLinkOpener : ExternalLinkOpener {
    override fun open(url: String) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
    }
}
