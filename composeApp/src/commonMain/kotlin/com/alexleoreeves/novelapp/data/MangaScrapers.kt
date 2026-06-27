package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

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
        val now = System.currentTimeMillis()
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
//  MangaSee Scraper
// ─────────────────────────────────────────────────────────────────────────────
class MangaSeeScraper(private val httpClient: HttpClient) : MangaScraper {

    override val sourceName = "MangaSee"
    private val baseUrl = "https://mangasee123.com"

    override suspend fun searchManga(query: String): List<UnifiedSearchResult> {
        return try {
            // MangaSee uses a browser search form. We send a direct browser User-Agent
            val html = httpClient.get("$baseUrl/search/?name=${query.replace(" ", "%20")}") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }.bodyAsText()

            val doc = Jsoup.parse(html)
            doc.select("div.col-md-8 div.row").map { el ->
                val title = el.select("a.text-bold").text()
                val path = el.select("a.text-bold").attr("href")
                val cover = el.select("img").attr("src")
                UnifiedSearchResult(
                    id = path,
                    title = title,
                    coverUrl = cover,
                    detailPageUrl = "$baseUrl$path",
                    sourceName = sourceName,
                    isManga = true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter> {
        return try {
            val html = httpClient.get(mangaUrl) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }.bodyAsText()

            val doc = Jsoup.parse(html)
            doc.select("div.chapter-list a.list-group-item").reversed().mapIndexed { idx, el ->
                val title = el.select("span.chapterLabel").text().ifEmpty { el.text().trim() }
                MangaChapter(
                    title = title,
                    url = "$baseUrl${el.attr("href")}",
                    chapterNumber = idx + 1
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaPages(chapterUrl: String): List<String> {
        return try {
            val html = httpClient.get(chapterUrl) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                header("Referer", baseUrl)
            }.bodyAsText()

            // MangaSee embeds its images list inside a javascript variable.
            // We search for `val vm = this; vm.CurChapter = ...` and parse JSON.
            val script = Jsoup.parse(html).select("script").firstOrNull { it.html().contains("CurChapter") }?.html() ?: ""
            val jsonString = script.substringAfter("vm.CurChapter = ").substringBefore(";")
            val json = Json.parseToJsonElement(jsonString).jsonObject
            val pageCount = json["Page"]?.jsonPrimitive?.int ?: 1
            val domain = json["Directory"]?.jsonPrimitive?.content ?: ""

            // Reconstruct image URLs
            (1..pageCount).map { pageNum ->
                val formattedPage = pageNum.toString().padStart(3, '0')
                "https://temp.compsci88.com/manga/$domain/$formattedPage.png"
            }
        } catch (e: Exception) {
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

    override suspend fun searchManga(query: String): List<UnifiedSearchResult> {
        return try {
            val html = httpClient.get("$baseUrl/filter?keyword=${query.replace(" ", "+")}") {
                header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
            }.bodyAsText()

            val doc = Jsoup.parse(html)
            doc.select("div.original div.inner").map { el ->
                val link = el.select("a.title")
                val cover = el.select("img").attr("src")
                UnifiedSearchResult(
                    id = link.attr("href"),
                    title = link.text(),
                    coverUrl = cover,
                    detailPageUrl = "$baseUrl${link.attr("href")}",
                    sourceName = sourceName,
                    isManga = true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaChapters(mangaUrl: String): List<MangaChapter> {
        return try {
            val html = httpClient.get(mangaUrl) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }.bodyAsText()

            val doc = Jsoup.parse(html)
            doc.select("ul.chapters li a").reversed().mapIndexed { idx, el ->
                MangaChapter(
                    title = el.text().trim(),
                    url = "$baseUrl${el.attr("href")}",
                    chapterNumber = idx + 1
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaPages(chapterUrl: String): List<String> {
        return try {
            val html = httpClient.get(chapterUrl) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                header("Referer", baseUrl)
            }.bodyAsText()

            val doc = Jsoup.parse(html)
            // Parse Webtoon pages directly from standard manga viewer markup
            doc.select("div#images-container img.page-image").map { it.attr("src") }
                .ifEmpty {
                    doc.select("div.reader-images img").map { it.attr("src") }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
