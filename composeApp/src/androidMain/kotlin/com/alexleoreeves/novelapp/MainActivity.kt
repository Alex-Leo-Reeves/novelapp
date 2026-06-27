package com.alexleoreeves.novelapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alexleoreeves.novelapp.sensor.AppContextHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize application context for sensor monitoring and downloads
        AppContextHolder.applicationContext = applicationContext

        enableEdgeToEdge()
        setContent {
            App()
        }
    }
}
