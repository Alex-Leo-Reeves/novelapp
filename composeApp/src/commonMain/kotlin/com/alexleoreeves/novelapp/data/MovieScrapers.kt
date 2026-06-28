package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import com.fleeksoft.ksoup.Ksoup

// ─────────────────────────────────────────────────────────────────────────────
//  Unified Media Results for K-Drama, Cartoons, and Movies
// ─────────────────────────────────────────────────────────────────────────────
data class MediaResult(
    val id: String,
    val title: String,
    val coverUrl: String,
    val description: String,
    val genres: String,
    val type: String, // KDRAMA, CARTOON, MOVIE, TVSHOW
    val rating: String = "",
    val releaseDate: String = ""
)

data class MediaEpisode(
    val title: String,
    val url: String,
    val episodeNumber: Int
)

// ─────────────────────────────────────────────────────────────────────────────
//  DramaCool Scraper
// ─────────────────────────────────────────────────────────────────────────────
class DramaCoolScraper(private val httpClient: HttpClient) {
    private val baseUrl = "https://dramacool.bg"

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val html = httpClient.get("$baseUrl/search?keyword=${query.replace(" ", "+")}").bodyAsText()
            val doc = Ksoup.parse(html)
            doc.select("ul.list-episode-item li").map { el ->
                val link = el.select("a").firstOrNull()
                val detailPath = link?.attr("href") ?: ""
                val title = el.select("h3.title").text()
                val cover = el.select("img").attr("data-original").ifEmpty { el.select("img").attr("src") }
                MediaResult(
                    id = if (detailPath.startsWith("http")) detailPath else "$baseUrl$detailPath",
                    title = title,
                    coverUrl = cover,
                    description = "Popular Asian Drama",
                    genres = "Drama, Romance",
                    type = "KDRAMA"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchEpisodes(detailUrl: String): List<MediaEpisode> {
        return try {
            val html = httpClient.get(detailUrl).bodyAsText()
            val doc = Ksoup.parse(html)
            doc.select("ul.list-episode-item li a").mapIndexed { index, el ->
                val epUrl = el.attr("href")
                val title = el.select("span.type").text() + " " + el.select("h3.title").text()
                MediaEpisode(
                    title = title.trim().ifEmpty { "Episode ${index + 1}" },
                    url = if (epUrl.startsWith("http")) epUrl else "$baseUrl$epUrl",
                    episodeNumber = index + 1
                )
            }.reversed() // Ascending order (1 to N)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun extractStreamUrl(episodeUrl: String): String? {
        return try {
            val html = httpClient.get(episodeUrl).bodyAsText()
            val doc = Ksoup.parse(html)
            val iframeSrc = doc.select("iframe[src*=/embed/]").attr("src")
                .ifEmpty { doc.select("iframe").attr("src") }
            if (iframeSrc.isNotEmpty()) {
                if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            } else {
                episodeUrl
            }
        } catch (e: Exception) {
            episodeUrl
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  KimCartoon Scraper
// ─────────────────────────────────────────────────────────────────────────────
class KimCartoonScraper(private val httpClient: HttpClient) {
    private val baseUrl = "https://kimcartoon.li"

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val html = httpClient.get("$baseUrl/Search/Cartoon?keyword=${query.replace(" ", "+")}").bodyAsText()
            val doc = Ksoup.parse(html)
            doc.select("div.list-cartoon div.item").map { el ->
                val link = el.select("a").firstOrNull()
                val detailPath = link?.attr("href") ?: ""
                val title = el.select("span.title").text().ifEmpty { link?.text() ?: "" }
                val cover = el.select("img").attr("src")
                MediaResult(
                    id = if (detailPath.startsWith("http")) detailPath else "$baseUrl$detailPath",
                    title = title,
                    coverUrl = if (cover.startsWith("//")) "https:$cover" else cover,
                    description = "Western Animation & Cartoon series",
                    genres = "Animation, Cartoon",
                    type = "CARTOON"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchEpisodes(detailUrl: String): List<MediaEpisode> {
        return try {
            val html = httpClient.get(detailUrl).bodyAsText()
            val doc = Ksoup.parse(html)
            doc.select("table.list h3 a").mapIndexed { index, el ->
                val epUrl = el.attr("href")
                MediaEpisode(
                    title = el.text().trim(),
                    url = if (epUrl.startsWith("http")) epUrl else "$baseUrl$epUrl",
                    episodeNumber = index + 1
                )
            }.reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun extractStreamUrl(episodeUrl: String): String? {
        return try {
            val html = httpClient.get(episodeUrl).bodyAsText()
            val doc = Ksoup.parse(html)
            val playerIframe = doc.select("iframe#myContent, iframe[src*=embed]").attr("src")
            if (playerIframe.isNotEmpty()) {
                if (playerIframe.startsWith("//")) "https:$playerIframe" else playerIframe
            } else {
                episodeUrl
            }
        } catch (e: Exception) {
            episodeUrl
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TMDB Metadata & Movie Scraper
// ─────────────────────────────────────────────────────────────────────────────
class TMDBMovieScraper(private val httpClient: HttpClient) {
    private val apiKey = "15d2ea6d0dc1d247f33e5405d4b507cc" // Public keyless key
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val imageBaseUrl = "https://image.tmdb.org/t/p/w500"

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val url = "$tmdbBaseUrl/search/multi?api_key=$apiKey&query=${query.encodeURL()}"
            val response = httpClient.get(url).bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { el ->
                val obj = el.jsonObject
                val mediaType = obj["media_type"]?.jsonPrimitive?.content ?: "movie"
                if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null

                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content ?: ""
                val desc = obj["overview"]?.jsonPrimitive?.content ?: ""
                val poster = obj["poster_path"]?.jsonPrimitive?.contentOrNull
                val cover = if (poster != null) "$imageBaseUrl$poster" else ""
                val date = obj["release_date"]?.jsonPrimitive?.content
                    ?: obj["first_air_date"]?.jsonPrimitive?.content ?: ""

                MediaResult(
                    id = id,
                    title = title,
                    coverUrl = cover,
                    description = desc,
                    genres = if (mediaType == "movie") "Movie" else "TV Show",
                    type = if (mediaType == "movie") "MOVIE" else "TVSHOW",
                    releaseDate = date
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchTrending(type: String = "movie"): List<MediaResult> {
        return try {
            val url = "$tmdbBaseUrl/trending/$type/week?api_key=$apiKey"
            val response = httpClient.get(url).bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content ?: ""
                val desc = obj["overview"]?.jsonPrimitive?.content ?: ""
                val poster = obj["poster_path"]?.jsonPrimitive?.contentOrNull
                val cover = if (poster != null) "$imageBaseUrl$poster" else ""
                val date = obj["release_date"]?.jsonPrimitive?.content
                    ?: obj["first_air_date"]?.jsonPrimitive?.content ?: ""

                MediaResult(
                    id = id,
                    title = title,
                    coverUrl = cover,
                    description = desc,
                    genres = if (type == "movie") "Movie" else "TV Show",
                    type = if (type == "movie") "MOVIE" else "TVSHOW",
                    releaseDate = date
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchTVSeasonsAndEpisodes(tvId: String): List<MediaEpisode> {
        return try {
            val url = "$tmdbBaseUrl/tv/$tvId?api_key=$apiKey"
            val response = httpClient.get(url).bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            val seasons = root["seasons"]?.jsonArray ?: return emptyList()

            val episodes = mutableListOf<MediaEpisode>()
            seasons.forEach { el ->
                val seasonObj = el.jsonObject
                val seasonNum = seasonObj["season_number"]?.jsonPrimitive?.int ?: 0
                val epCount = seasonObj["episode_count"]?.jsonPrimitive?.int ?: 0
                if (seasonNum > 0) {
                    for (ep in 1..epCount) {
                        episodes.add(
                            MediaEpisode(
                                title = "Season $seasonNum - Episode $ep",
                                url = "tv:$tvId:$seasonNum:$ep",
                                episodeNumber = ep
                            )
                        )
                    }
                }
            }
            episodes
        } catch (e: Exception) {
            // Fallback list of 12 episodes if call fails
            (1..12).map { ep ->
                MediaEpisode(title = "Episode $ep", url = "tv:$tvId:1:$ep", episodeNumber = ep)
            }
        }
    }

    private fun String.encodeURL(): String = this.replace(" ", "%20")
}
