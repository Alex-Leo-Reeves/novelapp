package com.alexleoreeves.novelapp

import com.alexleoreeves.novelapp.data.AnimeResult
import com.alexleoreeves.novelapp.data.NovelSearchRepository
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.data.VideoCategory
import com.alexleoreeves.novelapp.audio.KokoroNarrationController
import com.alexleoreeves.novelapp.data.AuthApi
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.DeveloperContact
import com.alexleoreeves.novelapp.platform.IosUserSessionStore
import com.alexleoreeves.novelapp.platform.SavedUserAccount
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
    private val narrationController = KokoroNarrationController()
    private val authApi = AuthApi()
    private val sessionStore = IosUserSessionStore()

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

    fun savedAccountJson(): String =
        json.encodeToString(
            IosAuthPayload(
                account = sessionStore.loadAccount()?.toIosAccount()
            )
        )

    fun verifySavedAccount(
        completion: (String, String?) -> Unit
    ) {
        val saved = sessionStore.loadAccount()
        if (saved == null) {
            completion(json.encodeToString(IosAuthPayload(account = null)), null)
            return
        }

        scope.launch {
            runCatching {
                authApi.me(saved.authToken).also { sessionStore.saveAccount(it) }
            }.fold(
                onSuccess = {
                    completion(
                        json.encodeToString(IosAuthPayload(account = it.toIosAccount())),
                        null
                    )
                },
                onFailure = {
                    sessionStore.clearAccount()
                    completion(
                        json.encodeToString(IosAuthPayload(account = null)),
                        it.readableMessage()
                    )
                }
            )
        }
    }

    fun loginJson(
        email: String,
        password: String,
        completion: (String, String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                authApi.login(email.trim(), password).also { sessionStore.saveAccount(it) }
            }.fold(
                onSuccess = {
                    completion(
                        json.encodeToString(IosAuthPayload(account = it.toIosAccount())),
                        null
                    )
                },
                onFailure = {
                    completion(
                        json.encodeToString(IosAuthPayload(account = null)),
                        it.readableMessage()
                    )
                }
            )
        }
    }

    fun registerJson(
        username: String,
        email: String,
        password: String,
        completion: (String, String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                authApi.register(username.trim(), email.trim(), password).also { sessionStore.saveAccount(it) }
            }.fold(
                onSuccess = {
                    completion(
                        json.encodeToString(IosAuthPayload(account = it.toIosAccount())),
                        null
                    )
                },
                onFailure = {
                    completion(
                        json.encodeToString(IosAuthPayload(account = null)),
                        it.readableMessage()
                    )
                }
            )
        }
    }

    fun logout() {
        val account = sessionStore.loadAccount()
        sessionStore.clearAccount()
        if (account != null) {
            scope.launch {
                runCatching { authApi.logout(account.authToken) }
            }
        }
    }

    fun chaptersJson(
        kind: String,
        detailUrl: String,
        sourceName: String,
        completion: (String, String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                val chapters = if (kind.equals("manga", ignoreCase = true)) {
                    repository.fetchMangaChapters(detailUrl, sourceName).map {
                        IosChapterItem(
                            title = it.title,
                            url = it.url,
                            chapterNumber = it.chapterNumber
                        )
                    }
                } else {
                    repository.fetchChapters(detailUrl, sourceName).map {
                        IosChapterItem(
                            title = it.title,
                            url = it.url,
                            chapterNumber = it.chapterNumber
                        )
                    }
                }
                json.encodeToString(IosChapterPayload(chapters))
            }.fold(
                onSuccess = { completion(it, null) },
                onFailure = { completion(emptyChaptersPayload(), it.readableMessage()) }
            )
        }
    }

    fun chapterTextJson(
        chapterUrl: String,
        sourceName: String,
        completion: (String, String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                json.encodeToString(
                    IosTextPayload(
                        text = repository.fetchChapterText(chapterUrl, sourceName)
                    )
                )
            }.fold(
                onSuccess = { completion(it, null) },
                onFailure = {
                    completion(
                        json.encodeToString(IosTextPayload("")),
                        it.readableMessage()
                    )
                }
            )
        }
    }

    fun mangaPagesJson(
        chapterUrl: String,
        sourceName: String,
        completion: (String, String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                json.encodeToString(
                    IosMangaPagesPayload(
                        pages = repository.fetchMangaPages(chapterUrl, sourceName)
                    )
                )
            }.fold(
                onSuccess = { completion(it, null) },
                onFailure = {
                    completion(
                        json.encodeToString(IosMangaPagesPayload(emptyList())),
                        it.readableMessage()
                    )
                }
            )
        }
    }

    fun watchUrlJson(
        kind: String,
        title: String,
        detailUrl: String
    ): String =
        json.encodeToString(
            IosWatchPayload(
                url = resolveIosWatchUrl(kind, title, detailUrl)
            )
        )

    fun playNarration(text: String, cacheKey: String) {
        narrationController.playText(
            text = text,
            cacheKey = cacheKey,
            persistAudioCache = false
        )
    }

    fun stopNarration() {
        narrationController.stop()
    }

    fun narrationStatusJson(): String {
        val status = narrationController.voiceSetupStatus.value
        return json.encodeToString(
            IosNarrationPayload(
                isPlaying = narrationController.isPlaying.value,
                isBuffering = narrationController.isBuffering.value,
                message = status.userMessage,
                progress = status.progressFraction
            )
        )
    }

    fun close() {
        narrationController.close()
        scope.cancel()
        repository.httpClient.close()
    }

    private fun emptyPayload(): String =
        json.encodeToString(IosContentPayload(emptyList()))

    private fun emptyChaptersPayload(): String =
        json.encodeToString(IosChapterPayload(emptyList()))
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

@Serializable
private data class IosNarrationPayload(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val message: String,
    val progress: Float?
)

@Serializable
private data class IosAuthPayload(
    val account: IosAccountPayload?
)

@Serializable
private data class IosAccountPayload(
    val id: String,
    val username: String,
    val email: String,
    val plan: String,
    val billingStatus: String,
    val createdAt: String
)

@Serializable
private data class IosChapterPayload(
    val chapters: List<IosChapterItem>
)

@Serializable
private data class IosChapterItem(
    val title: String,
    val url: String,
    val chapterNumber: Int
)

@Serializable
private data class IosTextPayload(
    val text: String
)

@Serializable
private data class IosMangaPagesPayload(
    val pages: List<String>
)

@Serializable
private data class IosWatchPayload(
    val url: String
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

private fun SavedUserAccount.toIosAccount(): IosAccountPayload =
    IosAccountPayload(
        id = id,
        username = username,
        email = email,
        plan = plan,
        billingStatus = billingStatus,
        createdAt = createdAt
    )

private fun resolveIosWatchUrl(kind: String, title: String, detailUrl: String): String {
    val normalizedKind = kind.lowercase()
    val tmdbMatch = Regex("""tmdb://([^/]+)/(\d+)""").find(detailUrl)
    if (tmdbMatch != null) {
        val mediaType = tmdbMatch.groupValues[1]
        val id = tmdbMatch.groupValues[2]
        return if (mediaType == "movie" || normalizedKind == "movie") {
            "https://vidlink.pro/movie/$id"
        } else {
            "https://vidlink.pro/tv/$id/1/1"
        }
    }

    if (detailUrl.startsWith("http", ignoreCase = true)) return detailUrl

    return "https://www.google.com/search?q=" +
        (title.ifBlank { detailUrl }).trim().replace(" ", "+")
}
