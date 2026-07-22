package com.alexleoreeves.novelapp.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class SherpaChapterNarrator(private val context: Context, private val modelManager: SherpaModelManager) {

    private var tts: OfflineTts? = null

    fun initializeTts() {
        if (tts != null) return
        if (!modelManager.isModelReady()) return

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelManager.getModelPath(),
            lexicon = "",
            tokens = modelManager.getTokensPath(),
            dataDir = modelManager.getDataDir(),
            dictDir = "",
            noiseScale = 0.33f,     // Lower = cleaner, less garbled phonemes
            noiseScaleW = 0.45f,    // Lower = more stable word pronunciation
            lengthScale = 1.15f     // Slightly slower = clearer articulation
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 2
        )
        val config = OfflineTtsConfig(model = modelConfig)
        tts = OfflineTts(assetManager = null, config = config)
    }

    suspend fun downloadChapterAudio(
        paragraphs: List<String>, 
        voiceId: Int, 
        chapterName: String, 
        onComplete: (Pair<File, List<ParagraphTiming>>) -> Unit,
        volumeGain: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        initializeTts()
        val engine = tts ?: return@withContext

        val downloadFolder = File(context.filesDir, "downloads")
        if (!downloadFolder.exists()) downloadFolder.mkdirs()
        val targetOutputFile = File(downloadFolder, "$chapterName.wav")

        val allAudioSamples = mutableListOf<FloatArray>()
        val timings = mutableListOf<ParagraphTiming>()
        var sampleRate = 22050 
        var currentSampleCount = 0L

        for ((index, paragraph) in paragraphs.withIndex()) {
            if (paragraph.isBlank()) continue
            val audioResult = engine.generate(paragraph, sid = voiceId, speed = 1.0f) ?: continue
            allAudioSamples.add(audioResult.samples)
            sampleRate = audioResult.sampleRate
            
            val durationMs = max(1L, (audioResult.samples.size * 1000L) / sampleRate)
            val startTimeMs = (currentSampleCount * 1000L) / sampleRate
            
            timings.add(
                ParagraphTiming(
                    paragraphIndex = index,
                    text = paragraph,
                    startTimeMs = startTimeMs,
                    durationMs = durationMs
                )
            )
            currentSampleCount += audioResult.samples.size
        }

        if (allAudioSamples.isEmpty()) return@withContext

        val totalLength = allAudioSamples.sumOf { it.size }
        val finalBuffer = FloatArray(totalLength)
        var currentPosition = 0
        for (segment in allAudioSamples) {
            System.arraycopy(segment, 0, finalBuffer, currentPosition, segment.size)
            currentPosition += segment.size
        }

        val appliedGain = maxOf(0.5f, volumeGain)
        val wavBytes = floatArrayToWavBytes(finalBuffer, sampleRate, appliedGain)
        FileOutputStream(targetOutputFile).use { it.write(wavBytes) }
        withContext(Dispatchers.Main) {
            onComplete(Pair(targetOutputFile, timings))
        }
    }

    fun generateAudioWavBytes(paragraph: String, settings: NarrationSettings, isDialogueOnly: Boolean): Pair<ByteArray, Long>? {
        initializeTts()
        val engine = tts ?: return null
        if (paragraph.isBlank()) return null
        
        val segments = if (isDialogueOnly) {
            listOf(Pair(paragraph, true))
        } else {
            parseSegments(paragraph)
        }

        val allAudioSamples = mutableListOf<FloatArray>()
        var sampleRate = 22050
        
        for ((text, isDialogue) in segments) {
            if (text.isBlank()) continue
            
            val voiceId = if (isDialogue && settings.voiceMode == VoiceMode.Dynamic) {
                settings.characterVoiceId
            } else {
                settings.narratorVoiceId
            }
            
            val audioResult = engine.generate(text, sid = voiceId, speed = 1.0f) ?: continue
            allAudioSamples.add(audioResult.samples)
            sampleRate = audioResult.sampleRate
        }
        
        if (allAudioSamples.isEmpty()) return null
        
        val totalLength = allAudioSamples.sumOf { it.size }
        val finalBuffer = FloatArray(totalLength)
        var currentPosition = 0
        for (segment in allAudioSamples) {
            System.arraycopy(segment, 0, finalBuffer, currentPosition, segment.size)
            currentPosition += segment.size
        }

        val durationMs = max(1L, (finalBuffer.size * 1000L) / sampleRate)
        // Apply user-set volume gain (0.0-2.0 range, minimum 0.5 boost so it's never silent)
        val volumeGain = maxOf(0.5f, settings.narratorVolume)
        val wavBytes = floatArrayToWavBytes(finalBuffer, sampleRate, volumeGain)
        return Pair(wavBytes, durationMs)
    }

    private fun parseSegments(paragraph: String): List<Pair<String, Boolean>> {
        val segments = mutableListOf<Pair<String, Boolean>>()
        val quoteRegex = Regex("[\"“](.*?)[\"”]")
        var lastIndex = 0
        
        for (match in quoteRegex.findAll(paragraph)) {
            val narration = paragraph.substring(lastIndex, match.range.first)
            if (narration.isNotBlank()) {
                segments.add(Pair(narration, false))
            }
            // The match includes the quotes, we synthesize them too (they are ignored by TTS usually, but good for context)
            segments.add(Pair(match.value, true))
            lastIndex = match.range.last + 1
        }
        
        if (lastIndex < paragraph.length) {
            val remaining = paragraph.substring(lastIndex)
            if (remaining.isNotBlank()) {
                segments.add(Pair(remaining, false))
            }
        }
        
        if (segments.isEmpty()) {
            segments.add(Pair(paragraph, false))
        }
        
        return segments
    }

    fun generateAudioWavBytes(text: String, voiceId: Int): Pair<ByteArray, Long>? {
        initializeTts()
        val engine = tts ?: return null
        if (text.isBlank()) return null
        
        val audioResult = engine.generate(text, sid = voiceId, speed = 1.0f) ?: return null
        val durationMs = max(1L, (audioResult.samples.size * 1000L) / audioResult.sampleRate)
        val wavBytes = floatArrayToWavBytes(audioResult.samples, audioResult.sampleRate, 1.5f) // Boost test voice by default
        return Pair(wavBytes, durationMs)
    }

    private fun floatArrayToWavBytes(samples: FloatArray, sampleRate: Int, gain: Float = 1.0f): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val pcmData = ShortArray(samples.size)
        for (i in samples.indices) {
            pcmData[i] = (samples[i] * 32767 * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        
        val header = ByteArray(44)
        val totalDataLen = pcmData.size * 2 + 36
        val byteRate = sampleRate * 2

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 
        header[20] = 1; header[21] = 0 
        header[22] = 1; header[23] = 0 
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0 
        header[34] = 16; header[35] = 0 
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        val audioDataLen = pcmData.size * 2
        header[40] = (audioDataLen and 0xff).toByte(); header[41] = ((audioDataLen shr 8) and 0xff).toByte()
        header[42] = ((audioDataLen shr 16) and 0xff).toByte(); header[43] = ((audioDataLen shr 24) and 0xff).toByte()

        out.write(header)
        val buffer = ByteArray(2)
        for (sample in pcmData) {
            buffer[0] = (sample.toInt() and 0xff).toByte()
            buffer[1] = ((sample.toInt() shr 8) and 0xff).toByte()
            out.write(buffer)
        }
        return out.toByteArray()
    }
}
