package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
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

        private val RELATION_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                title { romaji english }
                coverImage { large }
                description(asHtml: false)
                episodes
                genres
                status
                relations {
                  edges {
                    relationType
                    node {
                      id
                      type
                      format
                      title { romaji english }
                      coverImage { large }
                      description(asHtml: false)
                      episodes
                      genres
                      status
                    }
                  }
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

    suspend fun fetchSeasonChain(startId: String, maxDepth: Int = 12): List<AnimeResult> {
        val start = startId.toIntOrNull() ?: return emptyList()
        val seen = mutableSetOf<Int>()
        val center = fetchRelationNode(start) ?: return emptyList()
        val prequels = mutableListOf<AnimeResult>()
        val sequels = mutableListOf<AnimeResult>()

        seen += center.media.id.toIntOrNull() ?: start
        var cursor = center
        repeat(maxDepth) {
            val previousId = cursor.prequelId?.takeUnless { it in seen } ?: return@repeat
            val previous = fetchRelationNode(previousId) ?: return@repeat
            seen += previousId
            prequels += previous.media
            cursor = previous
        }

        cursor = center
        repeat(maxDepth) {
            val nextId = cursor.sequelId?.takeUnless { it in seen } ?: return@repeat
            val next = fetchRelationNode(nextId) ?: return@repeat
            seen += nextId
            sequels += next.media
            cursor = next
        }

        return (prequels.asReversed() + center.media + sequels)
            .distinctBy { it.id }
    }

    private suspend fun fetchRelationNode(id: Int): AniListRelationNode? = runCatching {
        val body = buildJsonObject {
            put("query", RELATION_QUERY)
            putJsonObject("variables") { put("id", id) }
        }
        val response: String = client.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.body()
        val media = json.parseToJsonElement(response)
            .jsonObject["data"]
            ?.jsonObject?.get("Media")
            ?.jsonObject
            ?: return@runCatching null
        val anime = media.toAnimeResult() ?: return@runCatching null
        val edges = media["relations"]
            ?.jsonObject?.get("edges")
            ?.jsonArray
            .orEmpty()
            .mapNotNull { it.jsonObject }
        val prequelId = edges.firstRelatedAnimeId("PREQUEL")
        val sequelId = edges.firstRelatedAnimeId("SEQUEL")
        AniListRelationNode(anime, prequelId, sequelId)
    }.getOrNull()

    private fun parseAnimeList(response: String): List<AnimeResult> {
        val root = json.parseToJsonElement(response).jsonObject
        val mediaArray = root["data"]
            ?.jsonObject?.get("Page")
            ?.jsonObject?.get("media")
            ?.jsonArray ?: return emptyList()

        return mediaArray.mapNotNull { element ->
            runCatching { element.jsonObject.toAnimeResult() }.getOrNull()
        }
    }

    private fun JsonObject.toAnimeResult(): AnimeResult? {
        val titleObj = this["title"]?.jsonObject
        val nextEp = this["nextAiringEpisode"]?.jsonObject
        val coverObj = this["coverImage"]?.jsonObject
        val genres = this["genres"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        return AnimeResult(
            id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            titleRomaji = titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull ?: "",
            titleEnglish = titleObj?.get("english")?.jsonPrimitive?.contentOrNull ?: "",
            coverUrl = coverObj?.get("large")?.jsonPrimitive?.contentOrNull ?: "",
            synopsis = this["description"]?.jsonPrimitive?.contentOrNull
                ?.replace(Regex("<[^>]*>"), "")
                ?: "",
            episodeCount = this["episodes"]?.jsonPrimitive?.intOrNull ?: 0,
            nextEpisode = nextEp?.get("episode")?.jsonPrimitive?.intOrNull ?: 0,
            nextAiringAt = nextEp?.get("airingAt")?.jsonPrimitive?.longOrNull ?: 0L,
            status = this["status"]?.jsonPrimitive?.contentOrNull ?: "RELEASING",
            genres = genres,
            sourceName = "AniList"
        )
    }

    private fun List<JsonObject>.firstRelatedAnimeId(relationType: String): Int? =
        firstNotNullOfOrNull { edge ->
            if (edge["relationType"]?.jsonPrimitive?.contentOrNull != relationType) return@firstNotNullOfOrNull null
            val node = edge["node"]?.jsonObject ?: return@firstNotNullOfOrNull null
            val type = node["type"]?.jsonPrimitive?.contentOrNull
            val format = node["format"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type != "ANIME" || format == "MUSIC") return@firstNotNullOfOrNull null
            node["id"]?.jsonPrimitive?.intOrNull
        }

    private data class AniListRelationNode(
        val media: AnimeResult,
        val prequelId: Int?,
        val sequelId: Int?
    )
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
    suspend fun fetchEpisodes(animeTitleQuery: String, maxEpisodes: Int = 300): List<AnimeEpisode> {
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
            .let { episodes ->
                if (maxEpisodes > 0) episodes.take(maxEpisodes) else episodes
            }
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
     * Directly fetch episodes for a known-good Anineko slug (e.g. "my-hero-academia").
     * Skips the DDG search, goes straight to the series page.
     */
    suspend fun fetchEpisodesBySlug(slug: String, maxEpisodes: Int = 300): List<AnimeEpisode> {
        return runCatching {
            val baseUrl = liveBaseUrl()
            val seriesUrl = "$baseUrl/watch/$slug"
            fetchEpisodesFromSeriesUrl(baseUrl, seriesUrl, maxEpisodes)
        }.getOrElse { e ->
            println("[Anineko] fetchEpisodesBySlug error for '$slug': ${e.message}")
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
            if (html.isBlockedOrErrorPage()) return@runCatching null

            extractDirectMediaUrl(html)?.let { return@runCatching it }

            val providerUrls = extractProviderUrls(html, liveBase)
            providerUrls.forEach { providerUrl ->
                val stream = extractProviderStream(providerUrl, effectiveUrl)
                if (stream != null) return@runCatching stream
            }

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
        providerUrl.toKnownAninekoDirectStream()?.let { return@runCatching it }
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
            .filterNot { it.isNonPlayableAnimeProviderUrl() }
            .sortedBy { it.animeProviderPriority() }
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

private fun String.toKnownAninekoDirectStream(): String? {
    val clean = substringBefore("?").substringBefore("#").trim()
    val host = runCatching { Url(clean).host.lowercase() }.getOrNull().orEmpty()
    val token = clean.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
    return when {
        "vivibebe.site" in host && "/public/stream/" !in clean ->
            "https://vivibebe.site/public/stream/$token/master.m3u8"
        else -> null
    }
}

private fun String.animeProviderPriority(): Int {
    val host = runCatching { Url(this).host.lowercase() }.getOrNull().orEmpty()
    return when {
        "vivibebe" in host -> 0
        "bibiemb" in host || "vibevibe" in host -> 1
        "otakuhg" in host -> 2
        "otakuvid" in host -> 3
        "playmogo" in host -> 4
        else -> 9
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AnimePahe Scraper — Fallback anime stream source
// ─────────────────────────────────────────────────────────────────────────────
class AnimePaheScraper(private val client: HttpClient) {

    companion object {
        private val BASE_URL_CANDIDATES = listOf(
            "https://animepahe.ch",
            "https://animepahe.ng",
            "https://animepahe.com",
            "https://animepahe.pw",
            "https://animepahe.org"
        )
        private val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/json",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }

    private var cachedBaseUrl: String? = null

    suspend fun fetchEpisodes(animeTitleQuery: String): List<AnimeEpisode> {
        for (baseUrl in candidateBaseUrls()) {
            val episodes = runCatching {
                fetchEpisodesFromBase(baseUrl, animeTitleQuery)
            }.getOrElse { e ->
                println("[AnimePahe] Episode fetch failed on $baseUrl: ${e.message}")
                emptyList()
            }
            if (episodes.isNotEmpty()) {
                cachedBaseUrl = baseUrl
                return episodes
            }
        }
        return emptyList()
    }

    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        for (baseUrl in candidateBaseUrls(episodePageUrl.originOrNull())) {
            val stream = runCatching {
                val effectiveUrl = rewriteUrlOrigin(episodePageUrl, baseUrl)
                val html: String = client.get(effectiveUrl) {
                    browserHeaders(baseUrl).forEach { (k, v) -> header(k, v) }
                }.body()
                if (html.isBlockedOrErrorPage()) return@runCatching null
                extractDirectAnimePaheMediaUrl(html)
            }.getOrNull()
            if (stream != null) {
                cachedBaseUrl = baseUrl
                return stream
            }
        }
        return null
    }

    private suspend fun fetchEpisodesFromBase(baseUrl: String, animeTitleQuery: String): List<AnimeEpisode> {
        val apiUrl = "$baseUrl/api"
        val searchResponse: String = client.get(apiUrl) {
            parameter("m", "search")
            parameter("q", animeTitleQuery)
            browserHeaders(baseUrl).forEach { (k, v) -> header(k, v) }
        }.body()
        if (searchResponse.isBlockedOrErrorPage()) return emptyList()

        val searchJson = json.parseToJsonElement(searchResponse).jsonObject
        val first = searchJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return emptyList()
        val session = first["session"]?.jsonPrimitive?.contentOrNull
            ?: first["id"]?.jsonPrimitive?.contentOrNull
            ?: return emptyList()

        val episodes = mutableListOf<AnimeEpisode>()
        var page = 1
        var lastPage = 1
        do {
            val releaseResponse: String = client.get(apiUrl) {
                parameter("m", "release")
                parameter("id", session)
                parameter("sort", "episode_asc")
                parameter("page", page)
                browserHeaders(baseUrl).forEach { (k, v) -> header(k, v) }
            }.body()
            if (releaseResponse.isBlockedOrErrorPage()) break

            val releaseJson = json.parseToJsonElement(releaseResponse).jsonObject
            lastPage = releaseJson["last_page"]?.jsonPrimitive?.intOrNull
                ?: releaseJson["lastPage"]?.jsonPrimitive?.intOrNull
                ?: lastPage
            val data = releaseJson["data"]?.jsonArray ?: break
            data.forEachIndexed { index, item ->
                val obj = item.jsonObject
                val episodeNumber = obj["episode"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: (episodes.size + index + 1)
                val episodeSession = obj["session"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (episodeSession.isNotBlank()) {
                    episodes.add(
                        AnimeEpisode(
                            episodeNumber = episodeNumber,
                            title = "Episode $episodeNumber",
                            url = "$baseUrl/play/$session/$episodeSession"
                        )
                    )
                }
            }
            page++
        } while (page <= lastPage && page <= 20)

        return episodes.distinctBy { it.url }.sortedByDescending { it.episodeNumber }
    }

    private fun candidateBaseUrls(preferred: String? = null): List<String> =
        buildList {
            if (!preferred.isNullOrBlank()) add(preferred)
            cachedBaseUrl?.let { add(it) }
            addAll(BASE_URL_CANDIDATES)
        }.distinct()

    private fun browserHeaders(baseUrl: String): Map<String, String> =
        BASE_HEADERS + ("Referer" to baseUrl)

    private fun extractDirectAnimePaheMediaUrl(html: String): String? {
        val cleaned = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .decodeHtmlEntitiesLite()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:file|src|url)["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|url)\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(cleaned)?.let { match ->
                match.groupValues.getOrNull(1).takeUnless { it.isNullOrBlank() } ?: match.value.trim('"', '\'')
            }
        }?.decodeHtmlEntitiesLite()
    }
}

class ConsumetAnimeScraper(private val client: HttpClient) {
    companion object {
        private const val MARKER_PREFIX = "consumet://"

        fun isConsumetEpisodeUrl(url: String): Boolean =
            url.startsWith(MARKER_PREFIX, ignoreCase = true)
    }

    suspend fun fetchEpisodes(
        provider: String,
        animeTitleQuery: String,
        alternateQueries: List<String> = emptyList(),
        maxEpisodes: Int = 300
    ): List<AnimeEpisode> {
        val queries = (listOf(animeTitleQuery) + alternateQueries)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        for (query in queries) {
            val episodes = runCatching {
                val raw: String = client.get("${AppReleaseConfig.API_BASE_URL}/content/anime/episodes") {
                    parameter("provider", provider)
                    parameter("q", query)
                    parameter("limit", maxEpisodes.coerceAtLeast(1))
                }.body()
                val root = json.parseToJsonElement(raw).jsonObject
                val data = root["data"]?.jsonObject ?: return@runCatching emptyList()
                data["episodes"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { element ->
                        val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                        val number = item["episodeNumber"]?.jsonPrimitive?.intOrNull
                            ?: item["number"]?.jsonPrimitive?.intOrNull
                            ?: 0
                        val url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isBlank()) return@mapNotNull null
                        AnimeEpisode(
                            episodeNumber = number,
                            title = item["title"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?: "Episode ${number.takeIf { it > 0 } ?: ""}".trim(),
                            url = url,
                            thumbnail = item["thumbnail"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        )
                    }
                    .distinctBy { it.url }
                    .sortedByDescending { it.episodeNumber }
            }.getOrElse { error ->
                println("[Consumet:$provider] Episode fetch failed for '$query': ${error.message}")
                emptyList()
            }
            if (episodes.isNotEmpty()) return episodes
        }
        return emptyList()
    }

    suspend fun extractStreamUrl(episodePageUrl: String): String? {
        val ref = parseEpisodeRef(episodePageUrl) ?: return null
        return runCatching {
            val raw: String = client.get("${AppReleaseConfig.API_BASE_URL}/content/anime/stream") {
                parameter("provider", ref.provider)
                parameter("episodeId", ref.episodeId)
            }.body()
            val root = json.parseToJsonElement(raw).jsonObject
            val data = root["data"]?.jsonObject ?: return@runCatching null
            val route = data["route"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val streamUrl = data["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            streamUrl.takeIf {
                route.equals("direct", ignoreCase = true) && it.isDirectAnimeMediaUrl()
            }
        }.getOrElse { error ->
            println("[Consumet:${ref.provider}] Stream extraction failed: ${error.message}")
            null
        }
    }

    private fun parseEpisodeRef(url: String): ConsumetEpisodeRef? {
        if (!isConsumetEpisodeUrl(url)) return null
        val rest = url.removePrefix(MARKER_PREFIX)
        val provider = rest.substringBefore("/").trim()
        val encodedEpisodeId = rest.substringAfter("/", "").trim()
        if (provider.isBlank() || encodedEpisodeId.isBlank()) return null
        return ConsumetEpisodeRef(
            provider = provider,
            episodeId = encodedEpisodeId.decodeURLQueryComponent()
        )
    }

    private data class ConsumetEpisodeRef(
        val provider: String,
        val episodeId: String
    )
}

private fun toAbsoluteAnimeUrl(baseUrl: String, href: String): String {
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    return baseUrl.trimEnd('/') + "/" + href.trimStart('/')
}

private fun String.originOrNull(): String? =
    runCatching {
        val url = Url(this)
        "${url.protocol.name}://${url.host}"
    }.getOrNull()

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
    return clean.endsWith(".m3u8") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
        Regex("""/(playlist|manifest|hls|dash)(/|$)""").containsMatchIn(clean)
}

private fun String.isNonPlayableAnimeProviderUrl(): Boolean {
    val lower = lowercase()
    return "youtube.com" in lower ||
        "youtu.be" in lower ||
        "trailer" in lower ||
        "doubleclick" in lower ||
        "googleadservices" in lower ||
        "popads" in lower ||
        "popcash" in lower
}
