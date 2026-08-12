package com.alexleoreeves.novelapp.platform

import com.alexleoreeves.novelapp.data.AppUpdatePhase
import com.alexleoreeves.novelapp.data.AppUpdateProgressBus
import com.alexleoreeves.novelapp.data.AppUpdateProgressState
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Desktop (Windows) link opener.
 *
 * - Non-installer URLs (subscription checkout, etc.) open in the default browser.
 * - The desktop installer URL (novelapp-android.exe on the GitHub v1.41
 *   release — the PERMANENT channel in AppReleaseConfig) is downloaded in-app
 *   with optional SHA-256 + byte-size verification, then launched.
 *
 * ⚠️ The download URL is the permanent channel URL. Never rewrite it for a
 * routine release — see AppReleaseConfig. If the manifest has no desktop
 * hash/size yet, integrity verification is skipped and the OS installer is
 * launched directly (Windows installer validation still applies).
 */
class DesktopExternalLinkOpener : ExternalLinkOpener {

    private data class DesktopManifestInfo(
        val url: String,
        val sha256: String,
        val bytes: Long
    )

    override fun open(url: String) {
        if (url.contains("novelapp-windows.exe", ignoreCase = true) ||
            url.contains("novelapp-windows.msi", ignoreCase = true) ||
            url.contains("novelapp-android.exe", ignoreCase = true)
        ) {
            installDesktopUpdate(url)
            return
        }
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }.onFailure {
            println("[DesktopLinkOpener] Could not open $url: ${it.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  installDesktopUpdate — download with verification, then launch
    // ─────────────────────────────────────────────────────────────────────────
    private fun installDesktopUpdate(url: String) {
        Thread {
            var lastException: Throwable? = null
            for (attempt in 1..3) {
                val result = runCatching { downloadAndLaunch(url) }
                if (result.isSuccess) {
                    result.getOrThrow()
                    return@Thread
                }
                lastException = result.exceptionOrNull()
                if (attempt < 3) {
                    val delay = attempt * 5000L
                    AppUpdateProgressBus.update(
                        AppUpdateProgressState(
                            isActive = true,
                            phase = AppUpdatePhase.Downloading,
                            message = "Retrying (attempt ${attempt + 1}/3)..."
                        )
                    )
                    Thread.sleep(delay)
                }
            }
            val msg = when (lastException) {
                is java.net.SocketTimeoutException -> "Download timed out — slow network."
                else -> lastException?.message ?: "try again"
            }
            AppUpdateProgressBus.update(
                AppUpdateProgressState(
                    isActive = true,
                    phase = AppUpdatePhase.Error,
                    message = "Update download failed: $msg",
                    canDismiss = true,
                    isError = true
                )
            )
        }.start()
    }

    private fun downloadAndLaunch(url: String) {
        val manifest = fetchManifest(url)
        val updateDir = File(System.getProperty("java.io.tmpdir"), "novelapp-updates").apply { mkdirs() }
        val targetFile = File(
            updateDir,
            when {
                url.contains(".msi", ignoreCase = true) -> "novelapp-windows.msi"
                else -> "novelapp-android.exe"
            }
        )
        val tmpFile = File(updateDir, "${targetFile.name}.part")
        if (targetFile.exists()) targetFile.delete()
        if (tmpFile.exists()) tmpFile.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NovelApp/${AppReleaseConfig.CURRENT_VERSION_NAME}")
        }
        if (connection.responseCode !in 200..299) {
            error("server returned HTTP ${connection.responseCode}")
        }

        // Expected size: prefer the manifest value, fall back to Content-Length.
        val manifestBytes = manifest?.bytes?.takeIf { it > 0L }
        val contentLengthBytes = connection.contentLengthLong.takeIf { it > 0L }
        val expectedBytes = manifestBytes ?: contentLengthBytes ?: -1L

        val expectedSha256 = manifest?.sha256
            ?.lowercase()
            ?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

        // Without a manifest hash we skip checksum verification. Windows will
        // still show its own installer validation before installing.
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }

        var downloadedBytes = 0L
        connection.inputStream.use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    digest?.update(buffer, 0, read)
                    if (expectedBytes > 0L) {
                        val progress = ((downloadedBytes * 100) / expectedBytes).toInt().coerceIn(0, 100)
                        AppUpdateProgressBus.update(
                            AppUpdateProgressState(
                                isActive = true,
                                phase = AppUpdatePhase.Downloading,
                                message = "Downloading update... $progress%",
                                receivedBytes = downloadedBytes,
                                totalBytes = expectedBytes
                            )
                        )
                    } else {
                        AppUpdateProgressBus.update(
                            AppUpdateProgressState(
                                isActive = true,
                                phase = AppUpdatePhase.Downloading,
                                message = "Downloading update...",
                                receivedBytes = downloadedBytes
                            )
                        )
                    }
                }
            }
        }
        connection.disconnect()

        if (downloadedBytes <= 0L) {
            tmpFile.delete()
            error("downloaded file was empty")
        }

        AppUpdateProgressBus.update(
            AppUpdateProgressState(
                isActive = true,
                phase = AppUpdatePhase.Verifying,
                message = "Verifying update...",
                receivedBytes = downloadedBytes,
                totalBytes = expectedBytes.coerceAtLeast(downloadedBytes)
            )
        )

        if (digest != null && expectedSha256 != null) {
            val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (actualSha256 != expectedSha256) {
                tmpFile.delete()
                error("download checksum did not match")
            }
        }

        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.delete()
            error("download could not be prepared for installation")
        }

        AppUpdateProgressBus.update(
            AppUpdateProgressState(
                isActive = true,
                phase = AppUpdatePhase.ReadyToInstall,
                message = "Launching Windows installer...",
                receivedBytes = downloadedBytes,
                totalBytes = expectedBytes.coerceAtLeast(downloadedBytes)
            )
        )

        // Launch the installer. EXE/MSI are self-contained installers; use the
        // OS default handler so the Windows SmartScreen / UAC flow runs.
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(targetFile)
            } else {
                ProcessBuilder(targetFile.absolutePath).start()
            }
        }.onFailure {
            // Fallback: reveal the downloaded file so the user can run it.
            runCatching {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(updateDir)
                }
            }
            error("Installer download complete, but Windows could not auto-launch it: $it")
        }

        AppUpdateProgressBus.update(
            AppUpdateProgressState(
                isActive = true,
                phase = AppUpdatePhase.Installing,
                message = "The Windows installer has been launched. Complete the install to finish the update.",
                receivedBytes = downloadedBytes,
                totalBytes = expectedBytes.coerceAtLeast(downloadedBytes),
                canDismiss = true
            )
        )
    }

    /**
     * Fetch the update manifest from Render for the desktop URL / hash / size.
     * Returns null on failure — callers skip verification when unavailable.
     */
    private fun fetchManifest(defaultUrl: String): DesktopManifestInfo? = runCatching {
        val connection = (URL(AppReleaseConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("accept", "application/json")
        }
        if (connection.responseCode !in 200..299) return@runCatching null
        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(raw).jsonObject
        DesktopManifestInfo(
            url = json["desktopUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: defaultUrl,
            sha256 = json["desktopSha256"]?.jsonPrimitive?.contentOrNull
                ?: json["desktop_sha256"]?.jsonPrimitive?.contentOrNull ?: "",
            bytes = json["desktopBytes"]?.jsonPrimitive?.longOrNull ?: 0L
        )
    }.getOrNull()
}
