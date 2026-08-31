package com.alexleoreeves.novelapp.data

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TvConfigSection(val key: String = "", val label: String = "")

@Serializable
data class TvConfigRow(val key: String = "", val label: String = "", val type: String = "")

@Serializable
data class TvBranding(
    val title: String = "NovaRead TV",
    val tagline: String = "Anime · Novels · Manga · Movies"
)

@Serializable
data class TvFeatureFlags(
    val showSports: Boolean = true,
    val showDownloads: Boolean = true,
    val showPhonePair: Boolean = true,
    val showComics: Boolean = true,
    val showDonghua: Boolean = true,
    val showKDrama: Boolean = true,
    val showCartoons: Boolean = true,
    val showClassic: Boolean = true,
    val showNollywood: Boolean = true
)

@Serializable
data class TvRemoteConfig(
    val version: Int = 1,
    val refreshSeconds: Int = 300,
    val branding: TvBranding = TvBranding(),
    val featureFlags: TvFeatureFlags = TvFeatureFlags(),
    val sidebar: List<TvConfigSection> = emptyList(),
    val homeRows: List<TvConfigRow> = emptyList()
) {
    val effectiveRefreshMillis: Long get() = (refreshSeconds.coerceAtLeast(30) * 1000L).toLong()
}

object TvRemoteConfigDefaults {
    val default = TvRemoteConfig(
        version = 1,
        refreshSeconds = 300,
        sidebar = listOf(
            TvConfigSection("home", "Home"),
            TvConfigSection("novels", "Novels"),
            TvConfigSection("creation", "Creation"),
            TvConfigSection("manga", "Manga"),
            TvConfigSection("comics", "Comics"),
            TvConfigSection("anime", "Anime"),
            TvConfigSection("donghua", "Donghua"),
            TvConfigSection("kdrama", "K-Drama"),
            TvConfigSection("cartoon", "Cartoon"),
            TvConfigSection("classic", "Classic"),
            TvConfigSection("movies", "Movies"),
            TvConfigSection("live_tv", "Live TV"),
            TvConfigSection("sports", "Sports"),
            TvConfigSection("downloads", "Downloads"),
            TvConfigSection("you", "You")
        ),
        homeRows = listOf(
            TvConfigRow("recommended", "✨ Recommended For You", "recommended"),
            TvConfigRow("trendingAnime", "🔥 Trending Anime", "anime"),
            TvConfigRow("popularNovels", "📚 Popular Novels", "novel"),
            TvConfigRow("topManga", "🎨 Top Manga", "manga"),
            TvConfigRow("newMovies", "🎬 New Movies", "movie")
        )
    )
}

/** Fetches the server-driven TV config. Returns null on failure so callers fall back to baked-in defaults. */
suspend fun fetchTvConfig(): TvRemoteConfig? {
    return try {
        val client = platformHttpClient()
        try {
            val body = client.get("${ApiConfig.API_BASE_URL}/tv/config").bodyAsText()
            Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<TvRemoteConfig>(body)
        } finally {
            client.close()
        }
    } catch (_: Exception) {
        null
    }
}

/** Converts a config sidebar key to the matching TvSection. Unknown keys fall back to HOME. */
fun TvConfigSection.toSection(): TvSection = when (key.lowercase()) {
    "home" -> TvSection.HOME
    "novels" -> TvSection.NOVELS
    "creation" -> TvSection.CREATION
    "manga" -> TvSection.MANGA
    "comics" -> TvSection.COMICS
    "anime" -> TvSection.ANIME
    "donghua" -> TvSection.DONGHUA
    "kdrama", "k-drama" -> TvSection.K_DRAMA
    "cartoon" -> TvSection.CARTOON
    "classic" -> TvSection.CLASSIC
    "movies" -> TvSection.MOVIES
    "live_tv", "livetv", "live tv", "nollywood" -> TvSection.LIVE_TV
    "sports" -> TvSection.SPORTS
    "downloads" -> TvSection.DOWNLOADS
    "you" -> TvSection.YOU
    else -> TvSection.HOME
}

fun TvRemoteConfig.visibleSections(): List<TvConfigSection> {
    return sidebar.filter { section ->
        val key = section.key.lowercase()
        when (key) {
            "sports" -> featureFlags.showSports
            "downloads" -> featureFlags.showDownloads
            "comics" -> featureFlags.showComics
            "donghua" -> featureFlags.showDonghua
            "kdrama", "k-drama" -> featureFlags.showKDrama
            "cartoon" -> featureFlags.showCartoons
            "classic" -> featureFlags.showClassic
            "nollywood" -> featureFlags.showNollywood
            else -> true
        }
    }.ifEmpty { TvRemoteConfigDefaults.default.sidebar }
}
