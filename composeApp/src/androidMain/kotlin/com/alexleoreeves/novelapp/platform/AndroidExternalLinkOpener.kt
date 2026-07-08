package com.alexleoreeves.novelapp.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.alexleoreeves.novelapp.data.AppUpdatePhase
import com.alexleoreeves.novelapp.data.AppUpdateProgressBus
import com.alexleoreeves.novelapp.data.AppUpdateProgressState
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AndroidExternalLinkOpener(context: Context) : ExternalLinkOpener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun open(url: String) {
        if (url.endsWith(".apk", ignoreCase = true) || url.contains("novelapp-android.apk", ignoreCase = true)) {
            installApkUpdate(url)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  installApkUpdate — entry point with retry loop
    // ─────────────────────────────────────────────────────────────────────────
    private fun installApkUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            AppUpdateProgressBus.update(
                AppUpdateProgressState(
                    isActive = true,
                    phase = AppUpdatePhase.Error,
                    message = "Allow NovelApp to install updates, then tap update again.",
                    canDismiss = true,
                    isError = true
                )
            )
            Toast.makeText(appContext, "Allow NovelApp to install updates, then tap update again.", Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            appContext.startActivity(settingsIntent)
            return
        }

        AppUpdateProgressBus.update(
            AppUpdateProgressState(
                isActive = true,
                phase = AppUpdatePhase.Downloading,
                message = "Starting update download..."
            )
        )
        Thread {
            var lastException: Throwable? = null
            for (attempt in 1..3) {
                val result = runCatching { downloadAndInstallApk(url) }
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
                    toast("Download failed, retrying in ${delay / 1000}s...", Toast.LENGTH_SHORT)
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
            toast("Update download failed: $msg", Toast.LENGTH_LONG)
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  downloadAndInstallApk — single download attempt (thrown on failure)
    // ─────────────────────────────────────────────────────────────────────────
    private fun downloadAndInstallApk(url: String) {
        val updateDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "novelapp-update.apk")
        val tmpFile = File(updateDir, "novelapp-update.apk.part")
        if (apkFile.exists()) apkFile.delete()
        if (tmpFile.exists()) tmpFile.delete()
        val manifest = fetchUpdateManifest()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NovelApp/${AppReleaseConfig.CURRENT_VERSION_NAME}")
        }
        if (connection.responseCode !in 200..299) {
            error("server returned HTTP ${connection.responseCode}")
        }
        val expectedBytes = manifest?.apkBytes?.takeIf { it > 0L } ?: connection.contentLengthLong
        val expectedSha256 = manifest?.sha256
            ?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
        val digest = MessageDigest.getInstance("SHA-256")

        var downloadedBytes = 0L
        var nextProgressToast = 25
        connection.inputStream.use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    digest.update(buffer, 0, read)
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
                        if (progress >= nextProgressToast) {
                            toast("Downloading update... $progress%")
                            nextProgressToast += 25
                        }
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

        if (expectedBytes > 0L && downloadedBytes != expectedBytes) {
            tmpFile.delete()
            error("download was incomplete")
        }
        if (tmpFile.length() <= 0L) {
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
        val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (expectedSha256 != null && actualSha256 != expectedSha256) {
            tmpFile.delete()
            error("download checksum did not match")
        }
        if (!tmpFile.renameTo(apkFile)) {
            tmpFile.delete()
            error("download could not be prepared for installation")
        }
        AppUpdateProgressBus.update(
            AppUpdateProgressState(
                isActive = true,
                phase = AppUpdatePhase.ReadyToInstall,
                message = "Preparing Android installer...",
                receivedBytes = downloadedBytes,
                totalBytes = expectedBytes.coerceAtLeast(downloadedBytes)
            )
        )
        installBlocker(apkFile)?.let { problem ->
            apkFile.delete()
            error(problem)
        }

        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        mainHandler.post {
            val installIntent = installerIntent(apkUri)
            grantInstallerReadPermission(installIntent)
            AppUpdateProgressBus.update(
                AppUpdateProgressState(
                    isActive = true,
                    phase = AppUpdatePhase.Installing,
                    message = "Android installer is open. Finish the install to complete the update.",
                    receivedBytes = downloadedBytes,
                    totalBytes = expectedBytes.coerceAtLeast(downloadedBytes),
                    canDismiss = true
                )
            )
            appContext.startActivity(installIntent)
        }
    }

    private fun fetchUpdateManifest(): UpdateManifest? = runCatching {
        val connection = (URL(AppReleaseConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("accept", "application/json")
        }
        if (connection.responseCode !in 200..299) return@runCatching null
        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val json = JSONObject(raw)
        UpdateManifest(
            apkBytes = json.optLong("apkBytes", 0L),
            sha256 = json.optString("apkSha256", json.optString("sha256", "")).lowercase()
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return appContext.packageManager.getPackageArchiveInfo(path, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return appContext.packageManager.getPackageInfo(appContext.packageName, flags)
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signers?.map { it.toByteArray() }.orEmpty()
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }

    private fun signaturesMatch(candidate: PackageInfo, installed: PackageInfo): Boolean? {
        val candidateSignatures = signaturesOf(candidate)
        val installedSignatures = signaturesOf(installed)
        if (candidateSignatures.isEmpty() || installedSignatures.isEmpty()) return null
        return candidateSignatures.any { candidateSignature ->
            installedSignatures.any { installedSignature ->
                candidateSignature.contentEquals(installedSignature)
            }
        }
    }

    private fun installBlocker(apk: File): String? {
        val candidate = packageInfo(apk.absolutePath) ?: return "Android could not read the downloaded APK"
        val installed = installedPackageInfo()
        if (candidate.packageName != appContext.packageName) return "downloaded APK package does not match NovelApp"
        if (versionCodeOf(candidate) <= versionCodeOf(installed)) return "downloaded APK is not newer than the installed app"
        if (signaturesMatch(candidate, installed) == false) return "downloaded APK is signed with a different key"
        if (installerIntent(apkUri(apk)).resolveActivity(appContext.packageManager) == null) {
            return "Android could not find an app installer"
        }
        return null
    }

    private fun apkUri(apk: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)

    private fun installerIntent(apkUri: Uri): Intent =
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(appContext.contentResolver, "NovelApp update", apkUri)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.takeIf { it.resolveActivity(appContext.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                clipData = ClipData.newUri(appContext.contentResolver, "NovelApp update", apkUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermission(intent: Intent) {
        val uri = intent.data ?: return
        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (handler in handlers) {
            handler.activityInfo?.packageName?.let { packageName ->
                appContext.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(appContext, message, duration).show()
        }
    }
}

private data class UpdateManifest(
    val apkBytes: Long,
    val sha256: String
)
