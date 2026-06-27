package com.alexleoreeves.novelapp.audio

actual class MangaOcrReader actual constructor() {
    actual suspend fun recognizeTextFromUrl(imageUrl: String): List<OcrTextPanel> {
        println("[OCR] Desktop OCR is not available for $imageUrl")
        return emptyList()
    }
}
