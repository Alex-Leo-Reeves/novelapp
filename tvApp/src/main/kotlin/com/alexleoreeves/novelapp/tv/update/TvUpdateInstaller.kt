package com.alexleoreeves.novelapp.tv.update

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
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Android TV in-app updater.
 *
 * Downloads the TV APK from the PERMANENT release channel
 * (AppReleaseConfig.ANDROID_TV_DOWNLOAD_URL — never change it for a routine
 * release), verifies it against the manifest's tvApkSha256 / tvApkBytes
 * (skipped when those are empty/0 until you provide them after building),
 * then hands it to the Android package installer — same flow as the phone app.
 *
 * ⚠️ Android TV and Android phone are SEPARATE APKs signed with the SAME key.
 * The installer verifies package name, version, and signature before allowing
 * an update.
 */
object TvUpdateInstaller {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Entry point. `url` is the TV APK download URL (permanent channel),
     * `sha256`/`bytes` from the server manifest (may be empty/0 → skipped).
     */
    fun start(context: Context, url: String, sha256: String, bytes: Long) {
        val appContext = context.applicationContext

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val canInstall = runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
                if (!canInstall) {
                    AppUpdateProgressBus.update(
                        AppUpdateProgressState(
                            isActive = true,
                            phase = AppUpdatePhase.Error,
                            message = "Allow NovaRead TV to install updates, then try again.",
                            canDismiss = true,
                            isError = true
                        )
                    )
                    toast(appContext, "Allow NovaRead TV to install updates, then try again.", Toast.LENGTH_LONG)
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    val launched = runCatching {
                        appContext.startActivity(settingsIntent)
                        true
                    }.getOrElse {
                        runCatching {
                            appContext.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                            true
                        }.getOrDefault(false)
                    }
                    if (launched) return
                }
            }
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
                val result = runCatching { downloadAndInstall(appContext, url, sha256, bytes) }
                if (result.isSuccess) {
                    result.getOrThrow()
                    return@Thread
                }
                lastException = result.exceptionOrNull()
                if (attempt < 3) {
                    AppUpdateProgressBus.update(
                        AppUpdateProgressState(
                            isActive = true,
                            phase = AppUpdatePhase.Downloading,
                            message = "Retrying (attempt ${attempt + 1}/3)..."
                        )
                    )
                    Thread.sleep(attempt * 5000L)
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
            toast(appContext, "Update download failed: $msg", Toast.LENGTH_LONG)
        }.start()
    }

    private fun downloadAndInstall(context: Context, url: String, sha256: String, bytes: Long) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "novelapp-tv-update.apk")
        val tmpFile = File(updateDir, "novelapp-tv-update.apk.part")
        if (apkFile.exists()) apkFile.delete()
        if (tmpFile.exists()) tmpFile.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NovaReadTV/${AppReleaseConfig.CURRENT_VERSION_NAME}")
        }
        if (connection.responseCode !in 200..299) {
            error("server returned HTTP ${connection.responseCode}")
        }

        val manifestBytes = bytes.takeIf { it > 0L }
        val contentLengthBytes = connection.contentLengthLong.takeIf { it > 0L }
        val expectedBytes = manifestBytes ?: contentLengthBytes ?: -1L

        val expectedSha256 = sha256
            .lowercase()
            .takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

        // When no manifest hash yet, skip checksum; Android verifies signature at install.
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

        installBlocker(context, apkFile)?.let { problem ->
            apkFile.delete()
            error(problem)
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        mainHandler.post {
            runCatching {
                val installIntent = installerIntent(context, apkUri)
                grantInstallerReadPermission(context, installIntent)
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
                context.startActivity(installIntent)
            }.onFailure { err ->
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallbackIntent) }
                AppUpdateProgressBus.update(
                    AppUpdateProgressState(
                        isActive = true,
                        phase = AppUpdatePhase.Error,
                        message = "Could not open installer (${err.message}). Opened download link in browser.",
                        canDismiss = true,
                        isError = true
                    )
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, apkPath: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(apkPath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(context: Context): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            val signers = if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
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

    private fun installBlocker(context: Context, apk: File): String? {
        val candidate = packageInfo(context, apk.absolutePath)
            ?: return "Android could not read the downloaded APK"
        val installed = installedPackageInfo(context)
        if (candidate.packageName != context.packageName) {
            return "downloaded APK package does not match NovaRead TV"
        }
        if (versionCodeOf(candidate) <= versionCodeOf(installed)) {
            return "downloaded APK is not newer than the installed app"
        }
        if (signaturesMatch(candidate, installed) == false) {
            return "downloaded APK is signed with a different key"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun installerIntent(context: Context, apkUri: Uri): Intent =
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(context.contentResolver, "NovaRead TV update", apkUri)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                clipData = ClipData.newUri(context.contentResolver, "NovaRead TV update", apkUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermission(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (handler in handlers) {
            handler.activityInfo?.packageName?.let { packageName ->
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun toast(context: Context, message: String, duration: Int) {
        mainHandler.post {
            Toast.makeText(context, message, duration).show()
        }
    }
}
