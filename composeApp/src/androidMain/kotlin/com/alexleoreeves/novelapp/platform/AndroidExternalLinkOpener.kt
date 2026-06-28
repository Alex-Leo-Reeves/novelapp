package com.alexleoreeves.novelapp.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL

class AndroidExternalLinkOpener(context: Context) : ExternalLinkOpener {
    private val appContext = context.applicationContext

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

    private fun installApkUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(appContext, "Allow NovelApp to install updates, then tap update again.", Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(settingsIntent)
            return
        }

        Toast.makeText(appContext, "Downloading update...", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val updateDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "novelapp-update.apk")
                URL(url).openStream().use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val apkUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile
                )
                android.os.Handler(appContext.mainLooper).post {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    appContext.startActivity(installIntent)
                }
            }.onFailure { error ->
                android.os.Handler(appContext.mainLooper).post {
                    Toast.makeText(
                        appContext,
                        "Update download failed: ${error.message ?: "try again"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}
