package com.alexleoreeves.novelapp.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.Serializable

@Serializable
data class SavedUserAccount(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val authToken: String = "",
    val plan: String = "free",
    val billingStatus: String = "none",
    val paidUntil: String? = null,
    val createdAt: String = "",
    val maxDevices: Int? = 2,
    val isPremium: Boolean = false
)

fun platformHttpClient(
    block: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit = {}
): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    block()
}

fun currentTimeMillis(): Long = System.currentTimeMillis()

/**
 * PERMANENT release-channel URLs.
 *
 * ⚠️ DO NOT CHANGE THESE URLS FOR A ROUTINE RELEASE.
 *
 * These are stable channel URLs. To ship a new build you REPLACE the binary
 * behind the URL (GitHub Release asset for APKs, the file served from Render
 * for the EXE/IPA) and update ONLY the versionCode/versionName/releaseNotes
 * plus the matching sha256/bytes in site/app-version.json.
 *
 *    Android APK   → replace the asset on the GitHub v1.39 release
 *    Android TV    → replace the asset on the GitHub v1.40 release
 *    Windows EXE   → replace the asset on the GitHub v1.41 release
 *                    (novelapp-android.exe)
 *    iOS IPA       → replace site/downloads/novelapp-ios.ipa (Render serves it;
 *                     NO auto-update — iOS can only manually sideload an IPA)
 *
 * If you ever think these need to change, stop and talk to the maintainer
 * first: every installed TV in the field is pointed at these URLs.
 */
object AppReleaseConfig {
    val CURRENT_VERSION_CODE: Int get() = com.alexleoreeves.novelapp.tv.BuildConfig.VERSION_CODE
    val CURRENT_VERSION_NAME: String get() = com.alexleoreeves.novelapp.tv.BuildConfig.VERSION_NAME

    const val SERVER_BASE_URL = "https://novelapp1.onrender.com"
    const val API_BASE_URL = "https://novelapp1.onrender.com/api"
    const val UPDATE_MANIFEST_URL = "https://novelapp1.onrender.com/app-version.json"

    // ── PERMANENT release channels (do not change) ──────────────────────────
    const val ANDROID_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk"
    const val ANDROID_TV_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.40/novelapp-androidtv.apk"
    const val DESKTOP_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.41/novelapp-android.exe"
    const val IOS_DOWNLOAD_URL = "https://novelapp1.onrender.com/downloads/novelapp-ios.ipa"
    const val DOWNLOAD_URL = ANDROID_TV_DOWNLOAD_URL
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
