package com.alexleoreeves.novelapp.data

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
//  Download Models — persisted locally as a JSON index
// ─────────────────────────────────────────────────────────────────────────────

enum class DownloadType { ANIME, MANGA, NOVEL }

@Serializable
data class DownloadedItem(
    val id: String,                    // unique key (e.g. "anilist_12345")
    val title: String,
    val coverUrl: String,
    val type: String,                  // "ANIME", "MANGA", "NOVEL"
    val sourceName: String,
    val downloadedAt: Long = 0L,
    val totalItems: Int = 0            // episodes or chapters downloaded
)

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
    val positionIndex: Int = 0,
    val updatedAt: Long = 0L
)

@Serializable
data class WatchHistoryItem(
    val parentId: String,
    val title: String,
    val coverUrl: String,
    val episodeTitle: String,
    val streamUrl: String,
    val episodeNumber: Int = 0,
    val positionMs: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class DownloadIndex(
    val items: List<DownloadedItem> = emptyList(),
    val episodes: List<DownloadedEpisode> = emptyList(),
    val chapters: List<DownloadedChapter> = emptyList(),
    val readHistory: List<ReadHistoryItem> = emptyList(),
    val watchHistory: List<WatchHistoryItem> = emptyList()
)

@Serializable
data class UserSyncState(
    val favorites: List<FavoriteNovel> = emptyList(),
    val readHistory: List<ReadHistoryItem> = emptyList(),
    val watchHistory: List<WatchHistoryItem> = emptyList(),
    val updatedAt: Long = 0L
)
