package com.alexleoreeves.novelapp.tv.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists the last-watched position for TV content so playback survives
 * app kills, TV power loss and re-launches.
 *
 * Keyed by `${mediaId}::${episodeTitle}` — the same key the detail screen
 * uses to show "Resume" and the player uses to auto-seek.
 */
@Serializable
data class TvWatchProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val isResumable: Boolean
        get() = positionMs > 30_000L && fraction < 0.95f
}

class TvWatchProgressStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("novelapp_tv_watch_progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(key: String): TvWatchProgress? {
        if (key.isBlank()) return null
        val raw = prefs.getString(key, null) ?: return null
        return try { json.decodeFromString<TvWatchProgress>(raw) } catch (_: Exception) { null }
    }

    /** Returns the saved position if it is worth resuming from, else null. */
    fun loadResumeKey(key: String): Long? = load(key)?.takeIf { it.isResumable }?.positionMs

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank() || positionMs <= 0) return
        prefs.edit()
            .putString(
                key,
                json.encodeToString(
                    TvWatchProgress(
                        positionMs = positionMs,
                        durationMs = durationMs.coerceAtLeast(positionMs),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
            .apply()
    }

    fun clear(key: String) {
        if (key.isBlank()) return
        prefs.edit().remove(key).apply()
    }
}
