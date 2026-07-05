package com.alexleoreeves.novelapp.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.AudioTrack
import com.alexleoreeves.novelapp.sensor.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Android implementation: streams generated Kokoro WAV bytes through AudioTrack.
 * This avoids temp-file I/O between narration chunks.
 */
actual suspend fun platformPlayAudio(audioBytes: ByteArray) {
    try {
        AndroidGeneratedAudioPlayer.playWavBytes(audioBytes)
    } catch (e: Exception) {
        println("[Audio] Android playback error: ${e.message}")
    }
}

actual suspend fun playKokoroAudioFile(filePath: String) {
    AndroidGeneratedAudioPlayer.playFile(File(filePath), deleteOnFinish = false)
}

internal fun stopPlatformNarrationAudio() = AndroidGeneratedAudioPlayer.stop()
internal fun pausePlatformNarrationAudio() = AndroidGeneratedAudioPlayer.pause()
internal fun resumePlatformNarrationAudio() = AndroidGeneratedAudioPlayer.resume()

actual fun clearTemporaryNarrationAudioCache() {
    AppContextHolder.applicationContext?.cacheDir
        ?.resolve("narration-audio-temp")
        ?.deleteRecursively()
}

actual fun existingNarrationAudioCachePath(cacheKey: String, persistent: Boolean): String? {
    val file = narrationAudioFile(cacheKey, persistent)
    return file.takeIf { it.exists() && it.length() > 44L }?.absolutePath
}

actual fun writeNarrationAudioCache(cacheKey: String, persistent: Boolean, audioBytes: ByteArray): String? {
    if (audioBytes.size <= 44) return null
    return runCatching {
        val file = narrationAudioFile(cacheKey, persistent)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(audioBytes)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        file.absolutePath
    }.getOrNull()
}

private object AndroidGeneratedAudioPlayer {
    /** How many ms before the *end* of a segment we hand control back so the next segment
     *  can start writing into the hardware buffer without a gap. */
    private const val STREAM_HANDOFF_MS = 60L
    /** Polling interval for the playback-head monitor loop. */
    private const val POLL_INTERVAL_MS = 8L
    /** Maximum bytes to write per non-blocking attempt before yielding. */
    private const val WRITE_CHUNK_BYTES = 8192

    private var activePlayer: MediaPlayer? = null
    private var activeTrack: AudioTrack? = null
    private var trackSampleRate: Int = 0
    private var trackChannelCount: Int = 0
    /** Monotonically increasing frame counter — wraps the AudioTrack 32-bit head. */
    private var writtenFrames: Long = 0L

    /**
     * Write [audioBytes] (a PCM-16 WAV) into the shared [AudioTrack] using
     * WRITE_NON_BLOCKING chunks, yielding to the coroutine scheduler between
     * each chunk so the thread never stalls.  After all bytes are queued, the
     * coroutine waits until the hardware head reaches the handoff point.
     */
    suspend fun playWavBytes(audioBytes: ByteArray) = withContext(Dispatchers.IO) {
        val wav = audioBytes.decodePcm16Wav() ?: return@withContext
        val track = ensureTrack(wav.sampleRate, wav.channelCount)

        // ── Non-blocking write loop ────────────────────────────────────────
        var offset = 0
        while (offset < wav.pcmBytes.size) {
            val remaining = wav.pcmBytes.size - offset
            val toWrite = remaining.coerceAtMost(WRITE_CHUNK_BYTES)
            val written = track.write(
                wav.pcmBytes,
                offset,
                toWrite,
                AudioTrack.WRITE_NON_BLOCKING
            )
            when {
                written > 0 -> offset += written
                written == 0 -> delay(POLL_INTERVAL_MS) // buffer full — yield
                else -> error("AudioTrack write failed: $written")
            }
        }

        // ── Record total frames written and compute handoff target ─────────
        val targetFrame = synchronized(this@AndroidGeneratedAudioPlayer) {
            writtenFrames += wav.frameCount
            writtenFrames
        }
        val handoffFrames = (wav.sampleRate.toLong() * STREAM_HANDOFF_MS / 1000L).coerceAtLeast(1L)
        waitUntilPlayed(track, (targetFrame - handoffFrames).coerceAtLeast(0L))
    }

    suspend fun playFile(file: File, deleteOnFinish: Boolean) {
        if (!file.exists() || file.length() == 0L) return
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            stop()
            activePlayer = player
            try {
                player.setDataSource(file.absolutePath)
                player.prepare()
                player.setOnCompletionListener {
                    if (activePlayer === it) activePlayer = null
                    it.release()
                    if (deleteOnFinish) file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { current, _, _ ->
                    if (activePlayer === current) activePlayer = null
                    current.release()
                    if (deleteOnFinish) file.delete()
                    if (cont.isActive) cont.resumeWithException(Exception("MediaPlayer error"))
                    true
                }
                player.start()
                cont.invokeOnCancellation {
                    if (activePlayer === player) activePlayer = null
                    runCatching { player.stop() }
                    player.release()
                    if (deleteOnFinish) file.delete()
                }
            } catch (e: Exception) {
                if (activePlayer === player) activePlayer = null
                player.release()
                if (deleteOnFinish) file.delete()
                cont.resumeWithException(e)
            }
        }
    }

    fun stop() {
        val player = activePlayer
        activePlayer = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
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
        runCatching { activePlayer?.takeIf { it.isPlaying }?.pause() }
        runCatching { activeTrack?.pause() }
    }

    fun resume() {
        runCatching { activePlayer?.takeIf { !it.isPlaying }?.start() }
        runCatching { activeTrack?.play() }
    }

    private fun ensureTrack(sampleRate: Int, channelCount: Int): AudioTrack {
        synchronized(this) {
            activePlayer?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
                activePlayer = null
            }
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
            ).coerceAtLeast(sampleRate * channelCount * 2 / 2) // doubled from /4
            val trackBuffer = max(minBuffer, sampleRate * channelCount * 2 * 2) // 2 seconds buffer
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
        // AudioTrack.playbackHeadPosition is a 32-bit unsigned counter: mask it and
        // compare against our monotonic writtenFrames to handle wrap-around.
        while (activeTrack === track) {
            val headRaw = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            // Compute how far behind the head is relative to our monotonic target.
            // If writtenFrames <= 2^32 the simple comparison is correct; beyond that
            // we use the low-32-bit portion of targetFrame.
            val headMono = if (writtenFrames <= 0xFFFFFFFFL) {
                headRaw
            } else {
                // Recover the high bits from writtenFrames, then correct for potential wrap.
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

private fun narrationAudioFile(cacheKey: String, persistent: Boolean): File {
    val context = AppContextHolder.applicationContext
        ?: error("Android app context is unavailable for narration cache.")
    val root = if (persistent) {
        File(context.filesDir, "narration-audio")
    } else {
        File(context.cacheDir, "narration-audio-temp")
    }
    return File(root, "${cacheKey.sha256()}.wav")
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(encodeToByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
