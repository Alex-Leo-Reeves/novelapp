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

    private val usableToken = readAccessToken.trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("mock_") }
    private val usableApiKey = apiKey.trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("mock_") }

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
            VideoCategory.K_DRAMA -> fetchKDrama(page)
            VideoCategory.CARTOON -> fetchCartoons(page)
            VideoCategory.MOVIES -> fetchMovies(page)
        }

    suspend fun searchVideo(category: VideoCategory, query: String, page: Int = 1): List<UnifiedSearchResult> =
        when (category) {
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
            VideoCategory.MOVIES -> searchMovie(query, page, category)
        }

    private suspend fun fetchKDrama(page: Int): List<UnifiedSearchResult> = runCatching {
        val response = client.get("$baseUrl/discover/tv") {
            tmdbAuth()
            parameter("with_origin_country", "KR")
            parameter("with_original_language", "ko")
            parameter("sort_by", "popularity.desc")
            parameter("include_adult", "false")
            parameter("page", page)
        }.bodyAsText()
        parseResults(response).mapNotNull { it.jsonObject.toUnified("tv", VideoCategory.K_DRAMA) }
    }.getOrElse { emptyList() }

    private suspend fun fetchCartoons(page: Int): List<UnifiedSearchResult> {
        val movies = discover("movie", page, VideoCategory.CARTOON) {
            parameter("with_genres", "16")
            parameter("without_original_language", "ja")
        }
        val tv = discover("tv", page, VideoCategory.CARTOON) {
            parameter("with_genres", "16")
            parameter("without_original_language", "ja")
        }
        return (movies + tv).distinctBy { it.id }
    }

    private suspend fun fetchMovies(page: Int): List<UnifiedSearchResult> =
        discover("movie", page, VideoCategory.MOVIES)

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
    }.getOrElse { emptyList() }

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
    }.getOrElse { emptyList() }

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
    }.getOrElse { emptyList() }

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
            mediaKind = category.name
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
