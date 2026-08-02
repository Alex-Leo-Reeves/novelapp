package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.BuildKonfig

class TvNovelSearchRepository {
    private val sharedRepository = NovelSearchRepository(
        rapidApiKey = BuildKonfig.RAPID_API_KEY,
        rapidApiHost = BuildKonfig.RAPID_API_HOST
    )

    suspend fun searchNovels(query: String): List<UnifiedSearchResult> =
        sharedRepository.searchNovels(query)

    suspend fun fetchPopularNovels(page: Int = 1): List<UnifiedSearchResult> =
        sharedRepository.fetchPopularNovels(page)

    suspend fun fetchChapters(novelUrl: String, sourceName: String): List<Chapter> =
        sharedRepository.fetchChapters(novelUrl, sourceName)

    suspend fun fetchChapterText(chapterUrl: String, sourceName: String): String =
        sharedRepository.fetchChapterText(chapterUrl, sourceName)
}
