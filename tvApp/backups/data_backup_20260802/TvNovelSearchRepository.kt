package com.alexleoreeves.novelapp.tv.data

import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
// BuildKonfig is not available, using empty string for RapidAPI key to fail gracefully or not use WebNovelApiSource if key missing

class TvNovelSearchRepository {
    private val sourceSemaphore = Semaphore(3)

    val httpClient = platformHttpClient()

    private val sources: List<NovelSource> = listOf(
        WebNovelApiSource(httpClient, "", ""),
        FreeWebNovelSource(httpClient),
        LightNovelPubSource(httpClient),
        BoxNovelSource(httpClient),
        WuxiaWorldSource(httpClient),
        ReadNovelFullSource(httpClient),
        RoyalRoadSource(httpClient)
    )

    suspend fun searchNovels(query: String): List<UnifiedSearchResult> = coroutineScope {
        val sourceResults = sources.map { source ->
            async {
                try {
                    val results = sourceSemaphore.withPermit {
                        withTimeoutOrNull(8_000) { source.search(query) }.orEmpty()
                    }
                    results
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        sourceResults
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
            .take(72)
    }

    suspend fun fetchPopularNovels(page: Int = 1): List<UnifiedSearchResult> = coroutineScope {
        val novelTasks = sources.map { source ->
            async {
                try {
                    sourceSemaphore.withPermit {
                        withTimeoutOrNull(8_000) { source.fetchPopular(page) }.orEmpty()
                    }
                } catch (e: Exception) { emptyList() }
            }
        }
        novelTasks.awaitAll().flatten()
            .filter { it.title.isNotBlank() && !it.title.isNavigationTitle() }
            .distinctBy { "${it.sourceName}:${it.detailPageUrl.ifBlank { it.title }}".lowercase() }
    }

    suspend fun fetchChapters(novelUrl: String, sourceName: String): List<Chapter> {
        val source = sources.find { it.sourceName.equals(sourceName, ignoreCase = true) } ?: return emptyList()
        return try {
            source.fetchChapters(novelUrl)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchChapterText(chapterUrl: String, sourceName: String): String {
        val source = sources.find { it.sourceName.equals(sourceName, ignoreCase = true) } ?: return ""
        return try {
            source.fetchChapterText(chapterUrl)
        } catch (e: Exception) {
            "Failed to load chapter text."
        }
    }
}
