package com.alexleoreeves.novelapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alexleoreeves.novelapp.platform.AndroidExternalLinkOpener
import com.alexleoreeves.novelapp.platform.AndroidUserSessionStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App(
                userSessionStore = AndroidUserSessionStore(this),
                linkOpener = AndroidExternalLinkOpener(this)
            )
        }
    }
}
