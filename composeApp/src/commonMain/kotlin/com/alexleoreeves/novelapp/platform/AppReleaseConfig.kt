package com.alexleoreeves.novelapp.platform

object AppReleaseConfig {
    /** Derives from PlatformAppVersion so the actual compiled version is always used. */
    val CURRENT_VERSION_CODE: Int get() = PlatformAppVersion.versionCode
    val CURRENT_VERSION_NAME: String get() = PlatformAppVersion.versionName

    const val SERVER_BASE_URL = "https://novelapp1.onrender.com"
    const val API_BASE_URL = "https://novelapp1.onrender.com/api"
    const val UPDATE_MANIFEST_URL = "https://novelapp1.onrender.com/app-version.json"
    const val ANDROID_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk"
    const val ANDROID_TV_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.40/novelapp-androidtv.apk"
    const val DESKTOP_DOWNLOAD_URL = "https://novelapp1.onrender.com/downloads/novelapp-windows.exe"
    const val IOS_DOWNLOAD_URL = "https://novelapp1.onrender.com/downloads/novelapp-ios.ipa"
    const val DOWNLOAD_URL = ANDROID_DOWNLOAD_URL
    const val KOKORO_MANIFEST_URL = "https://novelapp1.onrender.com/assets/kokoro/manifest.json"
}

/** Which artifact the running platform should update toward. */
enum class AppUpdateTarget {
    ANDROID,
    ANDROID_TV,
    IOS,
    DESKTOP;

    fun downloadUrl(): String = when (this) {
        ANDROID -> AppReleaseConfig.ANDROID_DOWNLOAD_URL
        ANDROID_TV -> AppReleaseConfig.ANDROID_TV_DOWNLOAD_URL
        IOS -> AppReleaseConfig.IOS_DOWNLOAD_URL
        DESKTOP -> AppReleaseConfig.DESKTOP_DOWNLOAD_URL
    }
}

object DeveloperContact {
    const val NAME = "Mike"
    const val EMAIL = "masteralexleoreevesd1@gmail.com"
    const val TELEGRAM_CHANNEL_URL = "https://t.me/developeralexd1"
    const val WHATSAPP_CHANNEL_URL = "https://whatsapp.com/channel/0029Vb8fgDa2P59cCnEkWW3I"
}
