package com.alexleoreeves.novelapp.platform

/**
 * Desktop fallback — must be kept in sync with
 * compose.desktop.application.nativeDistributions.packageVersion
 * in build.gradle.kts.
 */
actual object PlatformAppVersion {
    actual val versionCode: Int = 38
    actual val versionName: String = "1.38"
}
