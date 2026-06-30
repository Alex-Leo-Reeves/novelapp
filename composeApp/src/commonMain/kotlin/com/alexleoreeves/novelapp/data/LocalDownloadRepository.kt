package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.currentTimeMillis
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

/**
 * Platform-agnostic local download repository.
 *
 * On Android: uses `Context.filesDir/downloads/index.json`
 * On Desktop: uses `~/.aninovelmanga/downloads/index.json`
 *
 * The expect function [readIndexJson] / [writeIndexJson] are resolved per platform.
 */
expect fun readIndexJson(): String?
expect fun writeIndexJson(json: String)

class LocalDownloadRepository {

    private fun loadIndex(): DownloadIndex {
        return try {
            val raw = readIndexJson() ?: return DownloadIndex()
            json.decodeFromString<DownloadIndex>(raw)
        } catch (e: Exception) {
            DownloadIndex()
        }
    }

    private fun saveIndex(index: DownloadIndex) {
        writeIndexJson(json.encodeToString(index))
    }

    // ── Read ──────────────────────────────────────────────────────────────
    fun getAllItems(): List<DownloadedItem> = loadIndex().items
    fun getAnimeItems(): List<DownloadedItem> = loadIndex().items.filter { it.type == "ANIME" }
    fun getMangaItems(): List<DownloadedItem> = loadIndex().items.filter { it.type == "MANGA" }
    fun getNovelItems(): List<DownloadedItem> = loadIndex().items.filter { it.type == "NOVEL" }

    fun getEpisodesFor(parentId: String): List<DownloadedEpisode> =
        loadIndex().episodes.filter { it.parentId == parentId }
            .sortedByDescending { it.episodeNumber }

    fun getChaptersFor(parentId: String): List<DownloadedChapter> =
        loadIndex().chapters.filter { it.parentId == parentId }
            .sortedByDescending { it.chapterNumber }

    // ── Write ─────────────────────────────────────────────────────────────
    fun addItem(item: DownloadedItem) {
        val idx = loadIndex()
        if (idx.items.none { it.id == item.id }) {
            saveIndex(idx.copy(items = idx.items + item))
        }
    }

    fun addEpisode(episode: DownloadedEpisode) {
        val idx = loadIndex()
        val exists = idx.episodes.any { it.parentId == episode.parentId && it.episodeNumber == episode.episodeNumber }
        if (!exists) {
            val updatedItems = idx.items.map { item ->
                if (item.id == episode.parentId) item.copy(totalItems = item.totalItems + 1)
                else item
            }
            saveIndex(idx.copy(episodes = idx.episodes + episode, items = updatedItems))
        }
    }

    fun addChapter(chapter: DownloadedChapter) {
        val idx = loadIndex()
        val exists = idx.chapters.any { it.parentId == chapter.parentId && it.chapterNumber == chapter.chapterNumber }
        if (!exists) {
            val updatedItems = idx.items.map { item ->
                if (item.id == chapter.parentId) item.copy(totalItems = item.totalItems + 1)
                else item
            }
            saveIndex(idx.copy(chapters = idx.chapters + chapter, items = updatedItems))
        }
    }

    fun deleteItem(itemId: String) {
        val idx = loadIndex()
        saveIndex(
            idx.copy(
                items = idx.items.filter { it.id != itemId },
                episodes = idx.episodes.filter { it.parentId != itemId },
                chapters = idx.chapters.filter { it.parentId != itemId },
                readHistory = idx.readHistory.filter { it.parentId != itemId },
                watchHistory = idx.watchHistory.filter { it.parentId != itemId }
            )
        )
    }

    fun deleteEpisode(parentId: String, episodeNumber: Int) {
        val idx = loadIndex()
        saveIndex(idx.copy(
            episodes = idx.episodes.filter { !(it.parentId == parentId && it.episodeNumber == episodeNumber) },
            watchHistory = idx.watchHistory.filter { !(it.parentId == parentId && it.episodeNumber == episodeNumber) }
        ))
    }

    fun deleteChapter(parentId: String, chapterNumber: Int) {
        val idx = loadIndex()
        saveIndex(idx.copy(
            chapters = idx.chapters.filter { !(it.parentId == parentId && it.chapterNumber == chapterNumber) },
            readHistory = idx.readHistory.filter { !(it.parentId == parentId && it.chapterTitle.contains("Chapter $chapterNumber", ignoreCase = true)) }
        ))
    }

    fun isEpisodeDownloaded(parentId: String, episodeNumber: Int): Boolean =
        loadIndex().episodes.any { it.parentId == parentId && it.episodeNumber == episodeNumber }

    fun isChapterDownloaded(parentId: String, chapterNumber: Int): Boolean =
        loadIndex().chapters.any { it.parentId == parentId && it.chapterNumber == chapterNumber }

    // ── History / Resume ─────────────────────────────────────────────────
    fun getReadHistory(): List<ReadHistoryItem> =
        loadIndex().readHistory.sortedByDescending { it.updatedAt }

