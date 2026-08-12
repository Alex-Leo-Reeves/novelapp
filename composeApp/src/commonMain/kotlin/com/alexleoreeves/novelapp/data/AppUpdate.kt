package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.AppUpdateTarget
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Update manifest — mirrors site/app-version.json.
 *
 * ⚠️ The download URLs are PERMANENT release channels (defined in
 * AppReleaseConfig). NEVER rewrite apkUrl / tvApkUrl / desktopUrl / ipaUrl for
 * a routine release — you REPLACE the binary behind each URL and update only
 * the version fields plus sha256/bytes.
 *
 * Each platform ships on its OWN release cadence:
 *   - top-level versionCode/versionName/releaseNotes = Android phone channel
 *   - tvVersionCode/tvVersionName/tvReleaseNotes    = Android TV channel
 *   - desktopVersionCode/desktopVersionName/desktopReleaseNotes = Windows EXE
 * When a per-platform override is null it falls back to the shared top-level
 * values, so a single-version manifest keeps working unchanged.
 *
 * Hash/size fields are optional per platform:
 *   - `""` / `0`  → integrity check is SKIPPED for that platform until you
 *                   provide the values after building.
 *   - filled      → the in-app downloader verifies SHA-256 + byte size before
 *                   installing / launching.
 *
 * iOS deliberately has NO auto-update: iOS cannot self-install an IPA outside
 * the App Store / a device-management profile, so the iOS update button only
 * opens ipaUrl (manual download). Do not add auto-install logic for iOS.
 */
