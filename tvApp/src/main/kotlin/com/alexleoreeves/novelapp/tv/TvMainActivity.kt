package com.alexleoreeves.novelapp.tv

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexleoreeves.novelapp.nodebridge.WebViewBridgeRuntime
import com.alexleoreeves.novelapp.tv.audio.TvTtsEngine
import com.alexleoreeves.novelapp.tv.mediacache.TvMediaCacheController
import com.alexleoreeves.novelapp.tv.platform.UserSessionStore
import com.alexleoreeves.novelapp.tv.ui.TvApp
import com.alexleoreeves.novelapp.tv.ui.theme.NovaReadTVTheme
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class TvMainActivity : ComponentActivity() {

    private var mediaCache: TvMediaCacheController? = null
    private val crashError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install a global uncaught exception handler that persists the crash
        // to a file + shows on screen. Android TV often silently kills apps.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val trace = sw.toString()
            Log.e("TvMainActivity", "UNCAUGHT CRASH: $trace")
            runCatching {
                File(filesDir, "crash_log.txt").writeText(
                    "Time: ${System.currentTimeMillis()}\nThread: ${thread.name}\n$trace"
                )
            }
            runOnUiThread {
                crashError.value = trace
            }
        }

        // Check if there's a previous crash log and show it immediately
        try {
            val crashFile = File(filesDir, "crash_log.txt")
            if (crashFile.exists()) {
                val previousCrash = crashFile.readText()
                if (previousCrash.isNotBlank()) {
                    Log.w("TvMainActivity", "Previous crash detected:\n$previousCrash")
                    crashError.value = previousCrash
                }
            }
        } catch (_: Throwable) { /* ignore */ }

        try {
            // Full-screen immersive for TV
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (t: Throwable) {
            Log.e("TvMainActivity", "WindowCompat setup failed", t)
        }

        try {
            // Boot the Headless WebView runtime for residential-IP scraping
            // (13 Anivexa anime providers)
            WebViewBridgeRuntime.start(applicationContext)
        } catch (t: Throwable) {
            Log.e("TvMainActivity", "WebViewBridgeRuntime.start failed", t)
        }

        try {
            // Boot the offline media cache: DownloadEngine + USB monitor + indexer.
            val cache = TvMediaCacheController(applicationContext).also { mediaCache = it }
            val sessionStore = UserSessionStore(applicationContext)
            val ttsEngine = TvTtsEngine(applicationContext)
            setContent {
                NovaReadTVTheme {
                    val error = crashError.value
                    if (error != null) {
                        // Emergency crash display: shows the crash inline so we can
                        // see the cause even without ADB.
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "App Crash — Share this with developer:",
                                    color = Color.Red,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                                Text(error, color = Color.White, style = MaterialTheme.typography.bodySmall)
                                androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
                                androidx.compose.material3.Button(
                                    onClick = {
                                        runCatching { File(filesDir, "crash_log.txt").delete() }
                                        crashError.value = null
                                    }
                                ) {
                                    Text("Dismiss & Continue")
                                }
                            }
                        }
                    } else {
                        TvApp(
                            sessionStore = sessionStore,
                            ttsEngine = ttsEngine,
                            mediaCache = cache
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val trace = sw.toString()
            Log.e("TvMainActivity", "FATAL in onCreate setContent block: $trace")
            crashError.value = trace
            runCatching {
                File(filesDir, "crash_log.txt").writeText(trace)
            }
            // Last resort: show a Toast and set minimal content so the activity doesn't die
            runCatching {
                Toast.makeText(this, "App crashed: ${t.message}", Toast.LENGTH_LONG).show()
            }
            setContent {
                NovaReadTVTheme {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "App Crash — Share this with developer:",
                                color = Color.Red,
                                style = MaterialTheme.typography.titleMedium
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                            Text(trace, color = Color.White, style = MaterialTheme.typography.bodySmall)
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
                            androidx.compose.material3.Button(
                                onClick = {
                                    runCatching { File(filesDir, "crash_log.txt").delete() }
                                    crashError.value = null
                                }
                            ) {
                                Text("Dismiss & Continue")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            mediaCache?.start()
        } catch (t: Throwable) {
            Log.e("TvMainActivity", "mediaCache.start() failed", t)
        }
    }

    override fun onStop() {
        // Stop loopback playback (decrypted stream + HTTP server) so a direct
        // app exit from the downloaded-bundle player never leaks threads or
        // open RandomAccessFiles.
        runCatching { mediaCache?.stopPlayback() }
        runCatching { mediaCache?.stop() }
        super.onStop()
    }
}
