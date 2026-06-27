package com.alexleoreeves.novelapp.data

/**
 * Contract that every novel source (API or web scraper) must implement.
 * Each implementation handles one specific content provider.
 */
interface NovelSource {
    /** Human-readable name shown in search result badges */
    val sourceName: String

    /**
     * Search for novels matching [query].
     * Must never throw — wrap errors and return emptyList() if it fails.
     */
    suspend fun search(query: String): List<UnifiedSearchResult>

    /**
     * Fetch a list of chapters for the novel at [novelUrl].
     */
    suspend fun fetchChapters(novelUrl: String): List<Chapter>

    /**
     * Fetch the clean, plain-text content for a single chapter at [chapterUrl].
     * Strips ads, nav elements, and other HTML noise.
     */
    suspend fun fetchChapterText(chapterUrl: String): String

    /**
     * Fetch a list of trending / popular novels for the Discover home screen.
     * Returns sensible defaults / empty list on failure.
     */
    suspend fun fetchPopular(page: Int = 1): List<UnifiedSearchResult>
}
