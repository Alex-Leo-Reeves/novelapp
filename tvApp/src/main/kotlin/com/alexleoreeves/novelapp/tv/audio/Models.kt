package com.alexleoreeves.novelapp.tv.audio

data class ParagraphTiming(
    val paragraphIndex: Int,
    val text: String,
    val startTimeMs: Long,
    val durationMs: Long
) {
    val words: List<String> = text.split(Regex("\\s+")).filter { it.isNotBlank() }
}

enum class VoiceMode {
    Dynamic,
    NarratorOnly
}

data class NarrationSettings(
    val narratorVolume: Float = 1f,
    val ambienceVolume: Float = 0.18f,
    val ambienceEnabled: Boolean = false,
    val voiceMode: VoiceMode = VoiceMode.NarratorOnly,
    val narratorVoiceId: Int = 0,
    val characterVoiceId: Int = 17,
    val backgroundPlaybackEnabled: Boolean = false,
    val backgroundTitle: String = "NovelApp narration",
    val backgroundSubtitle: String = "Reading in background"
)
