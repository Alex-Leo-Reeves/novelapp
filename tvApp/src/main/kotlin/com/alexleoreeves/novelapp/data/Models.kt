package com.alexleoreeves.novelapp.data

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

enum class TvSection(val label: String) {
    HOME("Home"),
    NOVELS("Novels"),
    CREATION("Creation"),
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
