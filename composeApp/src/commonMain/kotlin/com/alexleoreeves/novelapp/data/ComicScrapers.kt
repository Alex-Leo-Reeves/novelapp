package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.*

/**
 * Baseline interface for Western comic sources.
 * Mirrors MangaScraper but targets English-language comic publishers (DC, Marvel, Indie).
 */
interface ComicSource {
    val sourceName: String
    suspend fun search(query: String): List<UnifiedSearchResult>
    suspend fun fetchChapters(comicUrl: String): List<MangaChapter>
    suspend fun fetchPages(chapterUrl: String): List<String>
}

// ─────────────────────────────────────────────────────────────────────────────
//  NewComic.info Scraper — massive DC/Marvel/IDW/Dark Horse archive
//  DLE CMS site. Search via POST do=search. Detail pages show cover + download.
//  Category/genre pages serve as "popular" feed.
// ─────────────────────────────────────────────────────────────────────────────
class NewComicScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "NewComic"
    companion object {
        private const val BASE_URL = "https://newcomic.info"
        private val COMIC_CATEGORIES = listOf("/dc/", "/marvels/", "/idw/", "/dark-horse/", "/image-comics/", "/boom-studios/")
    }

    override suspend fun search(query: String): List<UnifiedSearchResult> {
        return try {
            val html = httpClient.submitForm(
                url = "$BASE_URL/index.php?do=search",
                formParameters = Parameters.build {
                    append("do", "search")
                    append("subaction", "search")
                    append("story", query)
                }
            ) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()
            parseSearchResults(html)
        } catch (e: Exception) {
            println("[NewComic] Search failed: ${e.message}")
            // Fallback: try GET
            try {
                val html2 = httpClient.get("$BASE_URL/index.php") {
                    parameter("do", "search")
                    parameter("subaction", "search")
                    parameter("story", query)
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                    header("Referer", "$BASE_URL/")
                }.bodyAsText()
                if (html2.isBlockedOrErrorPage()) return emptyList()
                parseSearchResults(html2)
            } catch (e2: Exception) {
                println("[NewComic] Search fallback also failed: ${e2.message}")
                emptyList()
            }
        }
    }

    private fun parseSearchResults(html: String): List<UnifiedSearchResult> {
        val doc = Ksoup.parse(html)
        return doc.select("div.short-story, div.short, div.movie-item, article.short").mapNotNull { el ->
            val titleEl = el.select("a.bigtext, a.news-title, h2 a[href], a[href*=${BASE_URL.substringAfter("://")}]").firstOrNull()
                ?: return@mapNotNull null
            val href = titleEl.attr("href").trim()
            val title = titleEl.attr("title").ifBlank { titleEl.text() }.decodeHtmlEntitiesLite()
            if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            val img = el.select("img").firstOrNull()
            val cover = listOf("data-original", "data-lazy-src", "data-src", "src")
                .firstNotNullOfOrNull { attr -> img?.attr(attr)?.takeIf { v -> v.isNotBlank() && v.contains("upload", ignoreCase = true) } } ?: ""
            UnifiedSearchResult(
                id = "nc_${href.substringAfter("://").replace("/", "_")}",
                title = title,
                coverUrl = absoluteComicUrl(BASE_URL, cover).replace("&#58;", ":"),
                detailPageUrl = absoluteComicUrl(BASE_URL, href),
                sourceName = sourceName,
                isComic = true,
                genre = el.select("div.cat, a[href*=/category/]").text().ifBlank { "Western Comic" }
            )
        }.distinctBy { it.detailPageUrl }.take(40)
    }

    /** Fetches a popular feed by iterating through category pages (DC, Marvel, etc). */
    suspend fun fetchPopular(page: Int = 1): List<UnifiedSearchResult> {
        val category = COMIC_CATEGORIES.getOrElse((page - 1).mod(COMIC_CATEGORIES.size)) { COMIC_CATEGORIES[0] }
        return try {
            val html = httpClient.get("$BASE_URL$category") {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()
            parseSearchResults(html)
        } catch (e: Exception) {
            println("[NewComic] Popular feed ($category) failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchChapters(comicUrl: String): List<MangaChapter> {
        return try {
            val html = httpClient.get(comicUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            val title = doc.select("h1, h2.title, meta[property=og:title]").firstOrNull()
                ?.attr("content")?.ifBlank { doc.title() }
                ?.decodeHtmlEntitiesLite() ?: "Comic Issue"

            // Each newcomer.info page is a single issue. Return it as one chapter.
            // Also look for related issues in the sidebar/navigation.
            val related = doc.select("div.related-news a[href], div.navigation a[href], div.next-prev a[href]")
                .filter { it.attr("href").isNotBlank() }
                .distinctBy { it.attr("href") }
                .mapIndexed { idx, el ->
                    MangaChapter(
                        title = el.text().decodeHtmlEntitiesLite().ifBlank { "Related Issue ${idx + 1}" },
                        url = absoluteComicUrl(BASE_URL, el.attr("href")),
                        chapterNumber = idx + 2
                    )
                }

            listOf(
                MangaChapter(
                    title = title,
                    url = comicUrl,
                    chapterNumber = 1
                )
            ) + related
        } catch (e: Exception) {
            println("[NewComic] Chapters failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchPages(chapterUrl: String): List<String> {
        return try {
            val html = httpClient.get(chapterUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            // newComic.info is a download site — extract cover + any embedded previews
            val images = mutableListOf<String>()

            // 1. Cover image from og:description or main content area
            val ogDesc = doc.select("meta[property=og:description]").attr("content")
            if (ogDesc.isNotBlank()) {
                Regex("""https?://[^\s"']+\.(?:jpg|jpeg|png|webp|gif)""")
                    .findAll(ogDesc.replace("&#58;", ":").replace("&", "&"))
                    .map { it.value }
                    .distinct()
                    .take(5)
                    .forEach { images.add(it) }
            }

            // 2. Full-size images in article content
            doc.select("div.full-text img, div.full-story img, div.article-content img, div.maincont img")
                .mapNotNull { el ->
                    listOf("data-original", "data-lazy-src", "data-src", "src")
                        .firstNotNullOfOrNull { el.attr(it).takeIf { v -> v.isNotBlank() } }
                }
                .filter { !it.contains("logo", ignoreCase = true) && !it.contains("avatar", ignoreCase = true) && !it.contains("icon", ignoreCase = true) }
                .map { absoluteComicUrl(BASE_URL, it) }
                .forEach { images.add(it) }

            images.distinct()
        } catch (e: Exception) {
            println("[NewComic] Pages failed: ${e.message}")
            emptyList()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ComicBookPlus.com Scraper — public domain Golden Age comics
//  Real inline reader with page images. Search via site form.
// ─────────────────────────────────────────────────────────────────────────────
class ComicBookPlusScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "ComicBookPlus"
    companion object {
        private const val BASE_URL = "https://comicbookplus.com"
    }

    override suspend fun search(query: String): List<UnifiedSearchResult> {
        return try {
            val html = httpClient.get("$BASE_URL/?search=$query") {
                parameter("search", query)
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            doc.select("div.comic-item, div.item, a[href*=/comic/], a[href*=/book/], table.listing tr").mapNotNull { el ->
                val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
                val href = link.attr("href")
                if (!href.contains("/comic") && !href.contains("/book") && !href.contains("cid=")) return@mapNotNull null
                val img = el.select("img").firstOrNull()
                val cover = listOf("data-original", "src", "data-src").firstNotNullOfOrNull {
                    img?.attr(it)?.takeIf { v -> v.isNotBlank() }
                } ?: ""
                val title = link.attr("title").ifBlank { link.text() }.ifBlank { img?.attr("alt").orEmpty() }.decodeHtmlEntitiesLite()
                if (title.isBlank() || title.length < 3 || title.isNavigationTitle()) return@mapNotNull null
                UnifiedSearchResult(
                    id = "cbp_${href.substringAfter("://").replace("/", "_")}",
                    title = title,
                    coverUrl = absoluteComicUrl(BASE_URL, cover),
                    detailPageUrl = absoluteComicUrl(BASE_URL, href),
                    sourceName = sourceName,
                    isComic = true,
                    genre = "Golden Age"
                )
            }.distinctBy { it.detailPageUrl }.take(30)
        } catch (e: Exception) {
            println("[ComicBookPlus] Search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchChapters(comicUrl: String): List<MangaChapter> {
        return try {
            val html = httpClient.get(comicUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            val title = doc.title().decodeHtmlEntitiesLite().ifBlank { "Comic" }
            listOf(
                MangaChapter(title = title, url = comicUrl, chapterNumber = 1)
            )
        } catch (e: Exception) {
            println("[ComicBookPlus] Chapters failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchPages(chapterUrl: String): List<String> {
        return try {
            val html = httpClient.get(chapterUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", "$BASE_URL/")
            }.bodyAsText()
            if (html.isBlockedOrErrorPage()) return emptyList()

            val doc = Ksoup.parse(html)
            // ComicBookPlus reader page images
            doc.select("img[src*=/pagecount/], img[src*=/CBJserver/], img.comicpage, div.reader img")
                .mapNotNull { el ->
                    listOf("src", "data-src", "data-original")
                        .firstNotNullOfOrNull { el.attr(it).takeIf { v -> v.isNotBlank() } }
                }
                .filter { !it.contains("thumb", ignoreCase = true) && !it.contains("logo", ignoreCase = true) }
                .map { absoluteComicUrl(BASE_URL, it) }
                .distinct()
        } catch (e: Exception) {
            println("[ComicBookPlus] Pages failed: ${e.message}")
            emptyList()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ViewComic Noscript Scraper — fingerprint bypass via meta refresh
//  When Ktor fetches viewcomic.com, the server returns a fingerprinting wall
//  with a <noscript> meta refresh. Following that bypass URL (with fp=-5)
//  gives access to the real site content.
// ─────────────────────────────────────────────────────────────────────────────
class ViewComicNoscriptScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "ViewComic"
    companion object {
        private const val BASE_URL = "https://viewcomic.com"
        private val FINGERPRINT_PATTERN = Regex("""<noscript><meta http-equiv="refresh" content="[^"]*URL=([^"]+)""")
        private val CLICK_LINK_PATTERN = Regex("""<a href='(http[^']+)'>Click here""")
    }

    /** Extract the bypass URL from the fingerprint page and follow it. */
    private suspend fun bypassFingerprint(url: String): String? {
        try {
            val fpPage = httpClient.get(url) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
            }.bodyAsText()

            // Try <noscript> meta refresh first (works without JS), then click-here link
            val bypassUrl = FINGERPRINT_PATTERN.find(fpPage)?.groupValues?.getOrNull(1)
                ?.replace("'", "'")?.replace("&", "&")
                ?: CLICK_LINK_PATTERN.find(fpPage)?.groupValues?.getOrNull(1)
                ?: return@bypassFingerprint null

            val realPage = httpClient.get(bypassUrl) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                header("Referer", url)
            }.bodyAsText()

            return if (realPage.isBlockedOrErrorPage()) null else realPage
        } catch (e: Exception) {
            println("[ViewComic] Bypass failed: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<UnifiedSearchResult> {
        return try {
            val html = bypassFingerprint("$BASE_URL/?s=${query.replace(" ", "+")}") ?: return emptyList()
            val doc = Ksoup.parse(html)
            doc.select("article, div.item, div.comic-item, div.post").mapNotNull { el ->
                val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
                val href = link.attr("href")
                val img = el.select("img").firstOrNull()
                val cover = listOf("data-original", "data-lazy-src", "data-src", "src")
                    .firstNotNullOfOrNull { attr -> img?.attr(attr)?.takeIf { v -> v.isNotBlank() && !v.contains("dummy", ignoreCase = true) && !v.contains("blank", ignoreCase = true) } } ?: ""
                val title = link.attr("title").ifBlank { link.text() }.ifBlank { img?.attr("alt").orEmpty() }.decodeHtmlEntitiesLite()
                if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
                UnifiedSearchResult(
                    id = "vc_${href.substringAfter("://").replace("/", "_")}",
                    title = title,
                    coverUrl = absoluteComicUrl(BASE_URL, cover),
                    detailPageUrl = absoluteComicUrl(BASE_URL, href),
                    sourceName = sourceName,
                    isComic = true,
                    genre = "Western Comic"
                )
            }.distinctBy { it.detailPageUrl }.take(30)
        } catch (e: Exception) {
            println("[ViewComic] Search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchChapters(comicUrl: String): List<MangaChapter> {
        return try {
            val html = bypassFingerprint(comicUrl) ?: return emptyList()
            val doc = Ksoup.parse(html)
            doc.select("li a[href*=/chapter-], ul.chapter-list a, div.chapter-list a, a[href*=/chapter/]")
                .filter { it.attr("href").isNotBlank() }
                .distinctBy { it.attr("href") }
                .mapIndexed { idx, el ->
                    MangaChapter(
                        title = el.text().decodeHtmlEntitiesLite().ifBlank { "Chapter ${idx + 1}" },
                        url = absoluteComicUrl(BASE_URL, el.attr("href")),
                        chapterNumber = idx + 1
                    )
                }
        } catch (e: Exception) {
            println("[ViewComic] Chapters failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchPages(chapterUrl: String): List<String> {
        return try {
            val html = bypassFingerprint(chapterUrl) ?: return emptyList()
            val doc = Ksoup.parse(html)
            doc.select("div.reader-area img, div.reader img, div.comic img, div.chapter-content img, img[src*=/uploads/], img[src*=/comics/]")
                .mapNotNull { el ->
                    listOf("data-original", "data-lazy-src", "data-src", "src")
                        .firstNotNullOfOrNull { el.attr(it).takeIf { v -> v.isNotBlank() } }
                }
                .filterNot { it.contains("logo", ignoreCase = true) || it.contains("avatar", ignoreCase = true) || it.contains("discord", ignoreCase = true) || it.contains("dummy", ignoreCase = true) || it.contains("blank", ignoreCase = true) || it.contains("spacer", ignoreCase = true) }
                .map { absoluteComicUrl(BASE_URL, it) }
                .distinct()
                .ifEmpty {
                    Regex("""https?://[^\s"'>]+\.(?:jpg|jpeg|png|webp)[^\s"'>]*""")
                        .findAll(html)
                        .map { it.value }
                        .filter { it.contains("/uploads/") || it.contains("/comics/") || it.contains("/wp-content/") }
                        .filterNot { it.contains("logo", ignoreCase = true) || it.contains("avatar", ignoreCase = true) || it.contains("discord", ignoreCase = true) }
                        .distinct()
                        .toList()
                }
        } catch (e: Exception) {
            println("[ViewComic] Pages failed: ${e.message}")
            emptyList()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun absoluteComicUrl(baseUrl: String, href: String): String {
    if (href.isBlank()) return ""
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    return baseUrl.trimEnd('/') + "/" + href.trimStart('/')
}

/** Sort chapters descending (newest first) — matching MangaChapter standard ordering. */
internal fun List<MangaChapter>.normalizedComicChapterOrder(): List<MangaChapter> =
    sortedByDescending { it.chapterNumber }
