package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import com.fleeksoft.ksoup.Ksoup
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
            val baseUrl = liveBaseUrl()
            val directSeriesUrl = toAbsoluteAnimeUrl(baseUrl, "/watch/${animeTitleQuery.slugifyAnimeTitle()}")
            fetchEpisodesFromSeriesUrl(baseUrl, directSeriesUrl, maxEpisodes)
                .takeIf { it.isNotEmpty() }
                ?.let { return@runCatching it }

            val searchHtml: String = client.get("$baseUrl/browser") {
                parameter("keyword", animeTitleQuery)
                BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            }.body()
            if (searchHtml.isBlockedOrErrorPage()) return@runCatching emptyList()

            val searchDoc = Ksoup.parse(searchHtml)
            val searchCandidates = searchDoc.select("article.nv-anime-card a[href*=/watch/], a.nv-anime-thumb[href*=/watch/], h3.nv-anime-title a[href*=/watch/]")
                .mapNotNull { link ->
                    val href = link.attr("href")
                    val title = link.attr("title")
                        .ifBlank { link.select("img").firstOrNull()?.attr("alt").orEmpty() }
                        .ifBlank { link.text() }
                        .decodeHtmlEntitiesLite()
                    if (href.isBlank() || title.isBlank()) null else title to href
                }
                .distinctBy { it.second }
                .sortedWith(
                    compareByDescending<Pair<String, String>> { animeTitleMatchScore(animeTitleQuery, it.first) }
                        .thenBy { it.first.length }
                )
                .map { toAbsoluteAnimeUrl(baseUrl, it.second) }

            for (seriesUrl in searchCandidates) {
                val episodes = fetchEpisodesFromSeriesUrl(baseUrl, seriesUrl, maxEpisodes)
                if (episodes.isNotEmpty()) {
                    return@runCatching episodes
                }
            }

            emptyList()
        }.getOrElse { e ->
            println("[Anineko] Error fetching episodes for '$animeTitleQuery': ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchEpisodesFromSeriesUrl(
        baseUrl: String,
        seriesUrl: String,
        maxEpisodes: Int
    ): List<AnimeEpisode> {
        val seriesHtml: String = client.get(seriesUrl) {
            BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
        }.body()
        if (seriesHtml.isBlockedOrErrorPage()) return emptyList()

        val seriesDoc = Ksoup.parse(seriesHtml)
        return seriesDoc.select("a.nv-info-episode-main[href*=/ep-], a.nv-episode-item[href*=/ep-], a[href*=/watch/][href*=/ep-]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val title = link.select("strong").firstOrNull()?.text()
                    ?.ifBlank { link.text() }
                    ?.decodeHtmlEntitiesLite()
                    ?: link.text().decodeHtmlEntitiesLite()
                val episodeNumber = Regex("""(?:Episode\s*|/ep-)(\d+)""", RegexOption.IGNORE_CASE)
                    .find("$title $href")
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@mapNotNull null
                AnimeEpisode(
                    episodeNumber = episodeNumber,
                    title = title.ifBlank { "Episode $episodeNumber" },
                    url = toAbsoluteAnimeUrl(baseUrl, href),
                    thumbnail = ""
                )
            }
            .distinctBy { it.url }
            .sortedByDescending { it.episodeNumber }
            .take(maxEpisodes)
    }

    fun fallbackEpisodes(animeTitleQuery: String, episodeCount: Int, maxEpisodes: Int = 24): List<AnimeEpisode> {
        if (episodeCount <= 0) return emptyList()
        val baseUrl = FALLBACK_URL
        val slug = animeTitleQuery.slugifyAnimeTitle()
        val start = maxOf(1, episodeCount - maxEpisodes + 1)
        return (start..episodeCount).map { ep ->
                AnimeEpisode(
                    episodeNumber = ep,
                    title = "Episode $ep",
                    url = "$baseUrl/watch/$slug/ep-$ep",
                    thumbnail = ""
                )
            }.reversed()
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
            if (html.isBlockedOrErrorPage()) return@runCatching null

            extractDirectMediaUrl(html)?.let { return@runCatching it }

            val providerUrls = extractProviderUrls(html, liveBase)
            providerUrls.forEach { providerUrl ->
                val stream = extractProviderStream(providerUrl, effectiveUrl)
                if (stream != null) return@runCatching stream
            }
            providerUrls.firstOrNull()?.let { return@runCatching it }

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
                return@runCatching extractDirectMediaUrl(iframeHtml)
            }

            null
        }.getOrElse { e ->
            println("[Anineko] Stream extraction error: ${e.message}")
            null
        }
    }

    private suspend fun extractProviderStream(providerUrl: String, referer: String): String? = runCatching {
        if (providerUrl.isDirectAnimeMediaUrl()) return@runCatching providerUrl
        val html: String = client.get(providerUrl) {
            BROWSER_HEADERS.forEach { (k, v) -> header(k, v) }
            header("Referer", referer)
        }.body()
        if (html.isBlockedOrErrorPage()) return@runCatching null
        extractDirectMediaUrl(html)
            ?: Regex("""(?:const|var|let)\s+(?:src|file|url)\s*=\s*["']([^"']+)["']""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.decodeHtmlEntitiesLite()
    }.getOrNull()

    private fun extractDirectMediaUrl(html: String): String? {
        val cleaned = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .decodeHtmlEntitiesLite()
        val directPatterns = listOf(
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*"""),
            Regex("""["'](?:file|src|url)["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|url)\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        return directPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(cleaned)?.let { match ->
                match.groupValues.getOrNull(1).takeUnless { it.isNullOrBlank() } ?: match.value.trim('"', '\'')
            }
        }?.decodeHtmlEntitiesLite()
    }

    private fun extractProviderUrls(html: String, baseUrl: String): List<String> {
        val cleaned = html.replace("\\/", "/").decodeHtmlEntitiesLite()
        val patterns = listOf(
            Regex("""data-video\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""data-src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:embed|player|video|src)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        return patterns
            .flatMap { pattern -> pattern.findAll(cleaned).map { it.groupValues[1] }.toList() }
            .mapNotNull { normalizeProviderUrl(it, baseUrl) }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
    }

    private fun normalizeProviderUrl(rawUrl: String, baseUrl: String): String? {
        val cleaned = rawUrl.trim().decodeHtmlEntitiesLite()
        if (cleaned.isBlank() || cleaned.startsWith("javascript:", ignoreCase = true)) return null
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> baseUrl.trimEnd('/') + cleaned
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            else -> null
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
            if (searchResponse.isBlockedOrErrorPage()) return@runCatching emptyList()

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
            if (releaseResponse.isBlockedOrErrorPage()) return@runCatching emptyList()

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
            if (html.isBlockedOrErrorPage()) return@runCatching null
            val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
            val mp4Regex = Regex("""https?://[^\s"']+\.mp4[^\s"']*""")
            m3u8Regex.find(html)?.value
                ?: mp4Regex.find(html)?.value
                ?: Regex("""https?://kwik\.[^\s"']+""").find(html)?.value
        }.getOrNull()
    }
}

