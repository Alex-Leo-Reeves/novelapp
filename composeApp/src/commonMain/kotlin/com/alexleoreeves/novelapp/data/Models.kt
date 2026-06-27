package com.alexleoreeves.novelapp.data

import kotlinx.serialization.Serializable

// ───────────────────────────────────────────────
//  Unified result across all search sources
// ───────────────────────────────────────────────
@Serializable
data class UnifiedSearchResult(
    val id: String,
    val title: String,
    val coverUrl: String,
    val detailPageUrl: String = "",
    val sourceName: String,
    val author: String = "",
    val genre: String = "",
    val synopsis: String = "",
    val isManga: Boolean = false,
    val isAnime: Boolean = false,
    // Convenience alias used in newer code
    val url: String = detailPageUrl,
    @kotlinx.serialization.Transient
    val animeResult: AnimeResult? = null
)

@Serializable
data class MangaChapter(
    val title: String,
    val url: String,
    val chapterNumber: Int = 0
)

enum class MangaScrollMode(val displayName: String) {
    WEBTOON("Webtoon"),
    RTL("Right-to-Left"),
    LTR("Left-to-Right")
}

// ───────────────────────────────────────────────
//  Chapter reference (title + URL)
// ───────────────────────────────────────────────
@Serializable
data class Chapter(
    val title: String,
    val url: String,
    val chapterNumber: Int = 0
)

// ───────────────────────────────────────────────
//  App Themes
// ───────────────────────────────────────────────
enum class AppTheme(val displayName: String) {
    DARK("Dark"),
    WHITE_PINK("White Pink"),
    LAVENDER_MINT("Lavender Mint"),
    GREEN("Green Forest")
}

// ───────────────────────────────────────────────
//  Player state
// ───────────────────────────────────────────────
data class PlayerState(
    val isPlaying: Boolean = false,
    val novelTitle: String = "",
    val chapterTitle: String = "",
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val isBuffering: Boolean = false
)

// ───────────────────────────────────────────────
//  Favorites DB entity
// ───────────────────────────────────────────────
@Serializable
data class FavoriteNovel(
    val id: String,
    val title: String,
    val coverUrl: String,
    val detailPageUrl: String,
    val sourceName: String,
    val author: String = "",
    val genre: String = "",
    val addedAt: Long = 0L
)

// ───────────────────────────────────────────────
//  Category chips for discover feed
// ───────────────────────────────────────────────
enum class NovelCategory(val label: String) {
    ALL("All"),
    ACTION("Action"),
    ROMANCE("Romance"),
    FANTASY("Fantasy"),
    CULTIVATION("Cultivation"),
    SYSTEM("System"),
    SCIFI("Sci-Fi"),
    HORROR("Horror"),
    COMEDY("Comedy"),
    MYSTERY("Mystery"),
    XIANXIA("Xianxia"),
    XUANHUAN("Xuanhuan")
}

// ───────────────────────────────────────────────
//  Anime Models
// ───────────────────────────────────────────────
@Serializable
data class AnimeResult(
    val id: String,
    val titleRomaji: String,
    val titleEnglish: String,
    val coverUrl: String,
    val synopsis: String = "",
    val episodeCount: Int = 0,
    val nextEpisode: Int = 0,
    val nextAiringAt: Long = 0L,   // Unix timestamp
    val status: String = "RELEASING",
    val genres: List<String> = emptyList(),
    val sourceName: String = "AniList"
) {
    val displayTitle: String get() = titleEnglish.ifEmpty { titleRomaji }
}

@Serializable
data class AnimeEpisode(
    val episodeNumber: Int,
    val title: String,
    val url: String,       // GogoAnime/Hianime page URL to scrape stream from
    val thumbnail: String = ""
)

