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

object AppReleaseConfig {
    val CURRENT_VERSION_CODE: Int get() = com.alexleoreeves.novelapp.tv.BuildConfig.VERSION_CODE
    val CURRENT_VERSION_NAME: String get() = com.alexleoreeves.novelapp.tv.BuildConfig.VERSION_NAME

    const val SERVER_BASE_URL = "https://novelapp1.onrender.com"
    const val API_BASE_URL = "https://novelapp1.onrender.com/api"
    const val UPDATE_MANIFEST_URL = "https://novelapp1.onrender.com/app-version.json"
    const val ANDROID_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk"
    const val ANDROID_TV_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.41/novelapp-androidtv.apk"
    const val DESKTOP_DOWNLOAD_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.41/novelapp-android.exe"
    const val DOWNLOAD_URL = ANDROID_TV_DOWNLOAD_URL
    const val KOKORO_MANIFEST_URL = "https://novelapp1.onrender.com/assets/kokoro/manifest.json"
}
