package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// ─────────────────────────────────────────────────────────────────────────────
//  AniList GraphQL API — 100% free, no API key required
//  Endpoint: https://graphql.anilist.co
// ─────────────────────────────────────────────────────────────────────────────
class AniListSource(private val client: HttpClient) {

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"

        // GraphQL query for currently airing seasonal anime
        private val AIRING_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(status: RELEASING, type: ANIME, sort: POPULARITY_DESC) {
                  id
                  title { romaji english }
                  coverImage { large }
                  description(asHtml: false)
                  episodes
                  genres
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()

        // GraphQL query for searching anime by title
        private val SEARCH_QUERY = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ANIME, sort: POPULARITY_DESC) {
                  id
                  title { romaji english }
                  coverImage { large }
                  description(asHtml: false)
                  episodes
                  genres
                  status
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()

        // GraphQL query for trending anime
        private val TRENDING_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: TRENDING_DESC) {
                  id
                  title { romaji english }
                  coverImage { large }
                  description(asHtml: false)
                  episodes
                  genres
                  status
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()
    }

    suspend fun fetchCurrentlyAiring(page: Int = 1, perPage: Int = 20): List<AnimeResult> {
        return runCatching {
            val body = buildJsonObject {
                put("query", AIRING_QUERY)
                putJsonObject("variables") {
                    put("page", page)
                    put("perPage", perPage)
                }
            }
            val response: String = client.post(ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()
            parseAnimeList(response)
        }.getOrElse { e ->
            println("[AniList] Error fetching airing: ${e.message}")
            emptyList()
        }
    }

    suspend fun search(query: String, page: Int = 1): List<AnimeResult> {
        return runCatching {
            val body = buildJsonObject {
                put("query", SEARCH_QUERY)
                putJsonObject("variables") {
                    put("search", query)
                    put("page", page)
                    put("perPage", 25)
                }
            }
            val response: String = client.post(ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()
            parseAnimeList(response)
        }.getOrElse { e ->
            println("[AniList] Search error for '$query': ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchTrending(page: Int = 1, perPage: Int = 20): List<AnimeResult> {
        return runCatching {
            val body = buildJsonObject {
                put("query", TRENDING_QUERY)
                putJsonObject("variables") {
                    put("page", page)
                    put("perPage", perPage)
                }
            }
            val response: String = client.post(ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()
            parseAnimeList(response)
        }.getOrElse { emptyList() }
    }

    private fun parseAnimeList(response: String): List<AnimeResult> {
        val root = json.parseToJsonElement(response).jsonObject
        val mediaArray = root["data"]
            ?.jsonObject?.get("Page")
            ?.jsonObject?.get("media")
            ?.jsonArray ?: return emptyList()

        return mediaArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val titleObj = obj["title"]?.jsonObject
                val nextEp = obj["nextAiringEpisode"]?.jsonObject
                val coverObj = obj["coverImage"]?.jsonObject
                val genres = obj["genres"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                AnimeResult(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null,
                    titleRomaji = titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull ?: "",
                    titleEnglish = titleObj?.get("english")?.jsonPrimitive?.contentOrNull ?: "",
                    coverUrl = coverObj?.get("large")?.jsonPrimitive?.contentOrNull ?: "",
                    synopsis = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("<[^>]*>"), "") // Strip HTML tags
                        ?: "",
                    episodeCount = obj["episodes"]?.jsonPrimitive?.intOrNull ?: 0,
                    nextEpisode = nextEp?.get("episode")?.jsonPrimitive?.intOrNull ?: 0,
                    nextAiringAt = nextEp?.get("airingAt")?.jsonPrimitive?.longOrNull ?: 0L,
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "RELEASING",
                    genres = genres,
                    sourceName = "AniList"
                )
            }.getOrNull()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  GogoAnime Scraper — Extracts HLS .m3u8 stream URLs
//  Acts as a regular browser, no API key required
// ─────────────────────────────────────────────────────────────────────────────
class GogoAnimeScraper(private val client: HttpClient) {

    companion object {
        private const val BASE_URL = "https://gogoanime3.co"
        private const val SEARCH_URL = "$BASE_URL/search.html"

        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to BASE_URL
        )
    }

    /**
     * Search GogoAnime for an anime title and return a list of episode pages.
     */
    suspend fun fetchEpisodes(animeTitleQuery: String, maxEpisodes: Int = 24): List<AnimeEpisode> {
        return runCatching {
            // 1. Search for the anime series page
            val searchHtml: String = client.get(SEARCH_URL) {
                parameter("keyword", animeTitleQuery)
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            // 2. Extract the series slug from first result
            val slugRegex = Regex("""href="/category/([^"]+)"""")
            val slug = slugRegex.find(searchHtml)?.groupValues?.get(1) ?: return@runCatching emptyList()

            // 3. Fetch series page to find episode range
            val seriesHtml: String = client.get("$BASE_URL/category/$slug") {
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            // Extract episode count
            val epEndRegex = Regex("""id="episode_page".*?<a.*?ep_end\s*=\s*"(\d+)"""", RegexOption.DOT_MATCHES_ALL)
            val totalEpisodes = epEndRegex.find(seriesHtml)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // 4. Build episode list (limit to last N episodes to avoid massive lists)
            val start = maxOf(1, totalEpisodes - maxEpisodes + 1)
            (start..totalEpisodes).map { ep ->
                AnimeEpisode(
                    episodeNumber = ep,
                    title = "Episode $ep",
                    url = "$BASE_URL/$slug-episode-$ep",
                    thumbnail = ""
                )
            }.reversed() // Newest first
        }.getOrElse { e ->
            println("[GogoAnime] Error fetching episodes for '$animeTitleQuery': ${e.message}")
            emptyList()
        }
    }

    /**
     * Scrapes a GogoAnime episode page and extracts the .m3u8 HLS stream URL.
     */
    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        return runCatching {
            val html: String = client.get(episodePageUrl) {
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            // Strategy 1: Look for a direct m3u8 link
            val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
            val directM3u8 = m3u8Regex.find(html)?.value
            if (directM3u8 != null) return@runCatching directM3u8

            // Strategy 2: Find the embedded iframe server URL
            val iframeRegex = Regex("""<iframe[^>]+src="([^"]+)"""")
            val iframeUrl = iframeRegex.find(html)?.groupValues?.get(1)
            if (iframeUrl != null) {
                val iframeHtml: String = client.get(iframeUrl) {
                    BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
                    header("Referer", episodePageUrl)
                }.body()
                return@runCatching m3u8Regex.find(iframeHtml)?.value
            }

            null
        }.getOrElse { e ->
            println("[GogoAnime] Stream extraction error: ${e.message}")
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Hianime Scraper — Fallback anime stream source
// ─────────────────────────────────────────────────────────────────────────────
class HianimeScraper(private val client: HttpClient) {

    companion object {
        private const val BASE_URL = "https://hianime.to"
        private const val SEARCH_URL = "$BASE_URL/search"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to BASE_URL
        )
    }

    suspend fun fetchEpisodes(animeTitleQuery: String): List<AnimeEpisode> {
        return runCatching {
            val searchHtml: String = client.get(SEARCH_URL) {
                parameter("keyword", animeTitleQuery)
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            val slugRegex = Regex("""href="/([^"?]+)\?[^"]*"""")
            val slug = slugRegex.find(searchHtml)?.groupValues?.get(1) ?: return@runCatching emptyList()

            val seriesHtml: String = client.get("$BASE_URL/$slug") {
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            val epCountRegex = Regex("""class="tick-eps">(\d+)<""")
            val total = epCountRegex.find(seriesHtml)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            (1..total).map { ep ->
                AnimeEpisode(
                    episodeNumber = ep,
                    title = "Episode $ep",
                    url = "$BASE_URL/watch/$slug?ep=$ep"
                )
            }.reversed()
        }.getOrElse { emptyList() }
    }

    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        return runCatching {
            val html: String = client.get(episodePageUrl) {
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()
            val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
            m3u8Regex.find(html)?.value
        }.getOrNull()
    }
}
