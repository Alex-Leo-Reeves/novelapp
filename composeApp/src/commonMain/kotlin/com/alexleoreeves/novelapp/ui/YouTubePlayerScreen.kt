package com.alexleoreeves.novelapp.ui

import androidx.compose.runtime.Composable
import com.alexleoreeves.novelapp.data.AppTheme

@Composable
expect fun YouTubePlayerScreen(
    videoId: String,
    title: String,
    currentTheme: AppTheme,
    onBack: () -> Unit
)
