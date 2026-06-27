package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

class IosExternalLinkOpener : ExternalLinkOpener {
    override fun open(url: String) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
    }
}
