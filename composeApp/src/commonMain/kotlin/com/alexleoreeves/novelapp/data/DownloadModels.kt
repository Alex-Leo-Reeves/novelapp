package com.alexleoreeves.novelapp.data

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
//  Download Models — persisted locally as a JSON index
// ─────────────────────────────────────────────────────────────────────────────

enum class DownloadType { ANIME, MANGA, NOVEL }

/** Standardised content-type keys for downloads — maps to DownloadItem.type */
object ContentType {
    const val ANIME = "ANIME"
    const val MANGA = "MANGA"
    const val NOVEL = "NOVEL"
    const val MOVIE = "MOVIE"
    const val CARTOON = "CARTOON"
    const val K_DRAMA = "K_DRAMA"
    const val COMIC = "COMIC"
    const val CLASSIC = "CLASSIC"
    const val NIGERIAN = "NIGERIAN"

    /** Human-readable label for a given type key */
    fun label(type: String): String = when (type.uppercase()) {
        ANIME -> "Anime"
        MANGA -> "Manga"
        NOVEL -> "Novels"
        MOVIE -> "Movies"
        CARTOON -> "Cartoons"
        K_DRAMA -> "K-Drama"
        COMIC -> "Comics"
        CLASSIC -> "Classic"
        NIGERIAN -> "Nollywood"
        else -> type
    }

    /** Display icon descriptor (used for section accent colour) */
    fun accentName(type: String): String = when (type.uppercase()) {
        ANIME -> "anime"
        MANGA -> "manga"
        NOVEL -> "novel"
        MOVIE -> "movie"
        CARTOON -> "cartoon"
        K_DRAMA -> "k_drama"
        COMIC -> "comic"
        CLASSIC -> "classic"
        NIGERIAN -> "nigerian"
        else -> "default"
    }

    /** All downloadable content types in display order */
    val ALL_TYPES = listOf(ANIME, MANGA, NOVEL, MOVIE, CARTOON, K_DRAMA, COMIC, CLASSIC, NIGERIAN)
}

@Serializable
data class DownloadedItem(
    val id: String,                    // unique key (e.g. "anilist_12345")
    val title: String,
    val coverUrl: String,
    val type: String,                  // "ANIME", "MANGA", "NOVEL", "MOVIE", "CARTOON", "K_DRAMA", "COMIC" etc.
    val sourceName: String,
    val downloadedAt: Long = 0L,
    val totalItems: Int = 0            // episodes or chapters downloaded
) {
    /** Convenience: returns the human-readable section label */
    val typeLabel: String get() = ContentType.label(type)
}

@Serializable
data class DownloadedEpisode(
    val parentId: String,              // links to DownloadedItem.id
    val episodeNumber: Int,
    val episodeTitle: String,
    val localFilePath: String,         // absolute path to .mp4 / .m3u8 cache
    val downloadedAt: Long = 0L,
    val fileSizeBytes: Long = 0L
)

@Serializable
data class DownloadedChapter(
    val parentId: String,              // links to DownloadedItem.id
    val chapterNumber: Int,
    val chapterTitle: String,
    val localFilePath: String,         // directory containing page images OR text file
    val downloadedAt: Long = 0L,
    val pageCount: Int = 0
)

@Serializable
data class ReadHistoryItem(
    val parentId: String,
    val title: String,
    val coverUrl: String,
    val sourceName: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val isManga: Boolean = false,
    val isComic: Boolean = false,
    val positionIndex: Int = 0,
    val updatedAt: Long = 0L
) {
    /** Content-type derived from flags */
    val contentType: String get() = when {
        isComic -> ContentType.COMIC
        isManga -> ContentType.MANGA
        else -> ContentType.NOVEL
    }
}

@Serializable
data class WatchHistoryItem(
    val parentId: String,
    val title: String,
    val coverUrl: String,
    val episodeTitle: String,
    val streamUrl: String,
    val episodeNumber: Int = 0,
    val positionMs: Long = 0L,
    val updatedAt: Long = 0L,
    val mediaKind: String = ""         // "ANIME", "MOVIE", "CARTOON", "K_DRAMA", "CLASSIC", "NIGERIAN"
) {
    /** Content-type derived from kind */
    val contentType: String get() = when (mediaKind.uppercase()) {
        "MOVIE" -> ContentType.MOVIE
        "CARTOON" -> ContentType.CARTOON
        "K_DRAMA" -> ContentType.K_DRAMA
        "CLASSIC" -> ContentType.CLASSIC
        "NIGERIAN" -> ContentType.NIGERIAN
        else -> ContentType.ANIME
    }
}

@Serializable
data class SearchHistoryItem(
    val tab: String,
    val query: String,
    val updatedAt: Long = 0L
)

@Serializable
data class DownloadIndex(
    val items: List<DownloadedItem> = emptyList(),
    val episodes: List<DownloadedEpisode> = emptyList(),
    val chapters: List<DownloadedChapter> = emptyList(),
    val readHistory: List<ReadHistoryItem> = emptyList(),
    val watchHistory: List<WatchHistoryItem> = emptyList(),
    val searchHistory: List<SearchHistoryItem> = emptyList()
)

@Serializable
data class UserSyncState(
    val favorites: List<FavoriteNovel> = emptyList(),
    val readHistory: List<ReadHistoryItem> = emptyList(),
    val watchHistory: List<WatchHistoryItem> = emptyList(),
    val searchHistory: List<SearchHistoryItem> = emptyList(),
    val updatedAt: Long = 0L
)
