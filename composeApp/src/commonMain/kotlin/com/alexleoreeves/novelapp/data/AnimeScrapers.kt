package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// Domain resolution for Anineko is handled by the shared resolveLiveDomain()
// function in DomainResolver.kt (DomainCache TTL = 6 h, DDG HTML fallback).


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
//  Anineko scraper — extracts HLS .m3u8 stream URLs
//  Acts as a regular browser, no API key required.
//  BASE_URL is resolved lazily via DuckDuckGo so a domain shift auto-heals.
// ─────────────────────────────────────────────────────────────────────────────
class AninekoScraper(private val client: HttpClient) {

    companion object {
        // Hardcoded fallback; the live domain is resolved at runtime via DDG.
        private const val FALLBACK_URL = "https://anineko.to"
        private const val BRAND_QUERY = "anineko anime official"

        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to FALLBACK_URL
        )
    }

    /** Resolves (and caches) the live Anineko domain before making any request. */
    private suspend fun liveBaseUrl(): String =
        resolveLiveDomain(client, BRAND_QUERY, FALLBACK_URL)

    /**
     * Search Anineko for an anime title and return a list of episode pages.
     * The live domain is resolved via DDG before any network call.
     */
    suspend fun fetchEpisodes(animeTitleQuery: String, maxEpisodes: Int = 24): List<AnimeEpisode> {
        return runCatching {
            val BASE_URL = liveBaseUrl()
            val searchUrl = "$BASE_URL/search.html"

            // 1. Search for the anime series page
            val searchHtml: String = client.get(searchUrl) {
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
            val epEndRegex = Regex("""id="episode_page"[\s\S]*?<a[\s\S]*?ep_end\s*=\s*"(\d+)"""")
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
            println("[Anineko] Error fetching episodes for '$animeTitleQuery': ${e.message}")
            emptyList()
        }
    }

    /**
     * Scrapes an Anineko episode page and extracts the .m3u8 HLS stream URL.
     * The live domain is resolved via DDG before any network call.
     */
    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        return runCatching {
            // Rewrite the episode URL to the live domain in case the stored URL is stale
            val liveBase = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(episodePageUrl, liveBase)

            val html: String = client.get(effectiveUrl) {
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
                val absoluteIframeUrl = when {
                    iframeUrl.startsWith("//") -> "https:$iframeUrl"
                    iframeUrl.startsWith("/") -> "$liveBase$iframeUrl"
                    else -> iframeUrl
                }
                val iframeHtml: String = client.get(absoluteIframeUrl) {
                    BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
                    header("Referer", effectiveUrl)
                }.body()
                return@runCatching m3u8Regex.find(iframeHtml)?.value
            }

            null
        }.getOrElse { e ->
            println("[Anineko] Stream extraction error: ${e.message}")
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AnimePahe Scraper — Fallback anime stream source
// ─────────────────────────────────────────────────────────────────────────────
class AnimePaheScraper(private val client: HttpClient) {

    companion object {
        private const val BASE_URL = "https://animepahe.pw"
        private const val API_URL = "$BASE_URL/api"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to BASE_URL
        )
    }

    suspend fun fetchEpisodes(animeTitleQuery: String): List<AnimeEpisode> {
        return runCatching {
            val searchResponse: String = client.get(API_URL) {
                parameter("m", "search")
                parameter("q", animeTitleQuery)
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            val searchJson = json.parseToJsonElement(searchResponse).jsonObject
            val first = searchJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@runCatching emptyList()
            val session = first["session"]?.jsonPrimitive?.contentOrNull
                ?: first["id"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching emptyList()

            val releaseResponse: String = client.get(API_URL) {
                parameter("m", "release")
                parameter("id", session)
                parameter("sort", "episode_asc")
                parameter("page", 1)
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()

            val releaseJson = json.parseToJsonElement(releaseResponse).jsonObject
            val data = releaseJson["data"]?.jsonArray ?: return@runCatching emptyList()
            data.mapIndexed { index, item ->
                val obj = item.jsonObject
                val episodeNumber = obj["episode"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: (index + 1)
                val episodeSession = obj["session"]?.jsonPrimitive?.contentOrNull.orEmpty()
                AnimeEpisode(
                    episodeNumber = episodeNumber,
                    title = "Episode $episodeNumber",
                    url = "$BASE_URL/play/$session/$episodeSession"
                )
            }.reversed()
        }.getOrElse { e ->
            println("[AnimePahe] Episode fetch failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        return runCatching {
            val html: String = client.get(episodePageUrl) {
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()
            val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
            val mp4Regex = Regex("""https?://[^\s"']+\.mp4[^\s"']*""")
            m3u8Regex.find(html)?.value
                ?: mp4Regex.find(html)?.value
                ?: Regex("""https?://kwik\.[^\s"']+""").find(html)?.value
        }.getOrNull()
    }
}
