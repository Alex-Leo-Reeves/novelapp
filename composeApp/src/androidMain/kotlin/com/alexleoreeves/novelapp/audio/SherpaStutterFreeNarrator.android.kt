package com.alexleoreeves.novelapp.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.max

data class ParagraphTiming(
    val paragraphIndex: Int,
    val text: String,
    val startTimeMs: Long,
    val durationMs: Long
) {
    val words: List<String> = text.split(Regex("\\s+")).filter { it.isNotBlank() }
}

class SherpaStutterFreeNarrator(
    private val context: Context,
    private val chapterNarrator: SherpaChapterNarrator
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentParagraphIndex = MutableStateFlow(-1)
    val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex

    private val _currentWordIndex = MutableStateFlow(-1)
    val currentWordIndex: StateFlow<Int> = _currentWordIndex

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress
    
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private var mediaPlayer: MediaPlayer? = null
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var timelineJob: Job? = null
    
    private var currentTimings = listOf<ParagraphTiming>()
    private var currentChapterAudio: File? = null

    private var streamingJob: Job? = null
    
    data class ParagraphAudioData(
        val index: Int,
        val paragraph: String,
        val wavBytes: ByteArray,
        val durationMs: Long
    )

    suspend fun streamText(paragraphs: List<String>, settings: NarrationSettings, isDialogueOnly: Boolean) {
        stop()
        _isBuffering.value = true
        
        val channel = kotlinx.coroutines.channels.Channel<ParagraphAudioData>(capacity = 5)
        
        // Producer: Synthesize audio chunks in the background
        val producerJob = scope.launch(Dispatchers.IO) {
            for ((index, paragraph) in paragraphs.withIndex()) {
                if (paragraph.isBlank()) continue
                val result = chapterNarrator.generateAudioWavBytes(paragraph, settings, isDialogueOnly)
                if (result != null) {
                    channel.send(ParagraphAudioData(index, paragraph, result.first, result.second))
                }
            }
            channel.close()
        }
        
        // Consumer: Play chunks gaplessly using AudioTrack
        streamingJob = scope.launch {
            var first = true
            for (data in channel) {
                if (first) {
                    _isBuffering.value = false
                    _isPlaying.value = true
                    first = false
                }
                
                _currentParagraphIndex.value = data.index
                
                // Timeline tracker for words
                val wordTrackerJob = launch {
                    val words = data.paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (words.isNotEmpty()) {
                        val stepMs = data.durationMs / words.size
                        for (i in words.indices) {
                            _currentWordIndex.value = i
                            delay(stepMs)
                        }
                    }
                }
                
                // This blocks until 60ms before the segment finishes playing, ensuring smooth handoff!
                platformPlayAudio(data.wavBytes)
                wordTrackerJob.cancel()
            }
            _isPlaying.value = false
        }
        
        // Tie producer to streaming job so stopping cancels both
        streamingJob?.invokeOnCompletion { producerJob.cancel() }
    }

    suspend fun loadAndPlayChapter(
        paragraphs: List<String>, 
        voiceId: Int, 
        chapterName: String
    ) {
        stop()
        
        _isBuffering.value = true
        
        var currentSampleCount = 0L
        val sampleRate = 22050
        val timings = mutableListOf<ParagraphTiming>()
        
        // Wrap the original offline generation to capture timings
        val downloadFolder = File(context.filesDir, "downloads")
        if (!downloadFolder.exists()) downloadFolder.mkdirs()
        val targetOutputFile = File(downloadFolder, "$chapterName.wav")
        
        chapterNarrator.initializeTts()
        
        withContext(Dispatchers.IO) {
            val allAudioSamples = mutableListOf<FloatArray>()
            
            for ((index, paragraph) in paragraphs.withIndex()) {
                if (paragraph.isBlank()) {
                    timings.add(ParagraphTiming(index, paragraph, (currentSampleCount * 1000) / sampleRate, 0))
                    continue
                }
                
                // Get Sherpa to synthesize offline
                // Note: We need a private way to access engine, or just duplicate the call here
                // For simplicity, we just use the ChapterNarrator logic inline here to get timings
            }
        }
    }

    fun playAudioFileWithTimings(audioFile: File, timings: List<ParagraphTiming>) {
        currentChapterAudio = audioFile
        currentTimings = timings
        
        startNarrationLoop()
    }
    
    // We provide a dedicated method to render and play all at once
    suspend fun prepareAndPlay(paragraphs: List<String>, voiceId: Int, chapterName: String) {
        stop()
        _isBuffering.value = true
        
        val timings = mutableListOf<ParagraphTiming>()
        var currentSampleCount = 0L
        
        withContext(Dispatchers.IO) {
            chapterNarrator.initializeTts()
            // Using reflection or a dedicated method in ChapterNarrator to get access to offline generation
            // Since we own ChapterNarrator, let's just make it return a Pair<File, List<ParagraphTiming>>
        }
    }

    private fun startNarrationLoop() {
        val audioFile = currentChapterAudio ?: return
        if (!audioFile.exists()) return

        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
            setOnCompletionListener { 
                _isPlaying.value = false
                timelineJob?.cancel()
            }
        }
        
        _isPlaying.value = true
        _isBuffering.value = false

        timelineJob = scope.launch {
            while (mediaPlayer?.isPlaying == true) {
                val currentPlayTime = mediaPlayer?.currentPosition?.toLong() ?: 0L
                val duration = mediaPlayer?.duration?.toLong() ?: 1L
                
                _playbackProgress.value = currentPlayTime.toFloat() / duration.toFloat()
                
                val currentParaTiming = currentTimings.lastOrNull { currentPlayTime >= it.startTimeMs }
                
                if (currentParaTiming != null) {
                    _currentParagraphIndex.value = currentParaTiming.paragraphIndex
                    
                    // Estimate word highlighting based on paragraph progress
                    val timeInPara = currentPlayTime - currentParaTiming.startTimeMs
                    val paraProgress = timeInPara.toFloat() / max(1L, currentParaTiming.durationMs).toFloat()
                    
                    val words = currentParaTiming.words
                    if (words.isNotEmpty()) {
                        val wordIndex = (paraProgress * words.size).toInt().coerceIn(0, words.lastIndex)
                        _currentWordIndex.value = wordIndex
                    } else {
                        _currentWordIndex.value = -1
                    }
                }
                
                delay(30) // Checks timeline positioning 33 times a second for fluid transitions
            }
        }
    }
    
    fun pause() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        pausePlatformNarrationAudio()
        _isPlaying.value = false
        timelineJob?.cancel()
    }
    
    fun resume() {
        mediaPlayer?.start()
        resumePlatformNarrationAudio()
        _isPlaying.value = true
        if (mediaPlayer != null) startNarrationLoop()
    }
    
    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopPlatformNarrationAudio()
        streamingJob?.cancel()
        _isPlaying.value = false
        _isBuffering.value = false
        _currentParagraphIndex.value = -1
        _currentWordIndex.value = -1
        timelineJob?.cancel()
    }
    
    fun seekToProgress(progress: Float) {
        val player = mediaPlayer ?: return
        val duration = player.duration
        player.seekTo((duration * progress).toInt())
    }
}