@Serializable
data class AppUpdateManifest(
    val versionCode: Int = AppReleaseConfig.CURRENT_VERSION_CODE,
    val versionName: String = AppReleaseConfig.CURRENT_VERSION_NAME,
    // Android phone APK
    val apkUrl: String = AppReleaseConfig.ANDROID_DOWNLOAD_URL,
    val apkSha256: String = "",
    val apkBytes: Long = 0L,
    // Android TV APK
    val tvApkUrl: String = AppReleaseConfig.ANDROID_TV_DOWNLOAD_URL,
    val tvApkSha256: String = "",
    val tvApkBytes: Long = 0L,
    // Windows desktop installer (EXE/MSI)
    val desktopUrl: String = AppReleaseConfig.DESKTOP_DOWNLOAD_URL,
    val desktopSha256: String = "",
    val desktopBytes: Long = 0L,
    // iOS IPA — download only, no auto-install
    val ipaUrl: String = AppReleaseConfig.IOS_DOWNLOAD_URL,
    val ipaSha256: String = "",
    val ipaBytes: Long = 0L,
    // ── Per-platform version overrides (null = use top-level values) ────────
    val tvVersionCode: Int? = null,
    val tvVersionName: String? = null,
    val tvReleaseNotes: List<String>? = null,
    val desktopVersionCode: Int? = null,
    val desktopVersionName: String? = null,
    val desktopReleaseNotes: List<String>? = null,
    // iOS ships on its OWN track too: iosVersionCode/Name must match the
    // iOS build (iosApp/project.yml CURRENT_PROJECT_VERSION / MARKETING_VERSION).
    // Without these, iOS falls back to the Android top-level values and would
    // prompt "update available" whenever the Android phone APK is bumped.
    val iosVersionCode: Int? = null,
    val iosVersionName: String? = null,
    val iosReleaseNotes: List<String>? = null,
    val releaseNotes: List<String> = emptyList(),
    val forceUpdate: Boolean = false
) {
    /** Android-phone availability (top-level channel). Back-compat alias. */
    val isAvailable: Boolean
        get() = compareVersions(versionName, AppReleaseConfig.CURRENT_VERSION_NAME) > 0 ||
            versionCode > AppReleaseConfig.CURRENT_VERSION_CODE

    /** Version code for the given platform's own channel. */
    fun versionCodeFor(target: AppUpdateTarget): Int = when (target) {
        AppUpdateTarget.ANDROID_TV -> tvVersionCode ?: versionCode
        AppUpdateTarget.DESKTOP -> desktopVersionCode ?: versionCode
        AppUpdateTarget.IOS -> iosVersionCode ?: versionCode
        else -> versionCode
    }

    /** Version name for the given platform's own channel. */
    fun versionNameFor(target: AppUpdateTarget): String = when (target) {
        AppUpdateTarget.ANDROID_TV -> tvVersionName ?: versionName
        AppUpdateTarget.DESKTOP -> desktopVersionName ?: versionName
        AppUpdateTarget.IOS -> iosVersionName ?: versionName
        else -> versionName
    }

    /** Release notes for the given platform's own channel. */
    fun releaseNotesFor(target: AppUpdateTarget): List<String> = when (target) {
        AppUpdateTarget.ANDROID_TV -> tvReleaseNotes ?: releaseNotes
        AppUpdateTarget.DESKTOP -> desktopReleaseNotes ?: releaseNotes
        AppUpdateTarget.IOS -> iosReleaseNotes ?: releaseNotes
        else -> releaseNotes
    }

    /**
     * Whether an update is available FOR THIS PLATFORM.
     *
     * Each installed app compares its own local version against its channel's
     * server version — TV compares against tvVersionCode/tvVersionName, the
     * desktop EXE against desktopVersionCode/desktopVersionName, and the
     * Android phone against the top-level values. This keeps TV and EXE on
     * independent release tracks without false "update available" prompts.
     */
    fun isAvailableFor(target: AppUpdateTarget): Boolean {
        val localCode = AppReleaseConfig.CURRENT_VERSION_CODE
        val localName = AppReleaseConfig.CURRENT_VERSION_NAME
        val remoteCode = versionCodeFor(target)
        val remoteName = versionNameFor(target)
        return compareVersions(remoteName, localName) > 0 || remoteCode > localCode
    }

    /** URL the running platform should update from. Falls back to the permanent channel. */
    fun downloadUrlFor(target: AppUpdateTarget): String = when (target) {
        AppUpdateTarget.ANDROID -> apkUrl.ifBlank { AppReleaseConfig.ANDROID_DOWNLOAD_URL }
        AppUpdateTarget.ANDROID_TV -> tvApkUrl.ifBlank { AppReleaseConfig.ANDROID_TV_DOWNLOAD_URL }
        AppUpdateTarget.IOS -> ipaUrl.ifBlank { AppReleaseConfig.IOS_DOWNLOAD_URL }
        AppUpdateTarget.DESKTOP -> desktopUrl.ifBlank { AppReleaseConfig.DESKTOP_DOWNLOAD_URL }
    }

    /** Expected SHA-256 for the target artifact ("" = skip check until provided). */
    fun sha256For(target: AppUpdateTarget): String = when (target) {
        AppUpdateTarget.ANDROID -> apkSha256
        AppUpdateTarget.ANDROID_TV -> tvApkSha256
        AppUpdateTarget.IOS -> ipaSha256
        AppUpdateTarget.DESKTOP -> desktopSha256
    }

    /** Expected byte size for the target artifact (0 = skip size check until provided). */
    fun bytesFor(target: AppUpdateTarget): Long = when (target) {
        AppUpdateTarget.ANDROID -> apkBytes
        AppUpdateTarget.ANDROID_TV -> tvApkBytes
        AppUpdateTarget.IOS -> ipaBytes
        AppUpdateTarget.DESKTOP -> desktopBytes
    }
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

/**
 * Fetch the update manifest.
 *
 * The AUTHORITATIVE source is AppReleaseConfig.UPDATE_MANIFEST_URL
 * (site/app-version.json) because it carries the sha256/bytes that every
 * platform verifies. The GitHub latest-release is only a FALLBACK when the
 * server manifest is unreachable or not newer — it has no hashes, so the
 * installers fall back to signature / OS verification.
 */
suspend fun fetchAppUpdateManifest(
    client: HttpClient,
    target: AppUpdateTarget = AppUpdateTarget.ANDROID
): AppUpdateManifest? {
    val serverManifest = runCatching {
        val response = client.get(AppReleaseConfig.UPDATE_MANIFEST_URL).body<String>()
        Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString<AppUpdateManifest>(response)
    }.getOrNull()

    // Accept the manifest when the TARGET platform's own channel is newer.
    // TV compares tvVersionCode/tvVersionName and desktop compares its own
    // channel, so a TV-only or EXE-only bump still surfaces on that platform.
    if (serverManifest != null && serverManifest.isAvailableFor(target)) {
        return serverManifest
    }

    // The GitHub "latest release" fallback is ANDROID-ONLY and version-gated.
    //
    // It exists purely to keep the Android phone APK able to discover a release
    // even if the Render manifest is temporarily unreachable. It MUST NOT
    // fabricate a prompt for TV / Windows EXE / iOS: those platforms compare
    // against their OWN channel versions (tvVersion*, desktopVersion*,
    // iosVersion*) in site/app-version.json, and a routine Android GitHub tag
    // bump must never surface as an update there.
    if (target == AppUpdateTarget.ANDROID) {
        val githubResult = runCatching {
            val response = client.get("https://api.github.com/repos/Alex-Leo-Reeves/novelapp/releases/latest").body<String>()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val root = json.parseToJsonElement(response).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v")
                ?: return@runCatching null
            val bodyText = root["body"]?.jsonPrimitive?.contentOrNull ?: ""
            AppUpdateManifest(
                // The GH fallback has no hashes/sizes; keep Android's known code
                // baseline and gate purely on the tag name being NEWER below.
                versionCode = AppReleaseConfig.CURRENT_VERSION_CODE,
                versionName = tagName,
                releaseNotes = bodyText.split("\n").filter { it.isNotBlank() }
            )
        }.getOrNull()

        // Only accept the fallback when the GitHub tag is genuinely newer than
        // the installed version — never a fabricated unconditional prompt.
        if (githubResult != null &&
            compareVersions(githubResult.versionName, AppReleaseConfig.CURRENT_VERSION_NAME) > 0
        ) {
            return githubResult
        }
    }

    return serverManifest
}
