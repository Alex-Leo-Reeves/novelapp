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
    private companion object {
        private const val FALLBACK_URL = "https://dramacool.bg"
        private const val BRAND_QUERY = "dramacool official"
    }

    private suspend fun liveBaseUrl(): String =
        resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val baseUrl = liveBaseUrl()
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
            val baseUrl = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(detailUrl, baseUrl)
            val html = httpClient.get(effectiveUrl).bodyAsText()
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
            val baseUrl = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(episodeUrl, baseUrl)
            val html = httpClient.get(effectiveUrl).bodyAsText()
            extractDirectVideoUrl(html)?.let { return it }
            val doc = Ksoup.parse(html)
            val iframeSrc = doc.select("iframe[src*=/embed/]").attr("src")
                .ifEmpty { doc.select("iframe").attr("src") }
            val iframeUrl = iframeSrc.toAbsoluteVideoUrl(effectiveUrl)
                ?.takeUnless { it.isNonPlayableVideoProviderUrl() }
            if (iframeUrl != null) {
                val iframeHtml = httpClient.get(iframeUrl) {
                    header("Referer", effectiveUrl)
                }.bodyAsText()
                extractDirectVideoUrl(iframeHtml) ?: iframeUrl
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
// ─────────────────────────────────────────────────────────────────────────────
//  WCOStream Scraper — classic 90s/2000s cartoons (Nickelodeon, Cartoon Network,
//  Disney Channel legacy animation). WCOStream hosts almost every piece of
//  animated history ever created. Stream extraction: scrape episode page → find
//  iframe embed → extract direct .mp4/.m3u8 URL.
//  Domain auto-heals via resolveLiveDomain().
// ─────────────────────────────────────────────────────────────────────────────
class WcoStreamScraper(private val httpClient: HttpClient) {
    private companion object {
        private const val FALLBACK_URL = "https://www.wcostream.org"
        private const val BRAND_QUERY = "wcostream cartoon official"
        // Alternative fallback if main one fails
        private val FALLBACK_CHAIN = listOf(
            "https://www.wcostream.org",
            "https://www.wcostream.com",
            "https://wcostream.net",
            "https://wcostream.one",
            "https://www.wcostream.tv"
        )
    }

    private suspend fun liveBaseUrl(): String {
        val resolved = resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)
        // Verify resolved domain is a WCOStream domain
        if (resolved.contains("wcostream", ignoreCase = true)) {
            return resolved
        }
        // Try each fallback URL in order (global timeout on httpClient handles this)
        for (fallback in FALLBACK_CHAIN) {
            try {
                val test = httpClient.get(fallback) {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                }.bodyAsText()
                if (!test.isBlockedOrErrorPage()) {
                    println("[WCOStream] Using fallback: $fallback")
                    return fallback
                }
            } catch (e: Exception) {
                continue
            }
        }
        return FALLBACK_URL
    }

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val baseUrl = liveBaseUrl()
            val html = httpClient.get("$baseUrl/search") {
                parameter("keyword", query.replace(" ", "+"))
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                header("Referer", "$baseUrl/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            doc.select("div.category-item, div.video-item, div.item, article.item, li.video-item, div.post").mapNotNull { el ->
                val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
                val href = link.attr("href")
                val title = link.attr("title")
                    .ifBlank { link.text() }
                    .ifBlank { el.select("img").firstOrNull()?.attr("alt").orEmpty() }
                    .decodeHtmlEntitiesLite()
                if (href.isBlank() || title.isBlank() || title.isNavigationTitle() ||
                    href.contains("/category") || href.contains("/tag") || href.contains("/genre")) return@mapNotNull null
                val cover = el.select("img").firstOrNull()
                    ?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
                val year = el.select("span.year, span.date, div.year").firstOrNull()?.text().orEmpty()
                MediaResult(
                    id = absoluteUrl(baseUrl, href),
                    title = title,
                    coverUrl = absoluteUrl(baseUrl, cover),
                    description = "Classic cartoon from WCOStream",
                    genres = "Animation, Cartoon" + if (year.isNotBlank()) ", $year" else "",
                    type = "CARTOON"
                )
            }.distinctBy { it.id }.take(30)
        } catch (e: Exception) {
            println("[WCOStream] Search failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchEpisodes(detailUrl: String): List<MediaEpisode> {
        return try {
            val baseUrl = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(detailUrl, baseUrl)
            val html = httpClient.get(effectiveUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$baseUrl/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            // WCOStream lists episodes inside <ul> <li> <a> or <div> <a> structures
            // Usually: div#main-content ul li a, or div.episodes-list a
            val episodeLinks = doc.select("div#main-content ul li a, div.list-episode a, ul.episodes li a, div.episodes a, a[href*=/episode]")
                .filter { el ->
                    val href = el.attr("href")
                    href.isNotBlank() && !href.contains("/search") && !href.contains("/category")
                }
                .distinctBy { it.attr("href") }

            episodeLinks.mapIndexed { index, el ->
                val href = el.attr("href")
                val title = el.text().decodeHtmlEntitiesLite().ifBlank { "Episode ${index + 1}" }
                MediaEpisode(
                    title = title,
                    url = absoluteUrl(baseUrl, href),
                    episodeNumber = index + 1
                )
            }.reversed() // ascending order
        } catch (e: Exception) {
            println("[WCOStream] Episodes failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun extractStreamUrl(episodeUrl: String): String? {
        return try {
            val baseUrl = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(episodeUrl, baseUrl)
            val html = httpClient.get(effectiveUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$baseUrl/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return null

            // Step 1: Try direct media URL extraction from the episode page
            extractDirectVideoUrl(html)?.let { return it }

            // Step 2: Extract the iframe embed src (WCOStream wraps videos in <div id="video-container"><iframe src="..." /></div>)
            val doc = Ksoup.parse(html)
            val iframeSrc = doc.select("div#video-container iframe, div.video-wrapper iframe, iframe[src*=/embed], iframe[src*=cembed]")
                .firstOrNull()
                ?.attr("src")
                .orEmpty()

            val iframeUrl = iframeSrc.toAbsoluteVideoUrl(effectiveUrl)
                ?.takeUnless { it.isNonPlayableVideoProviderUrl() }
            if (iframeUrl != null) {
                val iframeHtml = httpClient.get(iframeUrl) {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                    header("Referer", effectiveUrl)
                }.bodyAsText()
                extractDirectVideoUrl(iframeHtml)?.let { return it }

                // Step 3: Try loading the embed page with a WebView-like approach: extract any <source> or data-video attributes
                val iframeDoc = Ksoup.parse(iframeHtml)
                val sourceSrc = iframeDoc.select("video source, source").firstOrNull()?.attr("src").orEmpty()
                if (sourceSrc.isNotBlank() && sourceSrc.toAbsoluteVideoUrl(iframeUrl) != null) {
                    return absoluteUrl(iframeUrl, sourceSrc)
                }
                // data-video or file variable
                val dataVideo = Regex("""data-video=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(iframeHtml)
                    ?.groupValues?.getOrNull(1).orEmpty()
                if (dataVideo.isNotBlank()) return absoluteUrl(iframeUrl, dataVideo)
            }
            null
        } catch (e: Exception) {
            println("[WCOStream] Stream extraction failed: ${e.message}")
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  KimCartoon Scraper
// ─────────────────────────────────────────────────────────────────────────────
class KimCartoonScraper(private val httpClient: HttpClient) {
    private companion object {
        private const val FALLBACK_URL = "https://kimcartoon.li"
        private const val BRAND_QUERY = "kimcartoon official"
    }

    private suspend fun liveBaseUrl(): String =
        resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val baseUrl = liveBaseUrl()
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
            val baseUrl = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(detailUrl, baseUrl)
            val html = httpClient.get(effectiveUrl).bodyAsText()
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
            val baseUrl = liveBaseUrl()
            val effectiveUrl = rewriteUrlOrigin(episodeUrl, baseUrl)
            val html = httpClient.get(effectiveUrl).bodyAsText()
            extractDirectVideoUrl(html)?.let { return it }
            val doc = Ksoup.parse(html)
            val playerIframe = doc.select("iframe#myContent, iframe[src*=embed]").attr("src")
            val iframeUrl = playerIframe.toAbsoluteVideoUrl(effectiveUrl)
                ?.takeUnless { it.isNonPlayableVideoProviderUrl() }
            if (iframeUrl != null) {
                val iframeHtml = httpClient.get(iframeUrl) {
                    header("Referer", effectiveUrl)
                }.bodyAsText()
                extractDirectVideoUrl(iframeHtml) ?: iframeUrl
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

private fun extractDirectVideoUrl(html: String): String? {
    val cleaned = html
        .replace("\\/", "/")
        .replace("&amp;", "&")
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
    }?.takeUnless { it.isNonPlayableVideoProviderUrl() }
}

private fun String.toAbsoluteVideoUrl(baseUrl: String): String? {
    val clean = trim()
    if (clean.isBlank() || clean.startsWith("javascript:", ignoreCase = true)) return null
    return when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> baseUrl.substringBefore("://")
            .let { scheme -> "$scheme://${baseUrl.substringAfter("://").substringBefore("/")}$clean" }
        clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
        else -> null
    }
}

private fun String.isNonPlayableVideoProviderUrl(): Boolean {
    val lower = lowercase()
    return "youtube.com" in lower ||
        "youtu.be" in lower ||
        "trailer" in lower ||
        "doubleclick" in lower ||
        "googleadservices" in lower ||
        "popads" in lower ||
        "popcash" in lower
}

// ─────────────────────────────────────────────────────────────────────────────
//  TMDB Metadata & Movie Scraper
// ─────────────────────────────────────────────────────────────────────────────
class TMDBMovieScraper(private val httpClient: HttpClient) {
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val imageBaseUrl = "https://image.tmdb.org/t/p/w500"

    private val usableToken = com.alexleoreeves.novelapp.BuildKonfig.TMDB_READ_ACCESS_TOKEN.trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("mock_") }
    private val usableApiKey = com.alexleoreeves.novelapp.BuildKonfig.TMDB_API_KEY.trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("mock_") }
        ?: "15d2ea6d0dc1d247f33e5405d4b507cc"

    private fun io.ktor.client.request.HttpRequestBuilder.tmdbAuth() {
        usableToken?.let {
            header("Authorization", "Bearer $it")
            return
        }
        parameter("api_key", usableApiKey)
    }

    suspend fun search(query: String): List<MediaResult> {
        return try {
            val response = httpClient.get("$tmdbBaseUrl/search/multi") {
                tmdbAuth()
                parameter("query", query)
                parameter("include_adult", "false")
            }.bodyAsText()
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

    /** Paged multi-search — searches pages 1..[page] and aggregates results */
    suspend fun searchMultiPaged(query: String, maxPages: Int = 3): List<MediaResult> {
        val all = mutableListOf<MediaResult>()
        for (p in 1..maxPages.coerceIn(1, 10)) {
            val page = try {
                val response = httpClient.get("$tmdbBaseUrl/search/multi") {
                    tmdbAuth()
                    parameter("query", query)
                    parameter("page", p)
                    parameter("include_adult", "false")
                }.bodyAsText()
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(response).jsonObject
                root["results"]?.jsonArray.orEmpty()
            } catch (e: Exception) {
                break
            }
            if (page.isEmpty()) break
            all.addAll(page.mapNotNull { el ->
                val obj = el.jsonObject
                val mediaType = obj["media_type"]?.jsonPrimitive?.content ?: "movie"
                if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content ?: ""
                val desc = obj["overview"]?.jsonPrimitive?.content ?: ""
                val poster = obj["poster_path"]?.jsonPrimitive?.contentOrNull
                val cover = if (poster != null) "$imageBaseUrl$poster" else ""
                MediaResult(
                    id = id,
                    title = title,
                    coverUrl = cover,
                    description = desc,
                    genres = if (mediaType == "movie") "Movie" else "TV Show",
                    type = if (mediaType == "movie") "MOVIE" else "TVSHOW",
                    releaseDate = obj["release_date"]?.jsonPrimitive?.content
                        ?: obj["first_air_date"]?.jsonPrimitive?.content ?: ""
                )
            })
            if (all.size >= 48) break
        }
        return all.distinctBy { it.id }.take(48)
    }

    suspend fun fetchTrending(type: String = "movie"): List<MediaResult> {
        return try {
            val response = httpClient.get("$tmdbBaseUrl/trending/$type/week") {
                tmdbAuth()
            }.bodyAsText()
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
            val response = httpClient.get("$tmdbBaseUrl/tv/$tvId") {
                tmdbAuth()
            }.bodyAsText()
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
            episodes.ifEmpty {
                (1..16).map { ep ->
                    MediaEpisode(title = "Episode $ep", url = "tv:$tvId:1:$ep", episodeNumber = ep)
                }
            }
        } catch (e: Exception) {
            // Fallback list of 16 episodes if call fails
            (1..16).map { ep ->
                MediaEpisode(title = "Episode $ep", url = "tv:$tvId:1:$ep", episodeNumber = ep)
            }
        }
    }
}
