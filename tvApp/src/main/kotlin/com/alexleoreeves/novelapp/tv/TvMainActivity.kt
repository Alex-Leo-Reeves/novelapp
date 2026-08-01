package com.alexleoreeves.novelapp.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexleoreeves.novelapp.tv.audio.TvTtsEngine
import com.alexleoreeves.novelapp.tv.platform.UserSessionStore
import com.alexleoreeves.novelapp.tv.ui.TvApp
import com.alexleoreeves.novelapp.tv.ui.theme.NovaReadTVTheme

class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive for TV
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val sessionStore = UserSessionStore(applicationContext)
        val ttsEngine = TvTtsEngine(applicationContext)
        setContent {
            NovaReadTVTheme {
                TvApp(sessionStore = sessionStore, ttsEngine = ttsEngine)
            }
        }
    }
}
