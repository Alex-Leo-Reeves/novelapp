package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
//  WebNovel RapidAPI Source
// ─────────────────────────────────────────────────────────────────────────────
class WebNovelApiSource(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val apiHost: String = "webnovel.p.rapidapi.com"
) : NovelSource {

    override val sourceName = "WebNovel API"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeRun {
        val response = httpClient.get("https://$apiHost/search") {
            parameter("query", query)
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()

        val json = Json.parseToJsonElement(response).jsonObject
        val novels = json["novels"]?.jsonArray ?: return@safeRun emptyList()
        novels.map { el ->
            val obj = el.jsonObject
            UnifiedSearchResult(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                coverUrl = obj["cover"]?.jsonPrimitive?.content
                    ?: obj["coverUrl"]?.jsonPrimitive?.content ?: "",
                detailPageUrl = "webnovel-api://${obj["id"]?.jsonPrimitive?.content}",
                sourceName = sourceName,
                author = obj["author"]?.jsonPrimitive?.content ?: "",
                genre = obj["genre"]?.jsonPrimitive?.content ?: ""
            )
        }
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeRun {
        val novelId = novelUrl.removePrefix("webnovel-api://")
        val response = httpClient.get("https://$apiHost/novels/$novelId/chapters") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        val chapters = json["chapters"]?.jsonArray ?: return@safeRun emptyList()
        chapters.mapIndexed { index, el ->
            val obj = el.jsonObject
            Chapter(
                title = obj["title"]?.jsonPrimitive?.content ?: "Chapter ${index + 1}",
                url = "webnovel-api-chapter://$novelId/${obj["id"]?.jsonPrimitive?.content}",
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeRun {
        val parts = chapterUrl.removePrefix("webnovel-api-chapter://").split("/")
        val novelId = parts[0]
        val chapterId = parts[1]
        val response = httpClient.get("https://$apiHost/novels/$novelId/chapters/$chapterId") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        json["content"]?.jsonPrimitive?.content ?: ""
    }.ifEmpty { "Chapter content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeRun {
        val response = httpClient.get("https://$apiHost/novels/$page") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        val novels = json["novels"]?.jsonArray ?: return@safeRun emptyList()
        novels.map { el ->
            val obj = el.jsonObject
            UnifiedSearchResult(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                coverUrl = obj["cover"]?.jsonPrimitive?.content ?: "",
                detailPageUrl = "webnovel-api://${obj["id"]?.jsonPrimitive?.content}",
                sourceName = sourceName
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  LightNovelPub Scraper
// ─────────────────────────────────────────────────────────────────────────────
class LightNovelPubSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "LightNovelPub"
    private val baseUrl = "https://www.lightnovelpub.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeRun {
        val html = httpClient.get("$baseUrl/search?inputContent=${query.encodeURL()}").bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("li.novel-item").map { el ->
            UnifiedSearchResult(
                id = el.select("a").attr("href"),
                title = el.select("h4.novel-title").text(),
                coverUrl = el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") },
                detailPageUrl = "$baseUrl${el.select("a").attr("href")}",
                sourceName = sourceName,
                author = el.select("span.author").text()
            )
        }
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("ul.chapter-list li a").mapIndexed { index, el ->
            Chapter(
                title = el.text().ifEmpty { "Chapter ${index + 1}" },
                url = "$baseUrl${el.attr("href")}",
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("#chapter-container").text()
            .ifEmpty { doc.select("div.chapter-content").text() }
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeRun {
        val html = httpClient.get("$baseUrl/novel-list?pg=$page&sort=hot").bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("li.novel-item").map { el ->
            UnifiedSearchResult(
                id = el.select("a").attr("href"),
                title = el.select("h4.novel-title").text(),
                coverUrl = el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") },
                detailPageUrl = "$baseUrl${el.select("a").attr("href")}",
                sourceName = sourceName
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BoxNovel Scraper
// ─────────────────────────────────────────────────────────────────────────────
class BoxNovelSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "BoxNovel"
    private val baseUrl = "https://boxnovel.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeRun {
        val html = httpClient.get("$baseUrl/?s=${query.encodeURL()}&post_type=wp-manga").bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("div.c-tabs-item__content").map { el ->
            UnifiedSearchResult(
                id = el.select("a").attr("href"),
                title = el.select("h3.h4 a").text(),
                coverUrl = el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") },
                detailPageUrl = el.select("a").attr("href"),
                sourceName = sourceName,
                author = el.select("div.mg_author a").text()
            )
        }
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("ul.main li a").reversed().mapIndexed { index, el ->
            Chapter(
                title = el.select("p").first()?.text() ?: "Chapter ${index + 1}",
                url = el.attr("href"),
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("div.reading-content").text()
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeRun {
        val html = httpClient.get("$baseUrl/novel-list/?m_orderby=trending&page=$page").bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("div.page-item-detail").map { el ->
            UnifiedSearchResult(
                id = el.select("a").attr("href"),
                title = el.select("h3.h5 a").text(),
                coverUrl = el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") },
                detailPageUrl = el.select("a").attr("href"),
                sourceName = sourceName
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  RoyalRoad Source (community web-fiction, copyright-safe)
// ─────────────────────────────────────────────────────────────────────────────
class RoyalRoadSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "RoyalRoad"
    private val baseUrl = "https://www.royalroad.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeRun {
        val html = httpClient.get("$baseUrl/fictions/search?title=${query.encodeURL()}").bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("div.fiction-list-item").map { el ->
            val link = el.select("h2.fiction-title a")
            UnifiedSearchResult(
                id = link.attr("href"),
                title = link.text(),
                coverUrl = el.select("img").attr("src"),
                detailPageUrl = "$baseUrl${link.attr("href")}",
                sourceName = sourceName,
                genre = el.select("span.label").joinToString(", ") { it.text() }
            )
        }
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("tr.chapter-row td a").mapIndexed { index, el ->
            Chapter(
                title = el.text().trim(),
                url = "$baseUrl${el.attr("href")}",
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("div.chapter-content").text()
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeRun {
        val html = httpClient.get("$baseUrl/fictions/best-rated?page=$page").bodyAsText()
        val doc = Jsoup.parse(html)
        doc.select("div.fiction-list-item").map { el ->
            val link = el.select("h2.fiction-title a")
            UnifiedSearchResult(
                id = link.attr("href"),
                title = link.text(),
                coverUrl = el.select("img").attr("src"),
                detailPageUrl = "$baseUrl${link.attr("href")}",
                sourceName = sourceName
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helper extensions
// ─────────────────────────────────────────────────────────────────────────────
private fun String.encodeURL(): String = this.replace(" ", "+")

/** Safely execute [block], swallowing exceptions and returning emptyList(). */
private suspend fun <T> safeRun(block: suspend () -> List<T>): List<T> {
    return try {
        block()
    } catch (e: Exception) {
        println("[NovelScraper] Source failed: ${e.message}")
        emptyList()
    }
}

private suspend fun safeRun(block: suspend () -> String): String {
    return try {
        block()
    } catch (e: Exception) {
        println("[NovelScraper] Chapter fetch failed: ${e.message}")
        ""
    }
}