    fun getWatchHistory(): List<WatchHistoryItem> =
        loadIndex().watchHistory.sortedByDescending { it.updatedAt }

    fun getReadProgress(chapterUrl: String): ReadHistoryItem? =
        loadIndex().readHistory.firstOrNull { it.chapterUrl == chapterUrl }

    fun getWatchProgress(streamUrl: String): WatchHistoryItem? =
        loadIndex().watchHistory.firstOrNull { it.streamUrl == streamUrl }

    fun getSearchHistory(tab: String): List<SearchHistoryItem> {
        val normalizedTab = tab.trim().uppercase()
        return loadIndex().searchHistory
            .filter { it.tab.equals(normalizedTab, ignoreCase = true) && it.query.isNotBlank() }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SEARCH_HISTORY_PER_TAB)
    }

    fun exportUserState(favorites: List<FavoriteNovel>): UserSyncState {
        val idx = loadIndex()
        return UserSyncState(
            favorites = favorites.sortedByDescending { it.addedAt }.take(MAX_FAVORITES),
            readHistory = idx.readHistory.sortedByDescending { it.updatedAt }.take(MAX_HISTORY_ITEMS),
            watchHistory = idx.watchHistory.sortedByDescending { it.updatedAt }.take(MAX_HISTORY_ITEMS),
            searchHistory = idx.searchHistory
                .filter { it.query.isNotBlank() }
                .sortedByDescending { it.updatedAt }
                .take(MAX_SEARCH_HISTORY_TOTAL),
            updatedAt = currentTimeMillis()
        )
    }

    fun mergeUserState(state: UserSyncState) {
        val idx = loadIndex()
        saveIndex(
            idx.copy(
                readHistory = mergeByLatest(
                    local = idx.readHistory,
                    remote = state.readHistory,
                    key = { it.chapterUrl },
                    updatedAt = { it.updatedAt }
                ).take(MAX_HISTORY_ITEMS),
                watchHistory = mergeByLatest(
                    local = idx.watchHistory,
                    remote = state.watchHistory,
                    key = { it.streamUrl },
                    updatedAt = { it.updatedAt }
                ).take(MAX_HISTORY_ITEMS),
                searchHistory = mergeByLatest(
                    local = idx.searchHistory,
                    remote = state.searchHistory,
                    key = { "${it.tab.uppercase()}:${it.query.trim().lowercase()}" },
                    updatedAt = { it.updatedAt }
                ).take(MAX_SEARCH_HISTORY_TOTAL)
            )
        )
    }

    fun recordReadProgress(item: ReadHistoryItem) {
        val idx = loadIndex()
        val updated = item.copy(
            positionIndex = item.positionIndex.coerceAtLeast(0),
            updatedAt = currentTimeMillis()
        )
        saveIndex(
            idx.copy(
                readHistory = (listOf(updated) + idx.readHistory.filter { it.chapterUrl != item.chapterUrl })
                    .take(MAX_HISTORY_ITEMS)
            )
        )
    }

    fun recordWatchProgress(item: WatchHistoryItem) {
        val idx = loadIndex()
        val updated = item.copy(
            positionMs = item.positionMs.coerceAtLeast(0L),
            updatedAt = currentTimeMillis()
        )
        saveIndex(
            idx.copy(
                watchHistory = (listOf(updated) + idx.watchHistory.filter { it.streamUrl != item.streamUrl })
                    .take(MAX_HISTORY_ITEMS)
            )
        )
    }

    fun recordSearchQuery(tab: String, query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return
        val cleanTab = tab.trim().uppercase().ifBlank { "NOVELS" }
        val idx = loadIndex()
        val updated = SearchHistoryItem(
            tab = cleanTab,
            query = cleanQuery,
            updatedAt = currentTimeMillis()
        )
        val merged = mergeByLatest(
            local = listOf(updated),
            remote = idx.searchHistory,
            key = { "${it.tab.uppercase()}:${it.query.trim().lowercase()}" },
            updatedAt = { it.updatedAt }
        )
        val trimmed = merged
            .groupBy { it.tab.uppercase() }
            .flatMap { (_, entries) -> entries.take(MAX_SEARCH_HISTORY_PER_TAB) }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SEARCH_HISTORY_TOTAL)
        saveIndex(idx.copy(searchHistory = trimmed))
    }

    private companion object {
        const val MAX_HISTORY_ITEMS = 40
        const val MAX_FAVORITES = 250
        const val MAX_SEARCH_HISTORY_PER_TAB = 3
        const val MAX_SEARCH_HISTORY_TOTAL = 60
    }
}

private fun <T> mergeByLatest(
    local: List<T>,
    remote: List<T>,
    key: (T) -> String,
    updatedAt: (T) -> Long
): List<T> {
    return (local + remote)
        .filter { key(it).isNotBlank() }
        .groupBy(key)
        .values
        .mapNotNull { group -> group.maxByOrNull(updatedAt) }
        .sortedByDescending(updatedAt)
}
