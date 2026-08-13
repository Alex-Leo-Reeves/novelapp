package com.alexleoreeves.novelapp.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexleoreeves.novelapp.tv.audio.TvTtsEngine
import com.alexleoreeves.novelapp.tv.mediacache.TvMediaCacheController
import com.alexleoreeves.novelapp.tv.platform.UserSessionStore
import com.alexleoreeves.novelapp.tv.ui.TvApp
import com.alexleoreeves.novelapp.tv.ui.theme.NovaReadTVTheme

class TvMainActivity : ComponentActivity() {

    private var mediaCache: TvMediaCacheController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive for TV
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Boot the offline media cache: DownloadEngine + USB monitor + indexer.
        val cache = TvMediaCacheController(applicationContext).also { mediaCache = it }
        val sessionStore = UserSessionStore(applicationContext)
        val ttsEngine = TvTtsEngine(applicationContext)
        setContent {
            NovaReadTVTheme {
                TvApp(
                    sessionStore = sessionStore,
                    ttsEngine = ttsEngine,
                    mediaCache = cache
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mediaCache?.start()
    }

    override fun onStop() {
        // Stop loopback playback (decrypted stream + HTTP server) so a direct
        // app exit from the downloaded-bundle player never leaks threads or
        // open RandomAccessFiles.
        mediaCache?.stopPlayback()
        mediaCache?.stop()
        super.onStop()
    }
}
