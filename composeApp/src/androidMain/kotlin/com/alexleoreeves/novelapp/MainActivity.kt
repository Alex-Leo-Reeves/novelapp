package com.alexleoreeves.novelapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.alexleoreeves.novelapp.nodebridge.ResidentialScraperStatus
import com.alexleoreeves.novelapp.nodebridge.WebViewBridgeRuntime
import com.alexleoreeves.novelapp.platform.AndroidExternalLinkOpener
import com.alexleoreeves.novelapp.platform.AndroidUserSessionStore
import com.alexleoreeves.novelapp.sensor.AppContextHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize application context for sensor monitoring and downloads
        AppContextHolder.applicationContext = applicationContext

        // Boot the stable Headless WebView runtime so the 13 Anivexa anime
        // providers scrape from this device's residential IP instead of datacenter egress.
        WebViewBridgeRuntime.start(applicationContext)

        enableEdgeToEdge()
        val appContext = applicationContext
        setContent {
            val nodeBridgeMessage by ResidentialScraperStatus.message.collectAsState()
            App(
                userSessionStore = AndroidUserSessionStore(appContext),
                linkOpener = AndroidExternalLinkOpener(appContext),
                nodeBridgeMessage = nodeBridgeMessage
            )
        }
    }
}
