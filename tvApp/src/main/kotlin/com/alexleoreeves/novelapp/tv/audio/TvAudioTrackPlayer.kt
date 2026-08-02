package com.alexleoreeves.novelapp.tv.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Ported from the Android app's PlatformAudio — streams PCM-16 WAV chunks
 * through AudioTrack with a gapless handoff so TV narration doesn't stutter.
 */
object TvAudioTrackPlayer {

    private const val STREAM_HANDOFF_MS = 60L
    private const val POLL_INTERVAL_MS = 8L
    private const val WRITE_CHUNK_BYTES = 8192

    private var activeTrack: AudioTrack? = null
    private var trackSampleRate: Int = 0
    private var trackChannelCount: Int = 0
    private var writtenFrames: Long = 0L

    suspend fun playWavBytes(audioBytes: ByteArray) = withContext(Dispatchers.IO) {
        val wav = audioBytes.decodePcm16Wav() ?: return@withContext
        val track = ensureTrack(wav.sampleRate, wav.channelCount)

        var offset = 0
        while (offset < wav.pcmBytes.size) {
            val remaining = wav.pcmBytes.size - offset
            val toWrite = remaining.coerceAtMost(WRITE_CHUNK_BYTES)
            val written = track.write(wav.pcmBytes, offset, toWrite, AudioTrack.WRITE_NON_BLOCKING)
            when {
                written > 0 -> offset += written
                written == 0 -> delay(POLL_INTERVAL_MS)
                else -> return@withContext
            }
        }

        val targetFrame = synchronized(this) {
            writtenFrames += wav.frameCount
            writtenFrames
        }
        val handoffFrames = (wav.sampleRate.toLong() * STREAM_HANDOFF_MS / 1000L).coerceAtLeast(1L)
        waitUntilPlayed(track, (targetFrame - handoffFrames).coerceAtLeast(0L))
    }

    fun stop() {
        val track = activeTrack
        activeTrack = null
        trackSampleRate = 0
        trackChannelCount = 0
        writtenFrames = 0L
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
    }

    fun pause() {
        runCatching { activeTrack?.pause() }
    }

    fun resume() {
        runCatching { activeTrack?.play() }
    }

    private fun ensureTrack(sampleRate: Int, channelCount: Int): AudioTrack {
        synchronized(this) {
            activeTrack?.takeIf { track ->
                trackSampleRate == sampleRate &&
                    trackChannelCount == channelCount &&
                    track.state == AudioTrack.STATE_INITIALIZED
            }?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                return track
            }

            activeTrack?.let { old ->
                runCatching { old.pause() }
                runCatching { old.flush() }
                runCatching { old.release() }
            }

            val channelMask = if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate * channelCount * 2 / 2)
            val trackBuffer = max(minBuffer, sampleRate * channelCount * 2 * 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBuffer)
                .build()
            track.play()
            activeTrack = track
            trackSampleRate = sampleRate
            trackChannelCount = channelCount
            writtenFrames = 0L
            return track
        }
    }

    private suspend fun waitUntilPlayed(track: AudioTrack, targetFrame: Long) {
        while (activeTrack === track) {
            val headRaw = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val headMono = if (writtenFrames <= 0xFFFFFFFFL) {
                headRaw
            } else {
                val highBits = writtenFrames and -4294967296L
                var estimate = highBits or headRaw
                if (estimate < writtenFrames - 2147483648L) estimate -= 4294967296L
                estimate
            }
            if (headMono >= targetFrame) return
            delay(POLL_INTERVAL_MS)
        }
    }
}

private data class Pcm16Wav(
    val sampleRate: Int,
    val channelCount: Int,
    val pcmBytes: ByteArray
) {
    val frameCount: Long
        get() = pcmBytes.size.toLong() / (channelCount.coerceAtLeast(1) * 2L)
}

private fun ByteArray.decodePcm16Wav(): Pcm16Wav? {
    if (size <= 44) return null
    if (this[0].toInt().toChar() != 'R' || this[8].toInt().toChar() != 'W') return null
    val channelCount = littleEndianShort(22).coerceAtLeast(1)
    val sampleRate = littleEndianInt(24).takeIf { it > 0 } ?: return null
    val bitsPerSample = littleEndianShort(34)
    if (bitsPerSample != 16) return null
    var cursor = 12
    while (cursor + 8 <= size) {
        val chunkId = String(this, cursor, 4)
        val chunkSize = littleEndianInt(cursor + 4).coerceAtLeast(0)
        val dataStart = cursor + 8
        val dataEnd = (dataStart + chunkSize).coerceAtMost(size)
        if (chunkId == "data" && dataEnd > dataStart) {
            return Pcm16Wav(
                sampleRate = sampleRate,
                channelCount = channelCount,
                pcmBytes = copyOfRange(dataStart, dataEnd)
            )
        }
        cursor = dataStart + chunkSize + (chunkSize % 2)
    }
    return Pcm16Wav(
        sampleRate = sampleRate,
        channelCount = channelCount,
        pcmBytes = copyOfRange(44, size)
    )
}

private fun ByteArray.littleEndianShort(offset: Int): Int =
    (getOrNull(offset)?.toInt()?.and(0xff) ?: 0) or
        ((getOrNull(offset + 1)?.toInt()?.and(0xff) ?: 0) shl 8)

private fun ByteArray.littleEndianInt(offset: Int): Int =
    (getOrNull(offset)?.toInt()?.and(0xff) ?: 0) or
        ((getOrNull(offset + 1)?.toInt()?.and(0xff) ?: 0) shl 8) or
        ((getOrNull(offset + 2)?.toInt()?.and(0xff) ?: 0) shl 16) or
        ((getOrNull(offset + 3)?.toInt()?.and(0xff) ?: 0) shl 24)
