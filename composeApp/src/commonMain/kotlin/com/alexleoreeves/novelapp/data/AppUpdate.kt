package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        get() = compareVersions(versionName, AppReleaseConfig.CURRENT_VERSION_NAME) > 0 || versionCode > AppReleaseConfig.CURRENT_VERSION_CODE
}

private fun compareVersions(v1: String, v2: String): Int {
    val clean1 = v1.removePrefix("v").trim()
    val clean2 = v2.removePrefix("v").trim()
    if (clean1 == clean2) return 0
    val parts1 = clean1.split(".").mapNotNull { it.toIntOrNull() }
    val parts2 = clean2.split(".").mapNotNull { it.toIntOrNull() }
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1.compareTo(p2)
    }
    return 0
}

suspend fun fetchAppUpdateManifest(client: HttpClient, isTv: Boolean = false, isDesktop: Boolean = false): AppUpdateManifest? {
    val defaultUrl = when {
        isTv -> AppReleaseConfig.ANDROID_TV_DOWNLOAD_URL
        isDesktop -> AppReleaseConfig.DESKTOP_DOWNLOAD_URL
        else -> AppReleaseConfig.ANDROID_DOWNLOAD_URL
    }

    val githubResult = runCatching {
        val response = client.get("https://api.github.com/repos/Alex-Leo-Reeves/novelapp/releases/latest").body<String>()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val root = json.parseToJsonElement(response).jsonObject
        val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return@runCatching null
        val bodyText = root["body"]?.jsonPrimitive?.contentOrNull ?: ""
        AppUpdateManifest(
            versionCode = AppReleaseConfig.CURRENT_VERSION_CODE + 1,
            versionName = tagName,
            apkUrl = defaultUrl,
            releaseNotes = bodyText.split("\n").filter { it.isNotBlank() }
        )
    }.getOrNull()

    if (githubResult != null && githubResult.isAvailable) {
        return githubResult
    }

    return runCatching {
        val response = client.get(AppReleaseConfig.UPDATE_MANIFEST_URL).body<String>()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val parsed = json.decodeFromString<AppUpdateManifest>(response)
        parsed.copy(apkUrl = if (parsed.apkUrl.isBlank() || parsed.apkUrl == AppReleaseConfig.DOWNLOAD_URL) defaultUrl else parsed.apkUrl)
    }.getOrNull()
}
