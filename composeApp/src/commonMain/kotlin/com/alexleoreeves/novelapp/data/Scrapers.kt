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
    private val baseUrl = "https://lightnovelpub.me"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/search?inputContent=${query.encodeURL()}").bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("li.novel-item, .novel-item, .book-item, .row")
            .mapNotNull { el ->
                val link = el.select("a[href*=/book/], a[href*=/novel/]").firstOrNull()
                    ?: return@mapNotNull null
                val href = link.attr("href")
                val title = el.select("h4.novel-title, .novel-title, h3, h4, a").firstOrNull()?.text()
                    ?.decodeHtmlEntitiesLite()
                    .orEmpty()
                if (title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = href,
                title = title,
                coverUrl = absoluteUrl(baseUrl, el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") }),
                detailPageUrl = absoluteUrl(baseUrl, href),
                sourceName = sourceName,
                author = el.select("span.author").text()
            )
        }.distinctBy { it.detailPageUrl }.take(30)
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl).bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val doc = Ksoup.parse(html)
        val origin = originFromUrl(novelUrl).ifBlank { baseUrl }
        val explicit = doc.select("ul.chapter-list li a, .chapter-list a, a[href*=chapter-]")
            .mapNotNull { el ->
                val href = el.attr("href")
                val chapterNumber = parseChapterNumber(href, -1)
                if (href.isBlank() || chapterNumber <= 0) return@mapNotNull null
            Chapter(
                    title = el.text().decodeHtmlEntitiesLite().ifEmpty { "Chapter $chapterNumber" },
                    url = absoluteUrl(origin, href),
                    chapterNumber = chapterNumber
            )
        }.distinctBy { it.url }.sortedBy { it.chapterNumber }
        if (explicit.size > 2) return@safeListRun explicit

        val first = explicit.minOfOrNull { it.chapterNumber } ?: 1
        val last = explicit.maxOfOrNull { it.chapterNumber }
            ?: Regex("""chapter-(\d+)""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull()
            ?: return@safeListRun explicit
        val novelPath = novelUrl.substringAfter(origin).trimEnd('/')
        (first..last).map { number ->
            Chapter(
                title = "Chapter $number",
                url = "$origin$novelPath/chapter-$number",
                chapterNumber = number
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl).bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeStringRun ""
        val doc = Ksoup.parse(html)
        doc.select("#chapter-container, .chapter-content, .chapter-text, article").text()
            .ifEmpty { doc.select("div.chapter-content").text() }
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/list/most-popular-novels/?page=$page").bodyAsText()
        val doc = Ksoup.parse(html)
        doc.select("li.novel-item, .novel-item, .book-item").mapNotNull { el ->
            val link = el.select("a[href*=/book/], a[href*=/novel/]").firstOrNull()
                ?: return@mapNotNull null
            val href = link.attr("href")
            val title = el.select("h4.novel-title, .novel-title, h3, h4, a").firstOrNull()?.text()
                ?.decodeHtmlEntitiesLite()
                .orEmpty()
            if (title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = href,
                title = title,
                coverUrl = absoluteUrl(baseUrl, el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") }),
                detailPageUrl = absoluteUrl(baseUrl, href),
                sourceName = sourceName
            )
        }.distinctBy { it.detailPageUrl }.take(30)
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
//  Wuxiaworld Source — strong xianxia/wuxia coverage
// ─────────────────────────────────────────────────────────────────────────────
class WuxiaWorldSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "Wuxiaworld"
    private val baseUrl = "https://www.wuxiaworld.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val response = httpClient.get("$baseUrl/api/novels/search") {
            parameter("query", query)
            header("Referer", "$baseUrl/novels")
            header("Accept", "application/json,text/plain,*/*")
        }.bodyAsText()
        val root = Json.parseToJsonElement(response).jsonObject
        val items = root["items"]?.jsonArray ?: return@safeListRun emptyList()
        items.mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().decodeHtmlEntitiesLite()
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isBlank() || slug.isBlank()) return@mapNotNull null
            UnifiedSearchResult(
                id = "wuxiaworld_$slug",
                title = name,
                coverUrl = obj["coverUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                detailPageUrl = "$baseUrl/novel/$slug",
                sourceName = sourceName,
                author = obj["authorName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                genre = obj["genres"]?.jsonArray?.joinToString(", ") {
                    it.jsonPrimitive.contentOrNull.orEmpty()
                }.orEmpty(),
                synopsis = obj["synopsis"]?.jsonPrimitive?.contentOrNull.orEmpty().htmlToPlainText()
            )
        }.take(30)
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl) {
            header("Referer", baseUrl)
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()

        val slug = novelUrl.substringAfter("/novel/")
            .substringBefore("/")
            .substringBefore("?")
            .trim()
        if (slug.isBlank()) return@safeListRun emptyList()

        val doc = Ksoup.parse(html)
        val linkedChapters = doc.select("a[href*=/novel/$slug/]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val number = parseChapterNumber(href, -1)
                if (number <= 0) return@mapNotNull null
                Chapter(
                    title = link.text().decodeHtmlEntitiesLite().ifBlank { "Chapter $number" },
                    url = absoluteUrl(baseUrl, href),
                    chapterNumber = number
                )
            }
            .distinctBy { it.url }
            .sortedBy { it.chapterNumber }
        if (linkedChapters.size > 1) return@safeListRun linkedChapters

        val prefix = html.extractWuxiaWorldChapterPrefix()
        val latestNumber = html.extractWuxiaWorldLatestChapter()
        if (prefix.isBlank() || latestNumber <= 0) return@safeListRun linkedChapters

        (1..latestNumber).map { number ->
            Chapter(
                title = "Chapter $number",
                url = "$baseUrl/novel/$slug/$prefix-chapter-$number",
                chapterNumber = number
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl) {
            header("Referer", chapterUrl.substringBeforeLast("/", baseUrl))
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeStringRun ""
        val doc = Ksoup.parse(html)
        val selectorText = doc
            .select(".chapter-content, .chapter-body, .fr-view, article")
            .firstOrNull()
            ?.text()
            .orEmpty()
        if (selectorText.length > 200) return@safeStringRun selectorText

        html.extractWuxiaWorldChapterContent()
    }.ifEmpty { "This chapter is unavailable from Wuxiaworld right now. It may be locked or blocked by the provider." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        listOf("renegade immortal", "a will eternal", "i shall seal the heavens", "against the gods")
            .drop(((page - 1).coerceAtLeast(0)) % 2)
            .take(2)
            .flatMap { search(it) }
            .distinctBy { it.detailPageUrl }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ReadNovelFull Source — broad translated web novel coverage
// ─────────────────────────────────────────────────────────────────────────────
class ReadNovelFullSource(private val httpClient: HttpClient) : NovelSource {

    override val sourceName = "ReadNovelFull"
    private val baseUrl = "https://readnovelfull.com"

    override suspend fun search(query: String): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/novel-list/search") {
            parameter("keyword", query)
            header("Referer", baseUrl)
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val doc = Ksoup.parse(html)
        val rows = doc.select("#list-page .col-novel-main .list-novel > .row, #list-page .archive .list-novel > .row")
            .let { mainRows ->
                if (mainRows.isNotEmpty()) mainRows else doc.select(".list-novel > .row")
            }
        rows
            .mapNotNull { el ->
                val link = el.select("h3.truyen-title a[href], h3.novel-title a[href], .truyen-title a[href], .novel-title a[href]")
                    .firstOrNull()
                    ?: return@mapNotNull null
                val href = link.attr("href")
                val title = link.attr("title")
                    .ifBlank { link.text() }
                    .decodeHtmlEntitiesLite()
                if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
                val absolute = absoluteUrl(baseUrl, href)
                if (!absolute.endsWith(".html")) return@mapNotNull null
                UnifiedSearchResult(
                    id = "readnovelfull_${href.trim('/')}",
                    title = title,
                    coverUrl = absoluteUrl(
                        baseUrl,
                        el.select("img").firstOrNull()?.attr("data-src").orEmpty()
                            .ifBlank { el.select("img").firstOrNull()?.attr("src").orEmpty() }
                    ),
                    detailPageUrl = absolute,
                    sourceName = sourceName,
                    author = el.select(".author a, span.author, .info a[href*=/authors/]").joinToString(", ") { it.text() },
                    genre = el.select(".genre a, a[href*=/genres/]").joinToString(", ") { it.text() },
                    synopsis = el.select(".desc, .description").firstOrNull()?.text().orEmpty()
                )
            }
            .distinctBy { it.detailPageUrl }
            .take(30)
    }

    override suspend fun fetchChapters(novelUrl: String): List<Chapter> = safeListRun {
        val html = httpClient.get(novelUrl) {
            header("Referer", baseUrl)
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val novelId = Regex("""data-novel-id=["'](\d+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val archiveHtml = if (novelId.isNotBlank()) {
            runCatching {
                httpClient.get("$baseUrl/ajax/chapter-archive") {
                    parameter("novelId", novelId)
                    header("Referer", novelUrl)
                    header("Accept", "text/html,*/*;q=0.8")
                }.bodyAsText()
            }.getOrDefault("")
        } else {
            ""
        }
        val chapterHtml = archiveHtml
            .takeIf { it.isNotBlank() && !it.isBlockedOrErrorPage() && it.contains("list-chapter", ignoreCase = true) }
            ?: html
        val doc = Ksoup.parse(chapterHtml)
        doc.select("#list-chapter a[href], .list-chapter a[href], a[href*=/chapter-][href$=.html]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val title = link.attr("title")
                    .ifBlank { link.text() }
                    .decodeHtmlEntitiesLite()
                if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
                val chapterNumber = parseReadNovelFullChapterNumber(href, title)
                Chapter(
                    title = title.ifBlank { "Chapter $chapterNumber" },
                    url = absoluteUrl(baseUrl, href),
                    chapterNumber = chapterNumber
                )
            }
            .distinctBy { it.url }
            .sortedBy { it.chapterNumber }
    }

    override suspend fun fetchChapterText(chapterUrl: String): String = safeStringRun {
        val html = httpClient.get(chapterUrl) {
            header("Referer", chapterUrl.substringBeforeLast("/", baseUrl))
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeStringRun ""
        val doc = Ksoup.parse(html)
        val content = doc.select("#chr-content, .chr-c, .chapter-c, #chapter-content, .chapter-content, article")
            .firstOrNull()
        content
            ?.select("script, style, .ads, ins")
            ?.forEach { it.remove() }
        content
            ?.select("p")
            ?.map { it.text().decodeHtmlEntitiesLite() }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n\n")
            .orEmpty()
            .ifBlank {
                content?.text().orEmpty()
            }
    }.ifEmpty { "Content unavailable." }

    override suspend fun fetchPopular(page: Int): List<UnifiedSearchResult> = safeListRun {
        val html = httpClient.get("$baseUrl/novel-list/hot-novel") {
            parameter("page", page)
            header("Referer", baseUrl)
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return@safeListRun emptyList()
        val doc = Ksoup.parse(html)
        val rows = doc.select("#list-page .col-novel-main .list-novel > .row, #list-page .archive .list-novel > .row, .index-novel .item")
            .let { mainRows ->
                if (mainRows.isNotEmpty()) mainRows else doc.select(".list-novel > .row, .index-novel .item")
            }
        rows
            .mapNotNull { el ->
                val link = el.select("h3.novel-title a[href], h3.truyen-title a[href], .s-title h3 a[href], .title a[href]").firstOrNull()
                    ?: return@mapNotNull null
                val href = link.attr("href")
                val title = link.attr("title").ifBlank { link.text() }.decodeHtmlEntitiesLite()
                if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
                UnifiedSearchResult(
                    id = "readnovelfull_${href.trim('/')}",
                    title = title,
                    coverUrl = absoluteUrl(
                        baseUrl,
                        el.select("img").firstOrNull()?.attr("data-src").orEmpty()
                            .ifBlank { el.select("img").firstOrNull()?.attr("src").orEmpty() }
                    ),
                    detailPageUrl = absoluteUrl(baseUrl, href),
                    sourceName = sourceName,
                    genre = el.select(".genre a, a[href*=/genres/]").joinToString(", ") { it.text() }
                )
            }
            .distinctBy { it.detailPageUrl }
            .take(30)
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

private fun originFromUrl(url: String): String {
    val match = Regex("""^(https?://[^/]+)""").find(url)
    return match?.groupValues?.getOrNull(1).orEmpty()
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

private fun parseReadNovelFullChapterNumber(href: String, title: String): Int {
    // Priority 1: Extract from the display title (most reliable — "Chapter 47")
    val fromTitle = Regex("""(?:chapter|chap|ch\.?)\s*#?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(title)
        ?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
    if (fromTitle != null && fromTitle > 0) return fromTitle

    // Priority 2: From URL path segment — only if it looks like a clean chapter slug
    // e.g. /chapter-47.html or /my-vampire-system-chapter-47.html
    val fromUrl = Regex("""chapter[-_](\d+)(?:[^/\d]|$)""", RegexOption.IGNORE_CASE)
        .find(href)
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
    // Clamp to avoid huge outlier numbers embedded in novel-id segments
    if (fromUrl != null && fromUrl in 1..9999) return fromUrl

    // Priority 3: Leading digit in title
    val leadingDigit = Regex("""^\s*(\d+)\b""")
        .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (leadingDigit != null && leadingDigit > 0) return leadingDigit

    return Int.MAX_VALUE
}


private fun String.htmlToPlainText(): String =
    Ksoup.parse(this.decodeHtmlEntitiesLite()).text().decodeHtmlEntitiesLite()

private fun String.extractWuxiaWorldChapterPrefix(): String {
    val firstSlug = Regex(""""firstChapter":\{[\s\S]*?"slug":"([^"]+)"""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    if (firstSlug.contains("chapter-", ignoreCase = true)) {
        return firstSlug.substringBefore("chapter-").trimEnd('-')
    }
    val abbreviation = Regex(""""abbreviation":"([^"]+)"""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "")
    return abbreviation.ifBlank { firstSlug.substringBefore("-chapter-") }
}

private fun String.extractWuxiaWorldLatestChapter(): Int {
    val latest = Regex(""""latestChapter":\{[\s\S]*?"number":\{"units":(\d+)""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    if (latest != null && latest > 0) return latest
    return Regex(""""chapterCount":\{"value":(\d+)""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0
}

private fun String.extractWuxiaWorldChapterContent(): String {
    return Regex(""""content":"((?:\\.|[^"\\])*)"""")
        .findAll(this)
        .mapNotNull { match -> decodeJsonString(match.groupValues[1]) }
        .map { html ->
            val doc = Ksoup.parse(html)
            val paragraphs = doc.select("p").map { it.text().decodeHtmlEntitiesLite() }
                .filter { it.isNotBlank() }
            paragraphs.ifEmpty { listOf(doc.text().decodeHtmlEntitiesLite()) }
                .joinToString("\n\n")
        }
        .firstOrNull { it.length > 400 }
        .orEmpty()
}

private fun decodeJsonString(raw: String): String? =
    runCatching { Json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }
        .getOrNull()

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
