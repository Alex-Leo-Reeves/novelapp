package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class AppUpdateManifest(
    val versionCode: Int = AppReleaseConfig.CURRENT_VERSION_CODE,
    val versionName: String = AppReleaseConfig.CURRENT_VERSION_NAME,
    val apkUrl: String = AppReleaseConfig.DOWNLOAD_URL,
    val apkSha256: String = "",
    val apkBytes: Long = 0L,
    val releaseNotes: List<String> = emptyList(),
    val forceUpdate: Boolean = false
) {
    val isAvailable: Boolean
        get() = versionCode > AppReleaseConfig.CURRENT_VERSION_CODE
}

suspend fun fetchAppUpdateManifest(client: HttpClient): AppUpdateManifest? =
    runCatching {
        val response = client.get(AppReleaseConfig.UPDATE_MANIFEST_URL).body<String>()
        Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString<AppUpdateManifest>(response)
    }.getOrNull()
