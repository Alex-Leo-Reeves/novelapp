package com.alexleoreeves.novelapp.data

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
                chapters = idx.chapters.filter { it.parentId != itemId }
            )
        )
    }

    fun deleteEpisode(parentId: String, episodeNumber: Int) {
        val idx = loadIndex()
        saveIndex(idx.copy(
            episodes = idx.episodes.filter { !(it.parentId == parentId && it.episodeNumber == episodeNumber) }
        ))
    }

    fun deleteChapter(parentId: String, chapterNumber: Int) {
        val idx = loadIndex()
        saveIndex(idx.copy(
            chapters = idx.chapters.filter { !(it.parentId == parentId && it.chapterNumber == chapterNumber) }
        ))
    }

    fun isEpisodeDownloaded(parentId: String, episodeNumber: Int): Boolean =
        loadIndex().episodes.any { it.parentId == parentId && it.episodeNumber == episodeNumber }

    fun isChapterDownloaded(parentId: String, chapterNumber: Int): Boolean =
        loadIndex().chapters.any { it.parentId == parentId && it.chapterNumber == chapterNumber }
}
