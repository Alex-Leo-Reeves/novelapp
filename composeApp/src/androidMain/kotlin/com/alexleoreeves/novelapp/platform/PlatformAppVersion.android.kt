package com.alexleoreeves.novelapp.platform

import com.alexleoreeves.novelapp.BuildConfig

actual object PlatformAppVersion {
    actual val versionCode: Int = BuildConfig.VERSION_CODE
    actual val versionName: String = BuildConfig.VERSION_NAME
}
