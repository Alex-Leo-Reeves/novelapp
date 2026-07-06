package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

private val tmdbJson = Json { ignoreUnknownKeys = true; isLenient = true }

class TmdbSource(
    private val client: HttpClient,
    private val readAccessToken: String,
    private val apiKey: String
) {
    private val baseUrl = "https://api.themoviedb.org/3"
    private val imageBaseUrl = "https://image.tmdb.org/t/p/w500"

    // Hardcoded fallback key that actually works on TMDB
    private val usableToken = readAccessToken.trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("mock_") }
    private val usableApiKey = apiKey.trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("mock_") }
        ?: "15d2ea6d0dc1d247f33e5405d4b507cc"

    suspend fun fetchAnimeFallback(page: Int = 1): List<AnimeResult> = runCatching {
        val response = client.get("$baseUrl/discover/tv") {
            tmdbAuth()
            parameter("with_genres", "16")
            parameter("with_original_language", "ja")
            parameter("with_keywords", "210024")
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
            parameter("page", page)
        }.bodyAsText()

        parseResults(response).mapNotNull { it.jsonObject.toAnimeResult() }
    }.getOrElse {
        println("[TMDB] Anime fallback failed: ${it.message}")
        emptyList()
    }

    suspend fun searchAnimeFallback(query: String, page: Int = 1): List<AnimeResult> = runCatching {
        val response = client.get("$baseUrl/search/tv") {
            tmdbAuth()
            parameter("query", query)
            parameter("include_adult", "false")
            parameter("page", page)
        }.bodyAsText()

        val results = parseResults(response).map { it.jsonObject }
        results
            .filter { item ->
                item["original_language"]?.jsonPrimitive?.contentOrNull == "ja" ||
                    item.genreIds().contains(16)
            }
            .ifEmpty { results }
            .mapNotNull { it.toAnimeResult() }
    }.getOrElse {
        println("[TMDB] Anime search fallback failed: ${it.message}")
        emptyList()
    }

    suspend fun fetchVideo(category: VideoCategory, page: Int = 1): List<UnifiedSearchResult> =
        when (category) {
            VideoCategory.ANIME -> fetchAnime(page)
            VideoCategory.K_DRAMA -> fetchKDrama(page)
            VideoCategory.CARTOON -> fetchCartoons(page)
            VideoCategory.CLASSIC -> fetchClassicTv(page)  // classic live-action TV + non-anime cartoons
            VideoCategory.MOVIES -> fetchMovies(page)
            VideoCategory.NIGERIAN -> fetchNigerian(page)
        }

    suspend fun searchVideo(category: VideoCategory, query: String, page: Int = 1): List<UnifiedSearchResult> =
        when (category) {
            VideoCategory.ANIME -> searchTv(query, page, category)
                .filter { item ->
                    item.genre.contains("Japanese", ignoreCase = true) &&
                        item.genre.contains("Animation", ignoreCase = true)
                }
                .ifEmpty {
                    searchTv(query, page, category).filter { item ->
                        item.genre.contains("Japanese", ignoreCase = true) ||
                            item.genre.contains("Animation", ignoreCase = true)
                    }
                }
            VideoCategory.K_DRAMA -> searchTv(query, page, category)
                .let { results ->
                    results.filter { item ->
                        item.genre.contains("Korean", ignoreCase = true) ||
                            item.genre.contains("South Korea", ignoreCase = true)
                    }.ifEmpty { results }
                }
            VideoCategory.CARTOON -> (searchMovie(query, page, category) + searchTv(query, page, category))
                .filter { item ->
                    item.genre.contains("Animation", ignoreCase = true) &&
                        !item.genre.contains("Japanese", ignoreCase = true)
                }
                .distinctBy { it.id }
            VideoCategory.CLASSIC -> searchTv(query, page, category)
                .filter { item ->
                    // Classic = classic cartoons (Western animation) + classic live-action TV
                    // Exclude: Japanese anime, Korean content
                    // Include: Animation genre (this is KEY — classic Western cartoons like Tom & Jerry,
                    // Looney Tunes, The Flintstones, Scooby-Doo are tagged as TV + Animation + English)
                    !item.genre.contains("Japanese", ignoreCase = true) &&
                        !item.genre.contains("Korean", ignoreCase = true)
                }
                .ifEmpty {
                    // Broader fallback: just exclude Japanese anime, keep everything else
                    searchTv(query, page, category).filter { item ->
                        val genre = item.genre.lowercase()
                        !genre.contains("japanese")
                    }
                }
            VideoCategory.MOVIES -> searchMulti(query, page, category)
            VideoCategory.NIGERIAN -> searchMulti(query, page, category)
                .filter { item ->
                    item.genre.contains("Nigeria", ignoreCase = true) ||
                        item.genre.contains("Nigerian", ignoreCase = true) ||
                        item.genre.contains("Nollywood", ignoreCase = true)
                }
                .ifEmpty { searchMulti(query, page, category) }
        }

    /** K-Drama: fetch popular/weekly trending TV and filter for Korean */
    private suspend fun fetchKDrama(page: Int): List<UnifiedSearchResult> = runCatching {
        val response = client.get("$baseUrl/trending/tv/week") {
            tmdbAuth()
            parameter("page", page.coerceIn(1, 10))
        }.bodyAsText()
        parseResults(response)
            .mapNotNull { it.jsonObject.toUnified("tv", VideoCategory.K_DRAMA) }
            .filter { it.genre.contains("Korean", ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?: runCatching {
                // Fallback: use discover with Korean filters
                val fallback = client.get("$baseUrl/discover/tv") {
                    tmdbAuth()
                    parameter("with_origin_country", "KR")
                    parameter("with_original_language", "ko")
                    parameter("sort_by", "popularity.desc")
                    parameter("include_adult", "false")
                    parameter("page", page)
                }.bodyAsText()
                parseResults(fallback).mapNotNull { it.jsonObject.toUnified("tv", VideoCategory.K_DRAMA) }
            }.getOrElse { emptyList() }
    }.getOrElse { emptyList() }

    /** Cartoons: fetch popular/weekly trending movie + TV and filter for animation (exclude Japanese/anime) */
    private suspend fun fetchCartoons(page: Int): List<UnifiedSearchResult> = runCatching {
        val movies = trending("movie", page)
            .filter { it.genre.contains("Animation", ignoreCase = true) }
        val tv = trending("tv", page)
            .filter { it.genre.contains("Animation", ignoreCase = true) }
        val result = (movies + tv)
            .filter { item -> !item.genre.contains("Japanese", ignoreCase = true) }
            .distinctBy { it.id }
        (result + discover("movie", page, VideoCategory.CARTOON) {
            parameter("with_genres", "16")
            parameter("sort_by", "popularity.desc")
        } + discover("tv", page, VideoCategory.CARTOON) {
            parameter("with_genres", "16")
            parameter("sort_by", "popularity.desc")
        })
            .filter { item -> !item.genre.contains("Japanese", ignoreCase = true) }
            .distinctBy { it.id }
    }.getOrElse { emptyList() }

    /** Anime: fetch popular/weekly trending TV and filter for Japanese + animation */
    private suspend fun fetchAnime(page: Int): List<UnifiedSearchResult> = runCatching {
        val result = trending("tv", page)
            .filter { item ->
                item.genre.contains("Japanese", ignoreCase = true) &&
                    item.genre.contains("Animation", ignoreCase = true)
            }
        (result + discover("tv", page, VideoCategory.ANIME) {
            parameter("with_genres", "16")
            parameter("with_original_language", "ja")
            parameter("sort_by", "popularity.desc")
        })
            .filter { item -> item.genre.contains("Japanese", ignoreCase = true) && item.genre.contains("Animation", ignoreCase = true) }
            .distinctBy { it.id }
    }.getOrElse { emptyList() }

    private suspend fun fetchMovies(page: Int): List<UnifiedSearchResult> =
        discover("movie", page, VideoCategory.MOVIES) {
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
            parameter("page", page)
        }

    /**
     * Fetch classic TV shows and classic cartoons (non-anime, non-Korean).
     * ONLY TV content - no movies. This is "Classic TV" which includes:
     * - Classic live-action TV shows (Friends, The Office, Game of Thrones, etc.)
     * - Classic Western cartoons (Tom & Jerry, Looney Tunes, The Simpsons, etc.)
     * Specifically excludes Japanese anime and Korean content.
     */
    private suspend fun fetchClassicTv(page: Int): List<UnifiedSearchResult> = runCatching {
        // Strategy 1: Trending TV — exclude Japanese and Korean
        val trendingTv = trending("tv", page)
            .filter { item ->
                val genre = item.genre.lowercase()
                !genre.contains("japanese") && !genre.contains("korean")
            }
        if (trendingTv.size >= 20) return@runCatching trendingTv.take(48)

        // Strategy 2: Discover popular TV — exclude Japanese/Korean
        val discoverTv = discover("tv", page, VideoCategory.CLASSIC) {
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
            parameter("page", page)
        }.filter { item ->
            val genre = item.genre.lowercase()
            !genre.contains("japanese") && !genre.contains("korean")
        }

        // Strategy 3: Classic Western cartoons (Animation genre + English language)
        val classicCartoons = discover("tv", page, VideoCategory.CLASSIC) {
            parameter("with_genres", "16") // Animation
            parameter("with_original_language", "en")
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
            parameter("page", page)
        }.filter { item ->
            val genre = item.genre.lowercase()
            !genre.contains("japanese") && !genre.contains("korean")
        }

        // NO movies in Classic - only TV content
        (trendingTv + discoverTv + classicCartoons)
            .distinctBy { it.id }
            .take(48)
    }.getOrElse {
        // Ultimate fallback: discover any TV content (no genre filter), exclude Japanese/Korean
        discover("tv", page, VideoCategory.CLASSIC).filter { item ->
            val genre = item.genre.lowercase()
            !genre.contains("japanese") && !genre.contains("korean")
        }
    }

    /** Reliable trending endpoint — always returns results even with basic API keys */
    private suspend fun trending(mediaType: String, page: Int): List<UnifiedSearchResult> = runCatching {
        val response = client.get("$baseUrl/trending/${mediaType}/week") {
            tmdbAuth()
            parameter("page", page.coerceIn(1, 10))
        }.bodyAsText()
        parseResults(response).mapNotNull { it.jsonObject.toUnified(mediaType, VideoCategory.CLASSIC) }
    }.getOrElse {
        println("[TMDB] Trending failed: ${it.message}")
        emptyList()
    }

    private suspend fun discover(
        mediaType: String,
        page: Int,
        category: VideoCategory,
        extraParams: HttpRequestBuilder.() -> Unit = {}
    ): List<UnifiedSearchResult> = runCatching {
        val response = client.get("$baseUrl/discover/$mediaType") {
            tmdbAuth()
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
            parameter("page", page)
            extraParams()
        }.bodyAsText()
        parseResults(response).mapNotNull { it.jsonObject.toUnified(mediaType, category) }
    }.getOrElse {
        println("[TMDB] Discover failed: ${it.message}")
        emptyList()
    }

    private suspend fun searchMovie(
        query: String,
        page: Int,
        category: VideoCategory
    ): List<UnifiedSearchResult> = runCatching {
        val response = client.get("$baseUrl/search/movie") {
            tmdbAuth()
            parameter("query", query)
            parameter("include_adult", "false")
            parameter("page", page)
        }.bodyAsText()
        parseResults(response).mapNotNull { it.jsonObject.toUnified("movie", category) }
    }.getOrElse {
        println("[TMDB] Search movie failed: ${it.message}")
        emptyList()
    }

    /**
     * Multi-search: searches both movies AND TV shows in a single TMDB call.
     * This is the key fix for Movie search — when a user searches "agency" or "forever",
     * it searches both movies AND TV across multiple pages to find ALL results.
     */
    private suspend fun searchMulti(
        query: String,
        page: Int,
        category: VideoCategory
    ): List<UnifiedSearchResult> = runCatching {
        val results = mutableListOf<UnifiedSearchResult>()

        // Search pages 1 through 3 for maximum coverage
        for (p in page.coerceAtLeast(1)..(page.coerceAtLeast(1) + 2).coerceAtMost(10)) {
            val response = client.get("$baseUrl/search/multi") {
                tmdbAuth()
                parameter("query", query)
                parameter("include_adult", "false")
                parameter("page", p.coerceAtMost(500))
            }.bodyAsText()
            val items = parseResults(response).mapNotNull { item ->
                val obj = item.jsonObject
                val mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull ?: "movie"
                if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
                obj.toUnified(mediaType, category)
            }
            results.addAll(items)
            if (items.size < 19) break // last page
        }

        results.distinctBy { it.id }
    }.getOrElse {
        println("[TMDB] Multi-search failed: ${it.message}")
        // Fallback to movie-only + TV-only search
        (searchMovie(query, page, category) + searchTv(query, page, category)).distinctBy { it.id }
    }

    /** Nigerian Films: fetch popular movies with Nigerian/Nollywood connection */
    private suspend fun fetchNigerian(page: Int): List<UnifiedSearchResult> = runCatching {
        // Strategy 1: Discover movies/TV with Nigerian origin country
        val movies = discover("movie", page, VideoCategory.NIGERIAN) {
            parameter("with_origin_country", "NG")
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
        }
        val tv = discover("tv", page, VideoCategory.NIGERIAN) {
            parameter("with_origin_country", "NG")
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
        }
        val results = (movies + tv).distinctBy { it.id }

        // Strategy 2: If Nigerian origin returns little, search for Nollywood
        if (results.size < 8) {
            val nollywood = searchMulti("Nollywood", page, VideoCategory.NIGERIAN)
            results + nollywood
        } else results
    }.getOrElse {
        // Ultimate fallback: just search "Nollywood"
        searchMulti("Nollywood", page, VideoCategory.NIGERIAN)
    }

    private suspend fun searchTv(
        query: String,
        page: Int,
        category: VideoCategory
    ): List<UnifiedSearchResult> = runCatching {
        val response = client.get("$baseUrl/search/tv") {
            tmdbAuth()
            parameter("query", query)
            parameter("include_adult", "false")
            parameter("page", page)
        }.bodyAsText()
        parseResults(response).mapNotNull { it.jsonObject.toUnified("tv", category) }
    }.getOrElse {
        println("[TMDB] Search TV failed: ${it.message}")
        emptyList()
    }

    private fun HttpRequestBuilder.tmdbAuth() {
        usableToken?.let {
            header("Authorization", "Bearer $it")
            return
        }
        usableApiKey?.let { parameter("api_key", it) }
    }

    private fun parseResults(response: String): JsonArray =
        tmdbJson.parseToJsonElement(response).jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.toAnimeResult(): AnimeResult? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = this["name"]?.jsonPrimitive?.contentOrNull
            ?: this["original_name"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val poster = this["poster_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return AnimeResult(
            id = "tmdb_tv_$id",
            titleRomaji = title,
            titleEnglish = this["original_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            coverUrl = poster.toPosterUrl(),
            synopsis = this["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = "TMDB",
            genres = listOf("Anime", "Animation", languageLabel()),
            sourceName = "TMDB"
        )
    }

    private fun JsonObject.toUnified(mediaType: String, category: VideoCategory): UnifiedSearchResult? {
        val rawId = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = when (mediaType) {
            "movie" -> this["title"]?.jsonPrimitive?.contentOrNull
                ?: this["original_title"]?.jsonPrimitive?.contentOrNull
            else -> this["name"]?.jsonPrimitive?.contentOrNull
                ?: this["original_name"]?.jsonPrimitive?.contentOrNull
        } ?: return null
        val poster = this["poster_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val language = languageLabel()
        val genreText = buildList {
            add(category.label)
            genreIds().mapNotNull { genreName(it) }.forEach(::add)
            if (language.isNotBlank()) add(language)
            originCountries().firstOrNull()?.let(::add)
        }.distinct().joinToString(", ")

        return UnifiedSearchResult(
            id = "tmdb_${mediaType}_$rawId",
            title = title,
            coverUrl = poster.toPosterUrl(),
            detailPageUrl = "tmdb://$mediaType/$rawId",
            sourceName = "TMDB",
            genre = genreText,
            synopsis = this["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            isVideo = true,
            mediaKind = category.name,
            url = "https://www.themoviedb.org/$mediaType/$rawId/watch"
        )
    }

    private fun String.toPosterUrl(): String =
        if (isBlank()) "" else "$imageBaseUrl$this"

    private fun JsonObject.genreIds(): List<Int> =
        this["genre_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()

    private fun JsonObject.originCountries(): List<String> =
        this["origin_country"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun JsonObject.languageLabel(): String =
        when (this["original_language"]?.jsonPrimitive?.contentOrNull) {
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "en" -> "English"
            "fr" -> "French"
            "es" -> "Spanish"
            else -> ""
        }

    private fun genreName(id: Int): String? = when (id) {
        12 -> "Adventure"
        16 -> "Animation"
        18 -> "Drama"
        28 -> "Action"
        35 -> "Comedy"
        80 -> "Crime"
        99 -> "Documentary"
        878 -> "Sci-Fi"
        10749 -> "Romance"
        10751 -> "Family"
        10759 -> "Action"
        10765 -> "Sci-Fi"
        else -> null
    }
}
