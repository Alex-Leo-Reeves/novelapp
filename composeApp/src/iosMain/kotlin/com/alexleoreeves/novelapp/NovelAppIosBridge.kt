package com.alexleoreeves.novelapp

import com.alexleoreeves.novelapp.data.AnimeResult
import com.alexleoreeves.novelapp.data.NovelSearchRepository
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.data.VideoCategory
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.DeveloperContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NovelAppIosBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = NovelSearchRepository(
        rapidApiKey = BuildKonfig.RAPID_API_KEY,
        rapidApiHost = BuildKonfig.RAPID_API_HOST
    )

    fun loadHomeJson(
        tab: String,
        page: Int,
        completion: (String, String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                val normalizedTab = tab.normalizedTab()
                val safePage = page.coerceAtLeast(1)
                val items = when (normalizedTab) {
                    "manga" -> repository.fetchPopularManga(safePage).map { it.toIosItem("manga") }
                    "anime" -> repository.fetchCurrentlyAiring(safePage).map { it.toIosItem() }
                    "kdrama" -> repository.fetchVideo(VideoCategory.K_DRAMA, safePage).map { it.toIosItem("kdrama") }
                    "cartoon" -> repository.fetchVideo(VideoCategory.CARTOON, safePage).map { it.toIosItem("cartoon") }
                    "movies" -> repository.fetchVideo(VideoCategory.MOVIES, safePage).map { it.toIosItem("movie") }
                    else -> repository.fetchPopularNovels(safePage).map { it.toIosItem("novel") }
                }
                json.encodeToString(IosContentPayload(items))
            }.fold(
                onSuccess = { completion(it, null) },
                onFailure = { completion(emptyPayload(), it.readableMessage()) }
            )
        }
    }

    fun searchJson(
        tab: String,
        query: String,
        page: Int,
        completion: (String, String?) -> Unit
    ) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            loadHomeJson(tab, page, completion)
            return
        }

        scope.launch {
            runCatching {
                val items = when (tab.normalizedTab()) {
                    "manga" -> repository.searchManga(trimmedQuery).map { it.toIosItem("manga") }
                    "anime" -> repository.searchAnime(trimmedQuery).map { it.toIosItem() }
                    "kdrama" -> repository.searchVideo(VideoCategory.K_DRAMA, trimmedQuery, page).map { it.toIosItem("kdrama") }
                    "cartoon" -> repository.searchVideo(VideoCategory.CARTOON, trimmedQuery, page).map { it.toIosItem("cartoon") }
                    "movies" -> repository.searchVideo(VideoCategory.MOVIES, trimmedQuery, page).map { it.toIosItem("movie") }
                    else -> repository.searchNovels(trimmedQuery).map { it.toIosItem("novel") }
                }
                json.encodeToString(IosContentPayload(items))
            }.fold(
                onSuccess = { completion(it, null) },
                onFailure = { completion(emptyPayload(), it.readableMessage()) }
            )
        }
    }

    fun developerJson(): String =
        json.encodeToString(
            IosDeveloperPayload(
                name = DeveloperContact.NAME,
                email = DeveloperContact.EMAIL,
                telegramUrl = DeveloperContact.TELEGRAM_CHANNEL_URL,
                whatsappUrl = DeveloperContact.WHATSAPP_CHANNEL_URL
            )
        )

    fun versionJson(): String =
        json.encodeToString(
            IosVersionPayload(
                versionCode = AppReleaseConfig.CURRENT_VERSION_CODE,
                versionName = AppReleaseConfig.CURRENT_VERSION_NAME,
                apiBaseUrl = AppReleaseConfig.API_BASE_URL,
                updateManifestUrl = AppReleaseConfig.UPDATE_MANIFEST_URL,
                downloadUrl = AppReleaseConfig.DOWNLOAD_URL
            )
        )

    fun close() {
        scope.cancel()
        repository.httpClient.close()
    }

    private fun emptyPayload(): String =
        json.encodeToString(IosContentPayload(emptyList()))
}

@Serializable
private data class IosContentPayload(
    val items: List<IosContentItem>
)

@Serializable
private data class IosContentItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val detailUrl: String,
    val sourceName: String,
    val kind: String,
    val synopsis: String
)

@Serializable
private data class IosDeveloperPayload(
    val name: String,
    val email: String,
    val telegramUrl: String,
    val whatsappUrl: String
)

@Serializable
private data class IosVersionPayload(
    val versionCode: Int,
    val versionName: String,
    val apiBaseUrl: String,
    val updateManifestUrl: String,
    val downloadUrl: String
)

private fun UnifiedSearchResult.toIosItem(fallbackKind: String): IosContentItem {
    val resolvedKind = when {
        isAnime -> "anime"
        isManga -> "manga"
        isVideo && mediaKind.equals(VideoCategory.K_DRAMA.name, ignoreCase = true) -> "kdrama"
        isVideo && mediaKind.equals(VideoCategory.CARTOON.name, ignoreCase = true) -> "cartoon"
        isVideo && mediaKind.equals(VideoCategory.MOVIES.name, ignoreCase = true) -> "movie"
        mediaKind.isNotBlank() -> mediaKind.lowercase()
        else -> fallbackKind
    }

    val subtitle = listOf(author, genre, sourceName)
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    return IosContentItem(
        id = id.ifBlank { "${sourceName}:${detailPageUrl.ifBlank { url }.ifBlank { title }}" },
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl,
        detailUrl = detailPageUrl.ifBlank { url },
        sourceName = sourceName,
        kind = resolvedKind,
        synopsis = synopsis
    )
}

private fun AnimeResult.toIosItem(): IosContentItem =
    IosContentItem(
        id = "anilist_$id",
        title = displayTitle,
        subtitle = genres.take(3).joinToString(", ").ifBlank { status },
        coverUrl = coverUrl,
        detailUrl = "anilist:$id",
        sourceName = sourceName,
        kind = "anime",
        synopsis = synopsis
    )

private fun String.normalizedTab(): String =
    trim()
        .lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")
        .let { raw ->
            when (raw) {
                "kdrama", "drama", "korean" -> "kdrama"
                "movie", "movies", "film" -> "movies"
                "cartoon", "cartoons" -> "cartoon"
                "anime" -> "anime"
                "manga" -> "manga"
                else -> "novels"
            }
        }

private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Unable to load this section right now."
