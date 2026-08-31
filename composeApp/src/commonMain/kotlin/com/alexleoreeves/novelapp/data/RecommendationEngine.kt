package com.alexleoreeves.novelapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class RecommendationEngine(
    private val tmdbSource: TmdbSource,
    private val aniListSource: AniListSource,
    private val downloadRepo: LocalDownloadRepository
) {
    suspend fun getRecommendations(
        searchHistoryQueries: List<String> = emptyList(),
        limit: Int = 25
    ): List<UnifiedSearchResult> = coroutineScope {
        val results = mutableListOf<UnifiedSearchResult>()
        val seenIds = mutableSetOf<String>()

        // 1. Gather seeds from history
        val watchedItems = downloadRepo.getAllItems().take(8)
        val watchedIds = watchedItems.map { it.id }.toSet()

        // 2. Fetch TMDB / AniList recommendations for watched items in parallel
        val recJobs = watchedItems.map { item ->
            async {
                val mediaType = if (item.type.equals("MOVIE", ignoreCase = true)) "movie" else "tv"
                val rawId = item.id.replace(Regex("""[^0-9]"""), "")
                if (rawId.isNotBlank()) {
                    if (item.type.equals("ANIME", ignoreCase = true)) {
                        aniListSource.search(item.title, page = 1)
                            .map { it.toUnifiedSearchResult() }
                    } else {
                        tmdbSource.fetchRecommendations(mediaType, rawId)
                    }
                } else emptyList()
            }
        }

        // 3. Fetch search keyword recommendations
        val searchJobs = searchHistoryQueries.take(4).map { query ->
            async {
                if (query.isNotBlank()) {
                    tmdbSource.searchVideo(VideoCategory.MOVIES, query)
                } else emptyList()
            }
        }

        // 4. Await all jobs
        recJobs.forEach { job ->
            val list = runCatching { job.await() }.getOrElse { emptyList() }
            list.forEach { item ->
                if (item.id !in watchedIds && seenIds.add(item.id)) {
                    results.add(item)
                }
            }
        }

        searchJobs.forEach { job ->
            val list = runCatching { job.await() }.getOrElse { emptyList() }
            list.forEach { item ->
                if (item.id !in watchedIds && seenIds.add(item.id)) {
                    results.add(item)
                }
            }
        }

        // 5. Fallback if empty (e.g. fresh install) -> populate with top trending anime & movies
        if (results.size < 10) {
            val trendingAnime = runCatching { aniListSource.fetchTrending(1, 10).map { it.toUnifiedSearchResult() } }.getOrElse { emptyList() }
            val trendingMovies = runCatching { tmdbSource.fetchVideo(VideoCategory.MOVIES, 1).take(10) }.getOrElse { emptyList() }
            (trendingMovies + trendingAnime).forEach { item ->
                if (seenIds.add(item.id)) {
                    results.add(item)
                }
            }
        }

        results.shuffled().take(limit)
    }
}
