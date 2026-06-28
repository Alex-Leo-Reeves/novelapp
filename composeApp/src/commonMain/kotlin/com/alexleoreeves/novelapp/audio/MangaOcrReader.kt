package com.alexleoreeves.novelapp.audio

import androidx.compose.ui.geometry.Rect

data class OcrTextPanel(
    val text: String,
    val bounds: Rect,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

/**
 * Platform-specific expect declaration for AI Manga panel OCR text recognition.
 */
expect class MangaOcrReader() {
    /**
     * Download the page image from [imageUrl], run OCR scanning locally,
     * and return list of speech bubbles with their bounding box coordinates.
     */
    suspend fun recognizeTextFromUrl(imageUrl: String): List<OcrTextPanel>
}
