package com.alexleoreeves.novelapp.audio

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

actual class MangaOcrReader actual constructor() {

    private val httpClient = HttpClient()

    private val recognizers = listOf(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    )

    actual suspend fun recognizeTextFromUrl(imageUrl: String): List<OcrTextPanel> = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.get(imageUrl) {
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122 Mobile Safari/537.36"
                )
                header("Referer", imageUrl.substringBeforeLast("/", missingDelimiterValue = imageUrl))
            }.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size) ?: return@withContext emptyList()

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val panels = recognizers.flatMap { recognizer ->
                runCatching {
                    recognizer.process(inputImage).await().textBlocks.mapNotNull { block ->
                        val box = block.boundingBox ?: return@mapNotNull null
                        val text = block.text
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                        if (text.length < 2 || box.width() < 8 || box.height() < 8) return@mapNotNull null
                        OcrTextPanel(
                            text = text,
                            bounds = Rect(
                                left = box.left.toFloat(),
                                top = box.top.toFloat(),
                                right = box.right.toFloat(),
                                bottom = box.bottom.toFloat()
                            ),
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height
                        )
                    }
                }.getOrElse { emptyList() }
            }

            panels
                .distinctBy { panel ->
                    "${panel.text.lowercase()}-${(panel.bounds.left / 12).toInt()}-${(panel.bounds.top / 12).toInt()}"
                }
                .sortedWith(compareBy<OcrTextPanel> { it.bounds.top }.thenBy { it.bounds.left })
        } catch (e: Exception) {
            println("[OCR] Text recognition failed: ${e.message}")
            emptyList()
        }
    }
}
