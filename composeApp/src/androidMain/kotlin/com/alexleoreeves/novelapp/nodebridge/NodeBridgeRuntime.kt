package com.alexleoreeves.novelapp.nodebridge

import android.content.Context
import android.util.Log
import com.alexleoreeves.novelapp.data.AnivexaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Boots the embedded nodejs-mobile runtime on the device so the 13 Anivexa
 * anime providers scrape from the device's residential IP (exactly like the
 * repo owner's browser-based site) instead of Render's datacenter egress,
 * which the provider CDNs block (streams=0 for popular anime).
 *
 * Pipeline:
 *   1. Copy nodebridge/ assets (bridge main.js + the vendored Anivexa worker)
 *      out of the APK into filesDir once (stamped with a version).
 *   2. Start the detached native Node thread via [NodeNativeBridge] — it
 *      dlopens libnode.so and runs `node main.js`.
 *   3. Poll for bridge-port.json (written by main.js) until the loopback HTTP
 *      server is ready.
 *   4. Point [AnivexaApi.embeddedBaseUrl] at `http://127.0.0.1:<port>`.
 *
 * Any failure degrades to the existing backend fallback (the app keeps
 * working) AND reports a user-facing reason through [NodeBridgeStatus] so the
 * UI can show why some anime servers may be blocked, instead of failing
 * invisibly.
 */
object NodeBridgeRuntime {

    private const val TAG = "NodeBridge"
    private const val ASSET_ROOT = "nodebridge"
    // v6: removed all import.meta.url usages across main.js and smartcache.js
    private const val STAMP_VERSION = "6"
    private const val STAMP_FILE = "nodebridge_stamp.txt"
    private const val PORT_FILE = "bridge-port.json"

    private val started = AtomicBoolean(false)

    /** Non-blocking. Call once early in app startup (e.g. MainActivity.onCreate). */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // Allow the UI and splash/login to settle cleanly before heavy IO/Node initialization
                delay(3500)

                if (!NodeNativeBridge.isLoaded) {
                    val reason = "The built-in anime engine isn't supported on this device's chip (backend fallback active)."
                    NodeBridgeStatus.reportFailure(reason)
                    return@launch
                }

                val workDir = stageAssets(context)
                if (workDir == null) {
                    NodeBridgeStatus.reportFailure("The built-in anime engine assets are missing from this build.")
                    return@launch
                }
                val entry = File(workDir, "main.js")
                if (!entry.exists()) {
                    Log.w(TAG, "entry missing: ${entry.absolutePath}")
                    NodeBridgeStatus.reportFailure("The anime engine entry file is missing.")
                    return@launch
                }

                val libNode = resolveLibNode(context)
                if (libNode == null || !libNode.exists()) {
                    Log.w(TAG, "libnode.so missing in ${context.applicationInfo.nativeLibraryDir} and could not be extracted from APK")
                    NodeBridgeStatus.reportFailure("The Node.js runtime is missing from this build.")
                    return@launch
                }

                val rc = NodeNativeBridge.startNode(libNode.absolutePath, entry.absolutePath)
                if (rc != "OK") {
                    Log.w(TAG, "startNode returned $rc")
                    NodeBridgeStatus.reportFailure("The anime engine failed to boot (${rc}). Backend fallback active.")
                    return@launch
                }

                val portFile = File(workDir, PORT_FILE)
                var port: Int? = null
                repeat(50) { // up to ~10s
                    delay(200)
                    val raw = runCatching { portFile.readText().trim() }.getOrNull()
                    if (!raw.isNullOrBlank()) {
                        port = runCatching {
                            JSONObject(raw).getInt("port")
                        }.getOrNull()
                        if (port != null && port!! > 0) return@repeat
                    }
                    if (raw != null) Log.i(TAG, "port file present but not ready yet")
                }

                val resolved = port
                if (resolved == null) {
                    Log.w(TAG, "bridge-port.json never appeared; using backend fallback")
                    NodeBridgeStatus.reportFailure("The anime engine timed out while starting. Backend fallback active.")
                    return@launch
                }

                AnivexaApi.setEmbeddedBaseUrl("http://127.0.0.1:$resolved")
                NodeBridgeStatus.reportStarted(resolved)
                Log.i(TAG, "embedded Anivexa worker ready at http://127.0.0.1:$resolved")
            } catch (t: Throwable) {
                Log.w(TAG, "nodebridge failed to start; backend fallback active", t)
                NodeBridgeStatus.reportFailure("The anime engine failed to start. Backend fallback active.")
            }
        }
    }

    /**
     * Copies the nodebridge asset tree into filesDir once. Assets are only
     * copied again when the stamp version changes (new release -> stale copy
     * replaced). Returns the directory containing main.js.
     */
    private fun stageAssets(context: Context): File? {
        val baseDir = File(context.filesDir, ASSET_ROOT)
        val stampFile = File(baseDir, STAMP_FILE)
        if (stampFile.exists() && stampFile.readText().trim() == STAMP_VERSION) {
            if (File(baseDir, "main.js").exists()) return baseDir
        }

        val assets = context.assets.list(ASSET_ROOT) ?: return null
        if (assets.isEmpty()) {
            Log.w(TAG, "no nodebridge assets bundled")
            return null
        }

        // Replace stale copy.
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        fun copyTree(relative: String) {
            val srcPath = if (relative.isEmpty()) ASSET_ROOT else "$ASSET_ROOT/$relative"
            val dest = File(baseDir, relative)
            val children = runCatching { context.assets.list(srcPath) }.getOrNull()
            if (children == null || children.isEmpty()) {
                // File.
                dest.parentFile?.mkdirs()
                context.assets.open(srcPath).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            } else {
                dest.mkdirs()
                for (child in children) {
                    copyTree(if (relative.isEmpty()) child else "$relative/$child")
                }
            }
        }
        copyTree("")

        runCatching { stampFile.writeText(STAMP_VERSION) }
        return baseDir
    }

    /**
     * Resolves the path to libnode.so. Checks applicationInfo.nativeLibraryDir first
     * (standard extracted location). If unextracted (due to extractNativeLibs=false or
     * uncompressed native packaging), extracts libnode.so from the APK directly into filesDir.
     */
    private fun resolveLibNode(context: Context): File? {
        val standard = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
        if (standard.exists() && standard.length() > 0) return standard

        val extracted = File(context.filesDir, "libnode.so")
        if (extracted.exists() && extracted.length() > 10_000_000L) {
            return extracted
        }

        val sourceDir = context.applicationInfo.sourceDir ?: return null
        val apkFile = File(sourceDir)
        if (!apkFile.exists()) return null

        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val abis = android.os.Build.SUPPORTED_ABIS
                var entry: java.util.zip.ZipEntry? = null
                for (abi in abis) {
                    val candidate = zip.getEntry("lib/$abi/libnode.so")
                    if (candidate != null) {
                        entry = candidate
                        break
                    }
                }
                if (entry == null) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.name.endsWith("/libnode.so")) {
                            entry = e
                            break
                        }
                    }
                }

                if (entry != null) {
                    Log.i(TAG, "Extracting libnode.so from APK entry ${entry.name} to ${extracted.absolutePath}")
                    extracted.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(extracted).use { output ->
                            input.copyTo(output)
                        }
                    }
                    extracted.setReadable(true, false)
                    extracted.setExecutable(true, false)
                    if (extracted.exists() && extracted.length() > 0) {
                        return extracted
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract libnode.so from APK", e)
        }

        return null
    }
}

