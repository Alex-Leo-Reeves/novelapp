package com.alexleoreeves.novelapp.tv.data

import kotlinx.serialization.Serializable

@Serializable
data class UnifiedSearchResult(
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val detailPageUrl: String = "",
    val sourceName: String = "",
    val author: String = "",
    val genre: String = "",
    val synopsis: String = "",
    val isManga: Boolean = false,
    val isComic: Boolean = false,
    val isAnime: Boolean = false,
    val isVideo: Boolean = false,
    val isPremium: Boolean = false,
    val mediaKind: String = "",
    val url: String = detailPageUrl,
    val animeResult: AnimeResult? = null,
    val chapters: List<Chapter> = emptyList()
)

@Serializable
data class AnimeResult(
    val id: String,
    val titleRomaji: String,
    val titleEnglish: String,
    val coverUrl: String,
    val synopsis: String = "",
    val episodeCount: Int = 0,
    val nextEpisode: Int = 0,
    val nextAiringAt: Long = 0L,
    val status: String = "RELEASING",
    val genres: List<String> = emptyList(),
    val sourceName: String = "AniList"
) {
    val displayTitle: String get() = titleEnglish.ifBlank { titleRomaji }
}

@Serializable
data class Chapter(val title: String, val url: String, val chapterNumber: Int = 0)

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

@Serializable
data class ReadHistoryItem(
    val parentId: String,
    val title: String,
    val coverUrl: String = "",
    val sourceName: String = "",
    val chapterTitle: String = "",
    val chapterUrl: String = "",
    val isManga: Boolean = false,
    val positionIndex: Int = 0,
    val timestamp: Long = 0L
)

@Serializable
data class WatchHistoryItem(
    val parentId: String,
    val title: String,
    val coverUrl: String = "",
    val episodeTitle: String = "",
    val streamUrl: String = "",
    val episodeNumber: Int = 0,
    val positionMs: Long = 0L,
    val timestamp: Long = 0L
)

@Serializable
data class BillingPlan(
    val id: String,
    val label: String,
    val amount: Int,
    val currency: String = "NGN",
    val maxDevices: Int? = null,
    val premium: Boolean = true,
    val description: String = ""
)

@Serializable
data class BillingCheckout(
    val link: String = "",
    val txRef: String = "",
    val amount: Int = 1000,
    val currency: String = "NGN",
    val alreadyPremium: Boolean = false,
    val premium: Boolean = false
)

data class TvMediaItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val format: String = "ANIME",
    val sourceName: String = "",
    val isManga: Boolean = false,
    val isComic: Boolean = false,
    val isAnime: Boolean = false,
    val isVideo: Boolean = false,
    val mediaKind: String = "",
    val detailPageUrl: String = ""
)

data class AppUpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val releaseNotes: List<String> = emptyList(),
    val forceUpdate: Boolean = false
) {
    val isAvailable: Boolean get() = versionCode > 0 && versionName.isNotBlank()
}

enum class TvSection(val label: String) {
    HOME("Home"),
    NOVELS("Novels"),
    MANGA("Manga"),
    COMICS("Comics"),
    ANIME("Anime"),
    DONGHUA("Donghua"),
    K_DRAMA("K-Drama"),
    CARTOON("Cartoon"),
    CLASSIC("Classic"),
    MOVIES("Movies"),
    NOLLYWOOD("Nollywood"),
    SPORTS("Sports"),
    DOWNLOADS("Downloads"),
    YOU("You")
}
