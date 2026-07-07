package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSBundle

actual object PlatformAppVersion {
    actual val versionCode: Int = NSBundle.mainBundle
        .objectForInfoDictionaryKey("CFBundleVersion")
        ?.toString()
        ?.toIntOrNull()
        ?: 1

    actual val versionName: String = NSBundle.mainBundle
        .objectForInfoDictionaryKey("CFBundleShortVersionString")
        ?.toString()
        ?: "1.0"
}
