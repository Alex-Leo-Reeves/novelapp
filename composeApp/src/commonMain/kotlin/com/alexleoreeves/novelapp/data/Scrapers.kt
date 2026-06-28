package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

data class DirectoryListing(
    val title: String,
    val url: String,
    val description: String = "",
    val source: String = ""
)

suspend fun scrapeDirectoryLinks(
    httpClient: HttpClient,
    baseUrl: String,
    query: String,
    linkSelector: String,
    searchPath: String = "/search",
    queryParameter: String = "q",
    userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    referer: String = baseUrl,
    mapper: (Element, String) -> DirectoryListing? = { link, origin ->
        val href = link.attr("href")
        val title = link.attr("title")
            .ifBlank { link.text() }
            .decodeHtmlEntitiesLite()

        if (href.isBlank() || title.isBlank()) {
            null
        } else {
            val parentText = link.parent()?.text().orEmpty()
            DirectoryListing(
                title = title,
                url = absoluteUrl(origin, href),
                description = parentText.removePrefix(title).trim(),
                source = origin
            )
        }
    }
): List<DirectoryListing> {
    val origin = baseUrl.trimEnd('/')
    return safeListRun {
        val html = httpClient.get("$origin/${searchPath.trimStart('/')}") {
            parameter(queryParameter, query)
            header("User-Agent", userAgent)
            header("Referer", referer)
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.bodyAsText()

        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()

        Ksoup.parse(html)
            .select(linkSelector)
            .mapNotNull { mapper(it, origin) }
            .distinctBy { it.url }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  WebNovel RapidAPI Source
// ─────────────────────────────────────────────────────────────────────────────
class WebNovelApiSource(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val apiHost: String = "webnovel.p.rapidapi.com"
) : NovelSource {

    override val sourceName = "WebNovel API"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val response = httpClient.get("https://$apiHost/search") {
            parameter("query", query)
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()

        val json = Json.parseToJsonElement(response).jsonObject
        val novels = json["novels"]?.jsonArray ?: return@safeListRun emptyList()
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

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val novelId = novelUrl.removePrefix("webnovel-api://")
        val response = httpClient.get("https://$apiHost/novels/$novelId/chapters") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        val chapters = json["chapters"]?.jsonArray ?: return@safeListRun emptyList()
        chapters.mapIndexed { index, el ->
            val obj = el.jsonObject
            Chapter(
                title = obj["title"]?.jsonPrimitive?.content ?: "Chapter ${index + 1}",
                url = "webnovel-api-chapter://$novelId/${obj["id"]?.jsonPrimitive?.content}",
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
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

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val response = httpClient.get("https://$apiHost/novels/$page") {
            headers {
                append("X-RapidAPI-Key", apiKey)
                append("X-RapidAPI-Host", apiHost)
            }
        }.bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        val novels = json["novels"]?.jsonArray ?: return@safeListRun emptyList()
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
//  FreeWebNovel Scraper — western/chinese/korean web novels
// ─────────────────────────────────────────────────────────────────────────────
class FreeWebNovelSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "FreeWebNovel"
    private val baseUrl = "https://freewebnovel.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.submitForm(
            url = "$baseUrl/search",
            formParameters = Parameters.build { append("searchkey", query) }
        ).bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val doc = Ksoup.parse(html)
        doc.select(".li-row, .book, .item, .novel-item, .book-item, .novel")
            .filter { it.select("a[href]").isNotEmpty() && it.text().length > 3 }
            .mapNotNull { el ->
            val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            if (!isFreeWebNovelBookHref(href)) return@mapNotNull null
            val title = el.select("h3, h4, .tit, .title, a").firstOrNull()?.text()
                ?.ifBlank { link.attr("title") }
                ?.ifBlank { link.text() }
                ?.decodeHtmlEntitiesLite()
                ?: return@mapNotNull null
            if (title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = href,
                title = title,
                coverUrl = absoluteUrl(baseUrl, el.select("img").firstOrNull()?.attr("data-src").orEmpty()
                    .ifBlank { el.select("img").firstOrNull()?.attr("src").orEmpty() }),
                detailPageUrl = absoluteUrl(baseUrl, href),
                sourceName = sourceName,
                author = el.select(".author, .s1, span:contains(Author)").firstOrNull()?.text().orEmpty(),
                genre = el.select(".genre, .s2, a[href*=/genre/]").joinToString(", ") { it.text() }
            )
        }.distinctBy { it.detailPageUrl }.take(30)
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val doc = Ksoup.parse(html)
        val novelSlug = novelUrl.substringAfterLast("/")
            .substringBefore("?")
            .removeSuffix(".html")
            .trim()
        val links = doc.select("a[href*=/chapter-], a[href*=/chapter/], ul.chapter-list a, .chapter-list a")
            .filter { it.attr("href").isNotBlank() }
            .filter { el ->
                val href = el.attr("href")
                val title = el.text().trim()
                !title.isNavigationTitle() &&
                    (novelSlug.isBlank() || href.contains("/$novelSlug/") || href.contains("$novelSlug/chapter")) &&
                    (href.contains("chapter", ignoreCase = true) || title.contains("chapter", ignoreCase = true))
            }
            .distinctBy { it.attr("href") }
        links.mapIndexed { index, el ->
            val href = el.attr("href")
            Chapter(
                title = el.text().decodeHtmlEntitiesLite().ifBlank { "Chapter ${index + 1}" },
                url = absoluteUrl(baseUrl, href),
                chapterNumber = parseChapterNumber(href, index + 1)
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeStringRun ""
        val doc = Ksoup.parse(html)
        doc.select("#chapter-content, .chapter-content, .txt, .cha-words, article")
            .firstOrNull()
            ?.text()
            .orEmpty()
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/sort/most-popular?page=$page").bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val doc = Ksoup.parse(html)
        doc.select(".li-row, .book, .item, .novel-item, .book-item, .novel")
            .filter { it.select("a[href]").isNotEmpty() && it.text().length > 3 }
            .mapNotNull { el ->
            val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            if (!isFreeWebNovelBookHref(href)) return@mapNotNull null
            val title = el.select("h3, h4, .tit, .title, a").firstOrNull()?.text()
                ?.ifBlank { link.attr("title") }
                ?.ifBlank { link.text() }
                ?.decodeHtmlEntitiesLite()
                ?: return@mapNotNull null
            if (title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = href,
                title = title,
                coverUrl = absoluteUrl(baseUrl, el.select("img").firstOrNull()?.attr("data-src").orEmpty()
                    .ifBlank { el.select("img").firstOrNull()?.attr("src").orEmpty() }),
                detailPageUrl = absoluteUrl(baseUrl, href),
                sourceName = sourceName,
                genre = el.select("a[href*=/genre/]").joinToString(", ") { it.text() }
            )
        }.distinctBy { it.detailPageUrl }.take(30)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  LightNovelPub Scraper
// ─────────────────────────────────────────────────────────────────────────────
class LightNovelPubSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "LightNovelPub"
    private val baseUrl = "https://www.lightnovelpub.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/search?inputContent=${query.encodeURL()}").bodyAsText()
        val doc = Ksoup.parse(html)
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

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("ul.chapter-list li a").mapIndexed { index, el ->
            Chapter(
                title = el.text().ifEmpty { "Chapter ${index + 1}" },
                url = "$baseUrl${el.attr("href")}",
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("#chapter-container").text()
            .ifEmpty { doc.select("div.chapter-content").text() }
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/novel-list?pg=$page&sort=hot").bodyAsText()
        val doc = Ksoup.parse(html)
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

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/?s=${query.encodeURL()}&post_type=wp-manga").bodyAsText()
        val doc = Ksoup.parse(html)
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

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("ul.main li a").reversed().mapIndexed { index, el ->
            Chapter(
                title = el.select("p").first()?.text() ?: "Chapter ${index + 1}",
                url = el.attr("href"),
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("div.reading-content").text()
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/novel-list/?m_orderby=trending&page=$page").bodyAsText()
        val doc = Ksoup.parse(html)
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

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/fictions/search?title=${query.encodeURL()}").bodyAsText()
        val doc = Ksoup.parse(html)
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

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("tr.chapter-row td a").mapIndexed { index, el ->
            Chapter(
                title = el.text().trim(),
                url = "$baseUrl${el.attr("href")}",
                chapterNumber = index + 1
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("div.chapter-content").text()
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/fictions/best-rated?page=$page").bodyAsText()
        val doc = Ksoup.parse(html)
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

private fun absoluteUrl(baseUrl: String, href: String): String {
    if (href.isBlank()) return ""
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    return baseUrl.trimEnd('/') + "/" + href.trimStart('/')
}

private fun isFreeWebNovelBookHref(href: String): Boolean {
    val clean = href.substringBefore("?").trim()
    if (clean.isBlank()) return false
    val lower = clean.lowercase()
    if (lower.contains("/genre/") ||
        lower.contains("/tag/") ||
        lower.contains("/category/") ||
        lower.contains("/chapter") ||
        lower.contains("javascript:")
    ) return false
    return lower.endsWith(".html")
}

private fun parseChapterNumber(href: String, fallback: Int): Int =
    Regex("""chapter[-/](\d+)""", RegexOption.IGNORE_CASE)
        .find(href)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: fallback

/** Safely execute [block], swallowing exceptions and returning emptyList(). */
private suspend fun <T> safeListRun(block: suspend () -> List<T>): List<T> {
    return try {
        block()
    } catch (e: Exception) {
        println("[NovelScraper] Source failed: ${e.message}")
        emptyList()
    }
}

private suspend fun safeStringRun(block: suspend () -> String): String {
    return try {
        block()
    } catch (e: Exception) {
        println("[NovelScraper] Chapter fetch failed: ${e.message}")
        ""
    }
}
