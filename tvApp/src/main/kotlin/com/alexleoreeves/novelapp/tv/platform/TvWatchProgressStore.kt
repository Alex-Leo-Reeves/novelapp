package com.alexleoreeves.novelapp.tv.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

/** A watched-title seed used by the home feed's recommendation engine. */
@Serializable
data class TvWatchSeed(
    val mediaId: String,
    val title: String,
    val updatedAt: Long = 0L
)

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

    /**
     * Recent watch seeds for the home feed's recommendation engine:
     * the media id + title parsed out of each progress key ("${id}::${title} - ${chapter}"),
     * newest first. This is what lets the TV home learn from what was watched.
     */
    fun getRecentSeeds(limit: Int = 6): List<TvWatchSeed> {
        return prefs.all.mapNotNull { (key, value) ->
            if ("::" !in key) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val progress = try {
                json.decodeFromString<TvWatchProgress>(raw)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val mediaId = key.substringBefore("::")
            val titlePart = key.substringAfter("::")
            // Skip keys built from a raw URL fallback and empty ones.
            if (mediaId.isBlank() || mediaId.startsWith("http") || titlePart.isBlank()) return@mapNotNull null
            // Keys are "${id}::${title} - ${chapter}"; strip the chapter suffix.
            val title = titlePart.substringBeforeLast(" - ").ifBlank { titlePart }
            TvWatchSeed(mediaId = mediaId, title = title, updatedAt = progress.updatedAt)
        }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.mediaId }
            .take(limit)
    }
}
