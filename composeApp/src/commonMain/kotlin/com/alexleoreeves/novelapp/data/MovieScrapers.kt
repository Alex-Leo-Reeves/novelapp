package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.plugins.*
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
    val type: String, // KDRAMA, CARTOON, MOVIE, TVSHOW, YOUTUBE_NOLLY
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
        if (resolved.contains("wcostream", ignoreCase = true)) {
            return resolved
        }
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
            }.reversed()
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

            extractDirectVideoUrl(html)?.let { return it }

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

                val iframeDoc = Ksoup.parse(iframeHtml)
                val sourceSrc = iframeDoc.select("video source, source").firstOrNull()?.attr("src").orEmpty()
                if (sourceSrc.isNotBlank() && sourceSrc.toAbsoluteVideoUrl(iframeUrl) != null) {
                    return absoluteUrl(iframeUrl, sourceSrc)
                }
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

// ─────────────────────────────────────────────────────────────────────────────
//  YouTubeNollywoodScraper — searches YouTube (via Piped API) for Nigerian
//  movies / Nollywood videos that are NOT on TMDB.
//
//  Piped API is an open-source privacy YouTube frontend that returns ad-free
//  direct .mp4/m3u8 stream URLs playable in ExoPlayer. No ads, no tracking,
//  no YouTube page loading — just raw video streams.
//
//  Workflow:
//    1. Search Piped for "Nollywood full movie" → get YouTube video IDs
//    2. Each video ID → fetch direct stream URL via Piped's /streams endpoint
//    3. Play the .mp4 in the existing AnimePlayerScreen
// ─────────────────────────────────────────────────────────────────────────────
class YouTubeNollywoodScraper(private val httpClient: HttpClient) {
    private companion object {
        // Primary Piped API instance. Multiple fallback instances available.
        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.lunar.icu",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.moomoo.me"
        )
        private const val SEARCH_LIMIT = 20
    }

    /** Sane defaults — long timeout because Piped can be slow */
    private fun defaultRequest(query: String, filter: String = "videos"): HttpRequestBuilder.() -> Unit = {
        header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        header("Accept", "application/json")
        parameter("q", query)
        parameter("filter", filter)
    }

    /**
     * Search for Nollywood movies on YouTube via Piped API.
     * Returns MediaResult items with type="YOUTUBE_NOLLY" and id=youtube-video-id.
     * The coverUrl is the YouTube thumbnail.
     */
    suspend fun search(query: String, page: Int = 1): List<MediaResult> {
        val keywords = listOf(
            "nigerian movie", "nollywood movie", "naija movie",
            "nigerian film full movie", "nollywood full movie",
            "yoruba movie full", "nigerian comedy movie",
            "nollywood latest movie", "nigerian action movie",
            "nigerian drama movie"
        )
        val searchQuery = if (query.length >= 3) query else keywords.getOrElse((page - 1).coerceAtMost(keywords.size - 1)) { "nollywood movie" }

        return try {
            val baseUrl = getWorkingInstance()
            val response = httpClient.get("$baseUrl/search") {
                defaultRequest(searchQuery, "videos")
                parameter("page", page.coerceIn(1, 5))
            }.bodyAsText()

            val root = Json.parseToJsonElement(response).jsonObject
            val items = root["items"]?.jsonArray ?: return emptyList()

            items.mapNotNull { item ->
                val obj = item.jsonObject
                val videoId = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?.substringAfter("/watch?v=")
                    .orEmpty()
                if (videoId.isBlank()) return@mapNotNull null

                val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val thumbnail = obj["thumbnail"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // Piped returns relative thumbnails from YouTube
                val coverUrl = thumbnail.let { t ->
                    if (t.startsWith("//")) "https:$t" else if (t.startsWith("/")) "https://i.ytimg.com$t" else t
                }
                val uploader = obj["uploaderName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val duration = obj["duration"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val views = obj["views"]?.jsonPrimitive?.longOrNull ?: 0L

                val durationStr = formatDuration(duration)
                val description = "Nigerian movie on YouTube" +
                    if (uploader.isNotBlank()) " by $uploader" else "" +
                    if (durationStr.isNotBlank()) " · $durationStr" else ""

                MediaResult(
                    id = "youtube_nollywood_$videoId",
                    title = title,
                    coverUrl = coverUrl,
                    description = description,
                    genres = "Nigerian, Nollywood, YouTube",
                    type = "YOUTUBE_NOLLY",
                    rating = if (views > 0) "${formatViews(views)} views" else "",
                    releaseDate = obj["uploadedDate"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }.distinctBy { it.id }.take(SEARCH_LIMIT)
        } catch (e: Exception) {
            println("[YouTubeNollywood] Search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get the direct stream URL for a YouTube video via Piped.
     * Returns the highest-quality .mp4 stream URL that ExoPlayer can play.
     * Completely ad-free — Piped strips all YouTube ads server-side.
     */
    suspend fun extractStreamUrl(videoId: String): String? {
        return try {
            val baseUrl = getWorkingInstance()
            val response = httpClient.get("$baseUrl/streams/$videoId") {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                header("Accept", "application/json")
            }.bodyAsText()

            val root = Json.parseToJsonElement(response).jsonObject

            // Piped returns video streams in "videoStreams" array
            // Each has: url, quality, format, mimeType, codec
            // We want the best quality MP4 stream
            val videoStreams: JsonArray = root["videoStreams"]?.jsonArray ?: JsonArray(emptyList())
            val audioStreams: JsonArray = root["audioStreams"]?.jsonArray ?: JsonArray(emptyList())

            // Priority order: highest quality .mp4 stream
            val bestVideo = findBestVideoStream(videoStreams)
            val bestAudio = findBestAudioStream(audioStreams)

            // Return the direct video URL if available
            bestVideo?.let { return it }

            // Fallback: Piped's HLS manifest (proxied through Piped)
            val hlsUrl = root["hls"]?.jsonPrimitive?.contentOrNull
            hlsUrl?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("[YouTubeNollywood] Stream extraction failed for $videoId: ${e.message}")
            null
        }
    }

    /**
     * Fetch Piped channel info listing for a given query to get more Nigerian movie content.
     */
    suspend fun fetchYouTubeNollywoodFeed(page: Int = 1): List<MediaResult> {
        val keywords = listOf(
            "nigerian movie", "nollywood movie", "yoruba movie full",
            "nigerian full movie 2024", "nollywood latest full movie",
            "naija film", "nigerian comedy 2024"
        )
        val seed = keywords.getOrElse((page - 1).coerceAtMost(keywords.size - 1)) { "nollywood movie" }
        return search(seed, page)
    }

    /** Piped API instance rotation — tries each until one responds */
    private suspend fun getWorkingInstance(): String {
        for (instance in PIPED_INSTANCES) {
            try {
                val test = httpClient.get("$instance/health") {
                    timeout {
                        requestTimeoutMillis = 3_000
                    }
                }.bodyAsText()
                if (!test.isBlockedOrErrorPage()) return instance
            } catch (e: Exception) {
                continue
            }
        }
        return PIPED_INSTANCES.first()
    }

    /** Find the best quality video stream (MP4 preferred, highest resolution) */
    private fun findBestVideoStream(streams: JsonArray): String? {
        // Sort: prefer MP4, then highest resolution
        val sorted = streams.mapNotNull { it.jsonObject }
            .sortedByDescending { stream ->
                val quality = stream["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val mimeType = stream["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val resolution = stream["resolution"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val score = when {
                    quality.contains("1080", ignoreCase = true) -> 4000
                    quality.contains("720", ignoreCase = true) -> 3000
                    quality.contains("480", ignoreCase = true) -> 2000
                    quality.contains("360", ignoreCase = true) -> 1000
                    else -> 0
                } + if (mimeType.contains("mp4", ignoreCase = true)) 500 else 0
                score
            }
        return sorted.firstOrNull()
            ?.let { it["url"]?.jsonPrimitive?.contentOrNull }
    }

    /** Find the best audio stream for potential future audio-only playback */
    private fun findBestAudioStream(streams: JsonArray): String? {
        val sorted = streams.mapNotNull { it.jsonObject }
            .sortedByDescending { it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0 }
        return sorted.firstOrNull()
            ?.let { it["url"]?.jsonPrimitive?.contentOrNull }
    }

    private fun formatDuration(duration: String): String {
        val secs = duration.toLongOrNull() ?: return ""
        return when {
            secs >= 3600 -> String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
            secs >= 60 -> String.format("%d:%02d", secs / 60, secs % 60)
            else -> "${secs}s"
        }
    }

    private fun formatViews(views: Long): String = when {
        views >= 1_000_000 -> String.format("%.1fM", views / 1_000_000.0)
        views >= 1_000 -> String.format("%.1fK", views / 1_000.0)
        else -> views.toString()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  NollywoodScraper — searches TMDB for Nigerian/Nollywood content using
//  known popular Nollywood titles, Nigerian keywords, and discover queries.
// ─────────────────────────────────────────────────────────────────────────────
class NollywoodScraper(private val httpClient: HttpClient) {
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

    companion object {
        val KNOWN_NOLLYWOOD_TITLES: List<NollywoodEntry> = listOf(
            NollywoodEntry("The Johnsons", "tv", 67433, "Nigerian TV, Comedy"),
            NollywoodEntry("Tinsel", "tv", 31792, "Nigerian TV, Drama"),
            NollywoodEntry("Jenifa's Diary", "tv", 63542, "Nigerian TV, Comedy"),
            NollywoodEntry("The Men's Club", "tv", 127142, "Nigerian TV, Drama"),
            NollywoodEntry("Battleground", "tv", 93674, "Nigerian TV, Drama"),
            NollywoodEntry("Husbands of Lagos", "tv", 125127, "Nigerian TV, Drama"),
            NollywoodEntry("Rumour Has It", "tv", 203365, "Nigerian TV, Drama"),
            NollywoodEntry("Daddy's Girls", "tv", 96801, "Nigerian TV, Drama"),
            NollywoodEntry("My Flatmates", "tv", 118396, "Nigerian TV, Comedy"),
            NollywoodEntry("The Therapist", "tv", 119418, "Nigerian TV, Drama"),
            NollywoodEntry("The Wedding Party", "movie", 408380, "Nigerian, Comedy, Romance"),
            NollywoodEntry("King of Boys", "movie", 596100, "Nigerian, Crime, Drama"),
            NollywoodEntry("Lionheart", "movie", 492718, "Nigerian, Comedy, Drama"),
            NollywoodEntry("Omo Ghetto: The Saga", "movie", 787724, "Nigerian, Comedy, Crime"),
            NollywoodEntry("Living in Bondage: Breaking Free", "movie", 653440, "Nigerian, Drama, Thriller"),
            NollywoodEntry("Blood Vessel", "movie", 812586, "Nigerian, Thriller"),
            NollywoodEntry("The Figurine", "movie", 45596, "Nigerian, Drama, Thriller"),
            NollywoodEntry("October 1", "movie", 295578, "Nigerian, Drama, Thriller"),
            NollywoodEntry("Chief Daddy", "movie", 549584, "Nigerian, Comedy"),
            NollywoodEntry("Merry Men", "movie", 538239, "Nigerian, Comedy, Crime"),
            NollywoodEntry("Merry Men 2", "movie", 591294, "Nigerian, Comedy, Crime"),
            NollywoodEntry("Rattle Snake", "movie", 926421, "Nigerian, Drama, Thriller"),
            NollywoodEntry("Nneka the Pretty Serpent", "movie", 929252, "Nigerian, Drama, Thriller"),
            NollywoodEntry("Isoken", "movie", 448114, "Nigerian, Comedy, Romance"),
            NollywoodEntry("The Bling Lagosians", "movie", 603575, "Nigerian, Comedy"),
            NollywoodEntry("Sugar Rush", "movie", 621947, "Nigerian, Comedy, Crime"),
            NollywoodEntry("Your Excellency", "movie", 619719, "Nigerian, Comedy"),
            NollywoodEntry("Quam's Money", "movie", 742373, "Nigerian, Comedy, Crime"),
            NollywoodEntry("Citation", "movie", 620717, "Nigerian, Drama"),
            NollywoodEntry("Fate of Alakada", "movie", 728657, "Nigerian, Comedy"),
            NollywoodEntry("Aki and Pawpaw", "movie", 841559, "Nigerian, Comedy"),
            NollywoodEntry("Dinner at My Place", "movie", 842182, "Nigerian, Drama"),
            NollywoodEntry("The Set", "movie", 644619, "Nigerian, Drama"),
            NollywoodEntry("The Wedding Party 2", "movie", 510454, "Nigerian, Comedy, Romance"),
            NollywoodEntry("Phone Swap", "movie", 157665, "Nigerian, Comedy, Romance"),
            NollywoodEntry("Glamour Girls", "movie", 581682, "Nigerian, Drama"),
            NollywoodEntry("4th Republic", "movie", 583387, "Nigerian, Drama, Thriller"),
            NollywoodEntry("Kunu", "movie", 685478, "Nigerian, Drama"),
            NollywoodEntry("The Origin: Madam Koi Koi", "movie", 835771, "Nigerian, Horror"),
            NollywoodEntry("A Soldier's Story", "movie", 599549, "Nigerian, Drama"),
            NollywoodEntry("Collision Course", "movie", 677993, "Nigerian, Drama"),
            NollywoodEntry("Namaste Wahala", "movie", 796924, "Nigerian, Comedy, Romance"),
            NollywoodEntry("Love in Lagos", "movie", 635781, "Nigerian, Romance"),
            NollywoodEntry("Introducing the Kujus", "movie", 781490, "Nigerian, Comedy"),
            NollywoodEntry("Day of Destiny", "movie", 785240, "Nigerian, Comedy"),
            NollywoodEntry("One Lagos Night", "movie", 836406, "Nigerian, Comedy, Romance"),
            NollywoodEntry("The Wait", "movie", 821071, "Nigerian, Drama"),
            NollywoodEntry("Alter Date", "movie", 832505, "Nigerian, Romance"),
            NollywoodEntry("My Wife & I", "movie", 605699, "Nigerian, Comedy"),
            NollywoodEntry("Hearts of Steel", "movie", 1060269, "Nigerian, Crime"),
            NollywoodEntry("The Good Husband", "movie", 950845, "Nigerian, Drama"),
            NollywoodEntry("Ada Omo Daddy", "movie", 792981, "Nigerian, Comedy"),
            NollywoodEntry("Elesin Oba", "movie", 975773, "Nigerian, Drama, History"),
            NollywoodEntry("Man of God", "movie", 860623, "Nigerian, Drama"),
            NollywoodEntry("Brotherhood", "movie", 925760, "Nigerian, Crime, Action"),
            NollywoodEntry("Kambili: The Whole 30 Yards", "movie", 927959, "Nigerian, Comedy, Romance"),
            NollywoodEntry("Anikulapo", "movie", 997418, "Nigerian, Drama, Fantasy"),
            NollywoodEntry("Jagun Jagun", "movie", 1160244, "Nigerian, Action, Drama"),
            NollywoodEntry("A Tribe Called Judah", "movie", 1211508, "Nigerian, Comedy, Drama"),
            NollywoodEntry("The Black Book", "movie", 1065470, "Nigerian, Crime, Drama"),
            NollywoodEntry("Ijogbon", "movie", 1182146, "Nigerian, Drama"),
            NollywoodEntry("Gangs of Lagos", "movie", 1060590, "Nigerian, Crime, Action"),
            NollywoodEntry("Shanty Town", "movie", 1079938, "Nigerian, Crime, Drama"),
            NollywoodEntry("The Trade", "movie", 1061483, "Nigerian, Drama"),
            NollywoodEntry("Hijack '93", "movie", 1148421, "Nigerian, Thriller"),
            NollywoodEntry("Afamefuna", "movie", 1284297, "Nigerian, Drama"),
        )

        val NOLLYWOOD_SEARCH_KEYWORDS = listOf(
            "Nollywood", "Nigerian movie", "Nigerian film", "Nigeria film",
            "Nollywood comedy", "Nollywood drama", "Nollywood romance",
            "Nigerian cinema", "Africa Magic", "Yoruba movie",
            "Naija movie", "Nigerian TV series", "Lagos movie",
            "Nigerian action", "Nigerian thriller"
        )
    }

    data class NollywoodEntry(
        val title: String,
        val tmdbType: String,
        val tmdbId: Int,
        val genres: String = ""
    )

    suspend fun fetchNollywoodFeed(page: Int = 1): List<MediaResult> {
        val results = mutableListOf<MediaResult>()

        val knownResults = KNOWN_NOLLYWOOD_TITLES
            .filterIndexed { index, _ ->
                val pageSize = 8
                val start = (page - 1) * pageSize
                index in start until (start + pageSize)
            }
            .mapNotNull { entry ->
                fetchTmdbDetails(entry.tmdbId, entry.tmdbType)?.let { media ->
                    MediaResult(
                        id = media.id,
                        title = media.title,
                        coverUrl = media.coverUrl,
                        description = media.description,
                        genres = entry.genres.ifBlank { media.genres },
                        type = if (entry.tmdbType == "movie") "MOVIE" else "TVSHOW",
                        rating = media.rating,
                        releaseDate = media.releaseDate
                    )
                }
            }

        results.addAll(knownResults)

        if (results.size < 6 && page <= 2) {
            val keyword = NOLLYWOOD_SEARCH_KEYWORDS.getOrElse((page - 1).coerceAtMost(NOLLYWOOD_SEARCH_KEYWORDS.size - 1)) { "Nollywood" }
            val searchResults = searchTmdb(keyword, page)
            results.addAll(searchResults.filter { search ->
                results.none { existing -> existing.title.equals(search.title, ignoreCase = true) }
            })
        }

        return results.distinctBy { it.id }.take(24)
    }

    suspend fun search(category: VideoCategory, query: String, page: Int = 1): List<MediaResult> {
        val results = mutableListOf<MediaResult>()

        val knownMatch = KNOWN_NOLLYWOOD_TITLES.filter { entry ->
            entry.title.contains(query, ignoreCase = true) ||
                query.contains(entry.title, ignoreCase = true) ||
                query.split(" ").all { word -> entry.title.contains(word, ignoreCase = true) }
        }
        for (entry in knownMatch) {
            fetchTmdbDetails(entry.tmdbId, entry.tmdbType)?.let { results.add(it) }
        }

        if (results.size < 12) {
            val tmdbResults = searchTmdb(query, page)
            results.addAll(tmdbResults.filter { tmdb ->
                results.none { existing -> existing.id == tmdb.id }
            })
        }

        return results.distinctBy { it.id }.take(24)
    }

    suspend fun fetchTmdbDetails(tmdbId: Int, tmdbType: String): MediaResult? {
        return try {
            val response = httpClient.get("$tmdbBaseUrl/$tmdbType/$tmdbId") { tmdbAuth() }.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(response).jsonObject

            val title = when (tmdbType) {
                "movie" -> obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["original_title"]?.jsonPrimitive?.contentOrNull
                else -> obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["original_name"]?.jsonPrimitive?.contentOrNull
            } ?: return null

            val poster = obj["poster_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            MediaResult(
                id = "tmdb_${tmdbType}_$tmdbId",
                title = title,
                coverUrl = poster.toFullPosterUrl(),
                description = obj["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                genres = obj["genres"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString(", ")
                    .orEmpty(),
                type = if (tmdbType == "movie") "MOVIE" else "TVSHOW",
                rating = obj["vote_average"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                releaseDate = (if (tmdbType == "movie") obj["release_date"] else obj["first_air_date"])
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun searchTmdb(query: String, page: Int = 1): List<MediaResult> {
        return try {
            val response = httpClient.get("$tmdbBaseUrl/search/multi") {
                tmdbAuth()
                parameter("query", query)
                parameter("include_adult", "false")
                parameter("page", page.coerceIn(1, 10))
            }.bodyAsText()

            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { el ->
                val obj = el.jsonObject
                val mediaType = obj["media_type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null

                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val poster = obj["poster_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val overview = obj["overview"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val originCountry = obj["origin_country"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                val lang = obj["original_language"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val isNigerian = originCountry.any { it.equals("NG", ignoreCase = true) } ||
                    lang.equals("yo", ignoreCase = true) ||
                    lang.equals("ha", ignoreCase = true) ||
                    lang.equals("ig", ignoreCase = true) ||
                    overview.contains("Nigeria", ignoreCase = true) ||
                    overview.contains("Nollywood", ignoreCase = true) ||
                    overview.contains("Lagos", ignoreCase = true) ||
                    overview.contains("Nigerian", ignoreCase = true) ||
                    title.contains("Lagos", ignoreCase = true) ||
                    title.contains("Nollywood", ignoreCase = true)

                if (!isNigerian) return@mapNotNull null

                MediaResult(
                    id = "tmdb_${mediaType}_$id",
                    title = title,
                    coverUrl = poster.toFullPosterUrl(),
                    description = overview,
                    genres = mediaType.let { type ->
                        buildList {
                            if (type == "movie") add("Movie") else add("TV Show")
                            add("Nigerian")
                        }.joinToString(", ")
                    },
                    type = if (mediaType == "movie") "MOVIE" else "TVSHOW",
                    releaseDate = obj["release_date"]?.jsonPrimitive?.contentOrNull
                        ?: obj["first_air_date"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun String.toFullPosterUrl(): String =
        if (isBlank()) "" else "$imageBaseUrl$this"
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
            val response = httpClient.get("$tmdbBaseUrl/trending/$type/week") { tmdbAuth() }.bodyAsText()
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
            val response = httpClient.get("$tmdbBaseUrl/tv/$tvId") { tmdbAuth() }.bodyAsText()
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
            (1..16).map { ep ->
                MediaEpisode(title = "Episode $ep", url = "tv:$tvId:1:$ep", episodeNumber = ep)
            }
        }
    }
}

private fun extractDirectVideoUrl(html: String): String? {
    val cleaned = html
        .replace("\\/", "/")
        .replace("&", "&")
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

/**
 * Block URLs that cannot produce a playable video.
 * youtube.com/youtu.be IS blocked for the generic scraper pipeline
 * because those are web pages, not direct video streams.
 * YouTube-based Nollywood movies use YouTubeNollywoodScraper instead,
 * which extracts direct .mp4 from Piped API — those Piped URLs are
 * direct video streams and are NOT blocked.
 */
private fun String.isNonPlayableVideoProviderUrl(): Boolean {
    val lower = lowercase()
    return "trailer" in lower ||
        "doubleclick" in lower ||
        "googleadservices" in lower ||
        "popads" in lower ||
        "popcash" in lower
}

/**
 * Convert a YouTubeNollywood MediaResult (type="YOUTUBE_NOLLY") to a
 * UnifiedSearchResult that the discover UI and MediaDetailScreen can use.
 * The id encodes the YouTube video ID so it can be extracted for Piped playback.
 */
fun MediaResult.toYouTubeNollywoodVideo(): UnifiedSearchResult =
    UnifiedSearchResult(
        id = if (id.startsWith("youtube_nollywood_")) id else "youtube_nollywood_$id",
        title = title,
        coverUrl = coverUrl,
        detailPageUrl = id,
        sourceName = "YouTube",
        genre = genres.ifBlank { "Nigerian, Nollywood" },
        synopsis = description.ifBlank { "Nigerian movie from YouTube" },
        isVideo = true,
        mediaKind = VideoCategory.NIGERIAN.name,
        url = id
    )
