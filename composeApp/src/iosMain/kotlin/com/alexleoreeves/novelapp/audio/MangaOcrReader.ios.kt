package com.alexleoreeves.novelapp.audio

/**
 * iOS actual. Real on-device OCR (like Android's MLKit) is intentionally NOT
 * implemented here yet. The reader must never fabricate content, so this
 * returns no panels — MangaViewerScreen then reports "No readable text found
 * on page N", exactly as it does for any page whose text it cannot read.
 */
actual class MangaOcrReader actual constructor() {
    actual suspend fun recognizeTextFromUrl(imageUrl: String): List<OcrTextPanel> {
        return emptyList()
    }
}
