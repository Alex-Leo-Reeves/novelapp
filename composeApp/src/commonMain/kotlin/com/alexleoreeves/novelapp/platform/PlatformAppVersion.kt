package com.alexleoreeves.novelapp.platform

/**
 * Single source of truth for the running app's version.
 *
 * On Android this reads BuildConfig.VERSION_CODE / VERSION_NAME.
 * On iOS  this reads CFBundleVersion / CFBundleShortVersionString.
 * On Desktop a hardcoded fallback (must match compose.desktop.application.packageVersion).
 */
expect object PlatformAppVersion {
    val versionCode: Int
    val versionName: String
}
