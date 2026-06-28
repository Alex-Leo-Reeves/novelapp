package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import com.fleeksoft.ksoup.Ksoup
import com.alexleoreeves.novelapp.platform.currentTimeMillis

/**
 * Baseline interface that every Manga scraping / API source must implement.
 */
interface MangaScraper {
    val sourceName: String
    suspend fun searchManga(query: String): List<UnifiedSearchResult>
    suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter>
    suspend fun fetchMangaPages(chapterUrl: String): List<String>
}

// ─────────────────────────────────────────────────────────────────────────────
//  MangaDex API Source with OAuth Handshake
// ─────────────────────────────────────────────────────────────────────────────
class MangaDexSource(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val username: String,
    private val password: String
) : MangaScraper {

    override val sourceName = "MangaDex"
    private var accessToken: String? = null
    private var tokenExpiryTime = 0L

    /**
     * Checks if current token is expired, if so updates it via OAuth password flow.
     */
    private suspend fun ensureAuthenticated() {
        val now = currentTimeMillis()
        if (accessToken != null && now < tokenExpiryTime) return

        try {
            val response = httpClient.post("https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters.build {
                        append("grant_type", "password")
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("username", username)
                        append("password", password)
                    }
                )
            }.bodyAsText()

            val json = Json.parseToJsonElement(response).jsonObject
            accessToken = json["access_token"]?.jsonPrimitive?.content
            val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 900L
            tokenExpiryTime = now + (expiresIn - 60) * 1000L // 60s safety buffer
        } catch (e: Exception) {
            println("[MangaDex Auth] OAuth token refresh failed: ${e.message}")
        }
    }

    override suspend fun searchManga(query: String): List<UnifiedSearchResult> {
        ensureAuthenticated()
        return try {
            val response = httpClient.get("https://api.mangadex.org/manga") {
                parameter("title", query)
                parameter("limit", 15)
                parameter("includes[]", "cover_art")
                if (accessToken != null) {
                    header("Authorization", "Bearer $accessToken")
                }
            }.bodyAsText()

            val json = Json.parseToJsonElement(response).jsonObject
            val data = json["data"]?.jsonArray ?: return emptyList()
            data.map { item ->
                val obj = item.jsonObject
                val mangaId = obj["id"]?.jsonPrimitive?.content ?: ""
                val titleObj = obj["attributes"]?.jsonObject?.get("title")?.jsonObject
                val title = titleObj?.values?.firstOrNull()?.jsonPrimitive?.content
                    ?: titleObj?.get("en")?.jsonPrimitive?.content ?: "Unknown Title"

                // Extract cover filename from relationships
                val coverFileName = obj["relationships"]?.jsonArray?.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "cover_art"
                }?.jsonObject?.get("attributes")?.jsonObject?.get("fileName")?.jsonPrimitive?.content ?: ""

                val coverUrl = if (coverFileName.isNotEmpty()) {
                    "https://uploads.mangadex.org/covers/$mangaId/$coverFileName.256.jpg" // Data saver cover
                } else ""

                UnifiedSearchResult(
                    id = mangaId,
                    title = title,
                    coverUrl = coverUrl,
                    detailPageUrl = "mangadex://$mangaId",
                    sourceName = sourceName,
                    isManga = true,
                    genre = obj["attributes"]?.jsonObject?.get("originalLanguage")?.jsonPrimitive?.content ?: "Manga"
                )
            }
        } catch (e: Exception) {
            println("[MangaDex Search] Failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter> {
        ensureAuthenticated()
        val mangaId = mangaUrl.removePrefix("mangadex://")
        return try {
            val response = httpClient.get("https://api.mangadex.org/manga/$mangaId/feed") {
                parameter("limit", 100)
                parameter("translatedLanguage[]", "en")
                parameter("order[chapter]", "asc")
                if (accessToken != null) {
                    header("Authorization", "Bearer $accessToken")
                }
            }.bodyAsText()

            val json = Json.parseToJsonElement(response).jsonObject
            val data = json["data"]?.jsonArray ?: return emptyList()
            data.mapIndexed { idx, item ->
                val obj = item.jsonObject
                val chapterId = obj["id"]?.jsonPrimitive?.content ?: ""
                val attr = obj["attributes"]?.jsonObject
                val title = attr?.get("title")?.jsonPrimitive?.content ?: "Chapter ${idx + 1}"
                val chapterNum = attr?.get("chapter")?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: (idx + 1)

                MangaChapter(
                    title = "Vol ${attr?.get("volume")?.jsonPrimitive?.content ?: "0"} Ch ${attr?.get("chapter")?.jsonPrimitive?.content ?: ""} - $title",
                    url = "mangadex-chapter://$chapterId",
                    chapterNumber = chapterNum
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaPages(chapterUrl: String): List<String> {
        ensureAuthenticated()
        val chapterId = chapterUrl.removePrefix("mangadex-chapter://")
        return try {
            val response = httpClient.get("https://api.mangadex.org/at-home/server/$chapterId") {
                if (accessToken != null) {
                    header("Authorization", "Bearer $accessToken")
                }
            }.bodyAsText()

            val json = Json.parseToJsonElement(response).jsonObject
            val baseUrl = json["baseUrl"]?.jsonPrimitive?.content ?: ""
            val chapterObj = json["chapter"]?.jsonObject ?: return emptyList()
            val hash = chapterObj["hash"]?.jsonPrimitive?.content ?: ""
            val dataSaver = chapterObj["dataSaver"]?.jsonArray ?: return emptyList()

            // Map to data-saver CDN links for rapid OCR speed & lower memory footprints
            dataSaver.map { page ->
                "$baseUrl/data-saver/$hash/${page.jsonPrimitive.content}"
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  WeebCentral Scraper  (successor to MangaSee123)
//  Primary URL : https://weebcentral.com
//  Domain auto-heals via DuckDuckGo resolver if the site hops.
// ─────────────────────────────────────────────────────────────────────────────
class WeebCentralScraper(private val httpClient: HttpClient) : MangaScraper {

    override val sourceName = "WeebCentral"

    companion object {
        private const val FALLBACK_URL = "https://weebcentral.com"
        private const val BRAND_QUERY  = "weebcentral manga official site"
        private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /** Resolves (and caches for 6 h) the live WeebCentral domain via DDG. */
    private suspend fun liveBase(): String =
        resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)

    override suspend fun searchManga(query: String): List<UnifiedSearchResult> {
        return try {
            val base = liveBase()
            // WeebCentral search: GET /search?keyword=...
            val html = httpClient.get("$base/search") {
                parameter("keyword", query)
                header("User-Agent", UA)
                header("Referer", base)
            }.bodyAsText()

            val doc = Ksoup.parse(html)
            // WeebCentral lists results as <article> or <li> wrappers containing
            // an anchor whose href contains /series/.
            // The broad multi-selector covers both the current and any light redesign.
            doc.select("article, li.result, div.series-item, section.grid > div")
                .filter { it.select("a[href*=/series/]").isNotEmpty() }
                .mapNotNull { el ->
                    val link = el.select("a[href*=/series/]").firstOrNull()
                        ?: return@mapNotNull null
                    val href  = link.attr("href")
                    val title = el.select("h2, h3, strong, .title, .name").firstOrNull()?.text()
                        ?.ifBlank { link.attr("title") }
                        ?.ifBlank { link.text() }
                        ?: return@mapNotNull null
                    val cover = el.select("img[data-src], img[src]").firstOrNull()?.let {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }.orEmpty()
                    if (title.isBlank() || href.isBlank()) return@mapNotNull null
                    UnifiedSearchResult(
                        id   = href,
                        title = title,
                        coverUrl = absoluteMangaUrl(base, cover),
                        detailPageUrl = absoluteMangaUrl(base, href),
                        sourceName = sourceName,
                        isManga = true,
                        genre = el.select("a[href*=/genre/], span.genre").joinToString(", ") { it.text() }
                    )
                }
                .distinctBy { it.detailPageUrl }
                .take(30)
        } catch (e: Exception) {
            println("[WeebCentral] Search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter> {
        return try {
            val base = liveBase()
            val html = httpClient.get(mangaUrl) {
                header("User-Agent", UA)
                header("Referer", base)
            }.bodyAsText()

            val doc = Ksoup.parse(html)
            // WeebCentral chapter list: anchors whose href contains /chapters/
            // Fallback selectors catch common list/table patterns.
            doc.select(
                "a[href*=/chapters/], " +
                ".chapter-list a[href], " +
                "ul.chapters a[href], " +
                "div.chapter a[href]"
            )
                .filter { it.attr("href").isNotBlank() }
                .distinctBy { it.attr("href") }
                .reversed()   // oldest first → numbered ascending
                .mapIndexed { idx, el ->
                    val rawTitle = el.select("span, strong, .chapter-title, .title")
                        .firstOrNull()?.text()
                        ?.ifBlank { el.text().trim() }
                        ?: el.text().trim()
                    MangaChapter(
                        title = rawTitle.ifBlank { "Chapter ${idx + 1}" },
                        url   = absoluteMangaUrl(base, el.attr("href")),
                        chapterNumber = idx + 1
                    )
                }
        } catch (e: Exception) {
            println("[WeebCentral] Chapter fetch failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchMangaPages(chapterUrl: String): List<String> {
        return try {
            val base = liveBase()
            // Rewrite host in stored URL in case domain hopped since last search
            val effectiveUrl = rewriteUrlOrigin(chapterUrl, base)
            val html = httpClient.get(effectiveUrl) {
                header("User-Agent", UA)
                header("Referer", base)
            }.bodyAsText()

            val doc = Ksoup.parse(html)
            // Strategy 1: direct img tags in the reader container
            val fromImages = doc.select(
                "img.lazy, img[data-src], " +
                "#reader img, .reader img, " +
                "img[src*=/uploads/], img[src*=/manga/], img[src*=/images/]"
            )
                .map { it.attr("data-src").ifBlank { it.attr("src") } }
                .filter { it.isNotBlank() }
                .map { absoluteMangaUrl(base, it) }

            if (fromImages.isNotEmpty()) return fromImages

            // Strategy 2: JSON data embedded in a <script> (WeebCentral sometimes
            // uses a JSON array of image URLs in a window.__NUXT__ or similar blob)
            val scriptText = doc.select("script").firstOrNull { s ->
                s.html().contains(".jpg") || s.html().contains(".webp") || s.html().contains(".png")
            }?.html().orEmpty()
            if (scriptText.isNotBlank()) {
                val urls = Regex("""https?://[^\s"']+\.(?:jpg|jpeg|png|webp)[^\s"']*""")
                    .findAll(scriptText)
                    .map { it.value }
                    .filter { it.contains("/manga/") || it.contains("/uploads/") || it.contains("/images/") }
                    .toList()
                if (urls.isNotEmpty()) return urls
            }

            // Strategy 3: brute-force regex over the full HTML
            Regex("""https?://[^\s"']+\.(?:jpg|jpeg|png|webp)[^\s"']*""")
                .findAll(html)
                .map { it.value }
                .filter { !it.contains("logo") && !it.contains("avatar") && !it.contains("icon") }
                .toList()
        } catch (e: Exception) {
            println("[WeebCentral] Page fetch failed: ${e.message}")
            emptyList()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  MangaFire Scraper
// ─────────────────────────────────────────────────────────────────────────────
class MangaFireScraper(private val httpClient: HttpClient) : MangaScraper {

    override val sourceName = "MangaFire"
    private val baseUrl = "https://mangafire.to"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private suspend fun liveBase(): String =
        resolveLiveDomain(httpClient, "mangafire official", baseUrl)

    override suspend fun searchManga(query: String): List<UnifiedSearchResult> {
        return try {
            val base = liveBase()
            val html = httpClient.get("$base/filter?keyword=${query.replace(" ", "+")}") {
                header("User-Agent", userAgent)
                header("Referer", "$base/")
            }.bodyAsText()

            val doc = Ksoup.parse(html)
            val cards = doc.select("div.original div.inner, .item, .unit, .manga, .poster")
                .filter { it.select("a[href]").isNotEmpty() }
            cards.mapNotNull { el ->
                val link = el.select("a.title, a.name, a[href*=/manga/]").firstOrNull()
                    ?: return@mapNotNull null
                val href = link.attr("href")
                val cover = el.select("img").firstOrNull()?.attr("data-src").orEmpty()
                    .ifBlank { el.select("img").firstOrNull()?.attr("src").orEmpty() }
                val title = link.text().ifBlank { link.attr("title") }
                if (title.isBlank() || href.isBlank()) return@mapNotNull null
                UnifiedSearchResult(
                    id = href,
                    title = title,
                    coverUrl = absoluteMangaUrl(base, cover),
                    detailPageUrl = absoluteMangaUrl(base, href),
                    sourceName = sourceName,
                    isManga = true,
                    genre = el.select("a[href*=/genre/], .genres").joinToString(", ") { it.text() }
                )
            }.distinctBy { it.detailPageUrl }.take(30)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter> {
        return try {
            val base = liveBase()
            val html = httpClient.get(rewriteUrlOrigin(mangaUrl, base)) {
                header("User-Agent", userAgent)
                header("Referer", "$base/")
            }.bodyAsText()

            val doc = Ksoup.parse(html)
            doc.select("ul.chapters li a, .chapters a, a[href*=/read/], a[href*=chapter]")
                .filter { it.attr("href").isNotBlank() }
                .distinctBy { it.attr("href") }
                .reversed()
                .mapIndexed { idx, el ->
                MangaChapter(
                    title = el.text().trim().ifBlank { "Chapter ${idx + 1}" },
                    url = absoluteMangaUrl(base, el.attr("href")),
                    chapterNumber = idx + 1
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaPages(chapterUrl: String): List<String> {
        return try {
            val base = liveBase()
            val html = httpClient.get(rewriteUrlOrigin(chapterUrl, base)) {
                header("User-Agent", userAgent)
                header("Referer", "$base/")
            }.bodyAsText()

            val doc = Ksoup.parse(html)
            // Parse Webtoon pages directly from standard manga viewer markup
            doc.select("div#images-container img.page-image, div.reader-images img, img.page-image, img[src*=/manga/]")
                .map { it.attr("data-src").ifBlank { it.attr("src") } }
                .filter { it.isNotBlank() }
                .map { absoluteMangaUrl(base, it) }
                .ifEmpty {
                    Regex("""https?://[^\s"']+\.(?:jpg|jpeg|png|webp)[^\s"']*""")
                        .findAll(html)
                        .map { it.value }
                        .toList()
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Webtoon Scraper — vertical webtoon/manhwa pages
// ─────────────────────────────────────────────────────────────────────────────
class WebtoonScraper(private val httpClient: HttpClient) : MangaScraper {

    override val sourceName = "Webtoon"
    private val baseUrl = "https://www.webtoons.com"

    override suspend fun searchManga(query: String): List<UnifiedSearchResult> {
        return try {
            val html = httpClient.get("$baseUrl/en/search") {
                parameter("keyword", query)
            }.bodyAsText()
            val doc = Ksoup.parse(html)
            val links = doc.select("a[href*=title_no=], a[href*=/en/]")
                .filter { it.attr("href").contains("title_no=") }
                .distinctBy { it.attr("href").substringBefore("?").substringBefore("&") }
            links.mapNotNull { link ->
                val href = link.attr("href")
                val title = link.select("img").firstOrNull()?.attr("alt")
                    ?.ifBlank { link.select(".subj, .title").text() }
                    ?.ifBlank { link.text() }
                    ?: return@mapNotNull null
                UnifiedSearchResult(
                    id = href,
                    title = title,
                    coverUrl = absoluteMangaUrl(baseUrl, link.select("img").firstOrNull()?.attr("src").orEmpty()),
                    detailPageUrl = absoluteMangaUrl(baseUrl, href),
                    sourceName = sourceName,
                    isManga = true,
                    genre = "Webtoon"
                )
            }.filter { it.title.isNotBlank() }.take(30)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter> {
        return try {
            val html = httpClient.get(mangaUrl).bodyAsText()
            val doc = Ksoup.parse(html)
            doc.select("a[href*=episode_no=], li._episodeItem a")
                .filter { it.attr("href").contains("episode_no=") }
                .distinctBy { it.attr("href") }
                .mapIndexed { index, el ->
                    MangaChapter(
                        title = el.select(".subj, .tx, .title").text().ifBlank { el.text().ifBlank { "Episode ${index + 1}" } },
                        url = absoluteMangaUrl(baseUrl, el.attr("href")),
                        chapterNumber = index + 1
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaPages(chapterUrl: String): List<String> {
        return try {
            val html = httpClient.get(chapterUrl).bodyAsText()
            val doc = Ksoup.parse(html)
            doc.select("img._images, .viewer_img img, img[src*=webtoon], img[data-url]")
                .map { it.attr("data-url").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") } }
                .filter { it.isNotBlank() }
                .map { absoluteMangaUrl(baseUrl, it) }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

private fun absoluteMangaUrl(baseUrl: String, href: String): String {
    if (href.isBlank()) return ""
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    return baseUrl.trimEnd('/') + "/" + href.trimStart('/')
}