private fun toAbsoluteAnimeUrl(baseUrl: String, href: String): String {
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    return baseUrl.trimEnd('/') + "/" + href.trimStart('/')
}

private fun animeTitleMatchScore(query: String, title: String): Int {
    val q = query.normalizedAnimeTitle()
    val t = title.normalizedAnimeTitle()
    if (q.isBlank() || t.isBlank()) return 0

    val sequelPenalty = when {
        Regex("""\b(movie|special|ova|ona|recap|summary|theatrical|part\s+\d+|cour\s+\d+)\b""").containsMatchIn(t) -> 900
        Regex("""\b(season\s+[2-9]|\d+(st|nd|rd|th)\s+season|s[2-9])\b""").containsMatchIn(t) -> 800
        Regex("""\b(culling game|hidden inventory|shibuya incident|final season)\b""").containsMatchIn(t) -> 700
        else -> 0
    }
    val baseBoost = when {
        t == q -> 2_000
        t == "$q tv" || t == "$q the animation" -> 1_500
        t.removeSuffix(" tv") == q -> 1_300
        Regex("""\b(tv|the animation)\b""").containsMatchIn(t) && t.startsWith(q) -> 700
        else -> 0
    }

    return when {
        t == q -> 10_000 + baseBoost
        t.removeSuffix(" tv") == q -> 9_500 + baseBoost
        t.startsWith("$q season 1") -> 9_000 + baseBoost
        t.startsWith("$q ") -> 8_000 + baseBoost - sequelPenalty
        t.contains(q) -> 5_000 + baseBoost - sequelPenalty
        else -> 0
    }
}

private fun String.normalizedAnimeTitle(): String =
    lowercase()
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun String.slugifyAnimeTitle(): String =
    lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

private fun String.isDirectAnimeMediaUrl(): Boolean {
    val clean = substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mpd") || clean.endsWith(".webm")
}
