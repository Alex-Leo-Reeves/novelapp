package com.alexleoreeves.novelapp.platform

object AppReleaseConfig {
    /** Derives from PlatformAppVersion so the actual compiled version is always used. */
    val CURRENT_VERSION_CODE: Int get() = PlatformAppVersion.versionCode
    val CURRENT_VERSION_NAME: String get() = PlatformAppVersion.versionName

    const val API_BASE_URL = "https://novelapp1.onrender.com/api"
    const val UPDATE_MANIFEST_URL = "https://novelapp1.onrender.com/app-version.json"
    const val DOWNLOAD_URL = "https://novelapp1.onrender.com/downloads/novelapp-android.apk"
    const val KOKORO_MANIFEST_URL = "https://novelapp1.onrender.com/assets/kokoro/manifest.json"
}

object DeveloperContact {
    const val NAME = "Mike"
    const val EMAIL = "masteralexleoreevesd1@gmail.com"
    const val TELEGRAM_CHANNEL_URL = "https://t.me/developeralexd1"
    const val WHATSAPP_CHANNEL_URL = "https://whatsapp.com/channel/0029Vb8fgDa2P59cCnEkWW3I"
}
