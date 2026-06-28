package com.alexleoreeves.novelapp.audio

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

actual class MangaOcrReader actual constructor() {

    private val httpClient = HttpClient()

    // Using Japanese OCR client as manga typically flows in Japanese character panels.
    // Falls back to default Latin OCR model if needed.
    private val ocrClient = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    actual suspend fun recognizeTextFromUrl(imageUrl: String): List<OcrTextPanel> = withContext(Dispatchers.IO) {
        try {
            // Download image bytes
            val response = httpClient.get(imageUrl).readBytes()
            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size) ?: return@withContext emptyList()

            // Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val textResult = ocrClient.process(inputImage).await()

            // Map OCR blocks to speech bubble panels with local coordinates
            textResult.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val rect = Rect(
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    right = box.right.toFloat(),
                    bottom = box.bottom.toFloat()
                )
                OcrTextPanel(
                    text = block.text,
                    bounds = rect,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }
        } catch (e: Exception) {
            println("[OCR] Text recognition failed: ${e.message}")
            emptyList()
        }
    }
}
