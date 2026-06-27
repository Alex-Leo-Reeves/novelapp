package com.alexleoreeves.novelapp.audio

import androidx.compose.ui.geometry.Rect

actual class MangaOcrReader actual constructor() {
    actual suspend fun recognizeTextFromUrl(imageUrl: String): List<OcrTextPanel> {
        // Stub implementation for iOS compiling
        return listOf(
            OcrTextPanel("Welcome to the Manga panel!", Rect(100f, 100f, 500f, 200f)),
            OcrTextPanel("This text is read via AI speech Recognition.", Rect(100f, 300f, 500f, 400f))
        )
    }
}
