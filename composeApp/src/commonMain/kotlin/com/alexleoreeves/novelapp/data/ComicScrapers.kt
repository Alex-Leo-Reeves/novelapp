package com.alexleoreeves.novelapp.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import com.fleeksoft.ksoup.Ksoup

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
//  ZipComic.com Scraper — massive DC, Marvel, Indie archive
//  Loads entire issue on a single page; easy <img> parsing.
//  Domain auto-heals via resolveLiveDomain().
// ─────────────────────────────────────────────────────────────────────────────
class ZipComicScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "ZipComic"
    companion object {
        private const val FALLBACK_URL = "https://zipcomic.com"
        private const val BRAND_QUERY = "zipcomic comics official"
    }

    private suspend fun liveBase(): String =
        resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)

    override suspend fun search(query: String): List<UnifiedSearchResult> {
        return try {
        val base = liveBase()
        val html = httpClient.get("$base/search") {
            parameter("q", query)
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        doc.select("div.item, div.comic-item, div.post, article.grid-item").mapNotNull { el ->
            val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            val img = el.select("img").firstOrNull()
            val cover = img?.attr("data-src")?.orEmpty().orEmpty().ifBlank { img?.attr("src").orEmpty() }
            val title = link.attr("title").ifBlank { link.text() }
                .ifBlank { img?.attr("alt").orEmpty() }
                .decodeHtmlEntitiesLite()
            if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = "zipcomic_${href.substringAfter("://").replace("/", "_")}",
                title = title,
                coverUrl = absoluteComicUrl(base, cover),
                detailPageUrl = absoluteComicUrl(base, href),
                sourceName = sourceName,
                isComic = true,
                genre = el.select("span.genre, .cat:not(a)").joinToString(", ") { it.text() }.ifBlank { "Western Comic" }
            )
        }.distinctBy { it.detailPageUrl }.take(30)
        } catch (e: Exception) {
        println("[ZipComic] Search failed: ${e.message}")
        emptyList()
    }
    }

    override suspend fun fetchChapters(comicUrl: String): List<MangaChapter> {
        return try {
        val base = liveBase()
        val effectiveUrl = rewriteUrlOrigin(comicUrl, base)
        val html = httpClient.get(effectiveUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        // ZipComic displays issues as a list. Each link to a chapter/issue.
        doc.select("ul.chapters li a, div.chapter-list a, a[href*=/issue/], a[href*=/chapter/], a[href*=/comic/]")
            .filter { el ->
                val h = el.attr("href")
                h.isNotBlank() && (h.contains("/issue/") || h.contains("/chapter/") || !h.contains("/search"))
            }
            .distinctBy { it.attr("href") }
            .mapIndexed { idx, el ->
                val href = absoluteComicUrl(base, el.attr("href"))
                val title = el.text().decodeHtmlEntitiesLite().ifBlank { "Issue ${idx + 1}" }
                MangaChapter(
                    title = title,
                    url = href,
                    chapterNumber = idx + 1
                )
            }.normalizedComicChapterOrder()
        } catch (e: Exception) {
        println("[ZipComic] Chapters failed: ${e.message}")
        emptyList()
    }
    }

    override suspend fun fetchPages(chapterUrl: String): List<String> {
        return try {
        val base = liveBase()
        val effectiveUrl = rewriteUrlOrigin(chapterUrl, base)
        val html = httpClient.get(effectiveUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        // ZipComic loads entire issue on a single page — all <img> tags
        doc.select("div.reader-area img, div.reader img, div.comic-pages img, img[src*=/uploads/], img[src*=/comics/], img[src*=/images/]")
            .map { it.attr("data-src").ifBlank { it.attr("src") } }
            .filter { it.isNotBlank() && !it.contains("logo", ignoreCase = true) && !it.contains("avatar", ignoreCase = true) }
            .map { absoluteComicUrl(base, it) }
            .distinct()
            .ifEmpty {
                Regex("""https?://[^\s"'>]+\.(?:jpg|jpeg|png|webp)[^\s"'>]*""")
                    .findAll(html)
                    .map { it.value }
                    .filter { it.contains("/uploads/") || it.contains("/comics/") || it.contains("/images/") }
                    .toList()
            }
        } catch (e: Exception) {
        println("[ZipComic] Pages failed: ${e.message}")
        emptyList()
    }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ReadAllComics.com Scraper — backup mirror for major comics
//  Loads entire issue sequentially using a scrollable page container.
//  Domain auto-heals via resolveLiveDomain().
//  Image extraction targets: div.scrolling-box img
// ─────────────────────────────────────────────────────────────────────────────
class ReadAllComicsScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "ReadAllComics"
    companion object {
        private const val FALLBACK_URL = "https://readallcomics.com"
        private const val BRAND_QUERY = "read all comics official"
    }

    private suspend fun liveBase(): String =
        resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)

    override suspend fun search(query: String): List<UnifiedSearchResult> {
        return try {
        val base = liveBase()
        val html = httpClient.get("$base/?s=${query.replace(" ", "+")}") {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        doc.select("article, div.post, div.result-item, li.search-item").mapNotNull { el ->
            val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank() || href.contains("/page/") || href.contains("/tag/")) return@mapNotNull null
            val img = el.select("img").firstOrNull()
            val cover = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }
            val title = link.attr("title").ifBlank { link.text() }
                .ifBlank { img?.attr("alt").orEmpty() }
                .decodeHtmlEntitiesLite()
            if (title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = "rac_${href.substringAfter("://").replace("/", "_")}",
                title = title,
                coverUrl = absoluteComicUrl(base, cover),
                detailPageUrl = absoluteComicUrl(base, href),
                sourceName = sourceName,
                isComic = true,
                genre = "Western Comic"
            )
        }.distinctBy { it.detailPageUrl }.take(30)
        } catch (e: Exception) {
        println("[ReadAllComics] Search failed: ${e.message}")
        emptyList()
    }
    }

    override suspend fun fetchChapters(comicUrl: String): List<MangaChapter> {
        return try {
        val base = liveBase()
        val effectiveUrl = rewriteUrlOrigin(comicUrl, base)
        val html = httpClient.get(effectiveUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        doc.select("ul.list-chapter a, div.chapter-list a, a[href*=/issue/], a[href*=/chapter/]")
            .filter { it.attr("href").isNotBlank() }
            .distinctBy { it.attr("href") }
            .mapIndexed { idx, el ->
                MangaChapter(
                    title = el.text().decodeHtmlEntitiesLite().ifBlank { "Issue ${idx + 1}" },
                    url = absoluteComicUrl(base, el.attr("href")),
                    chapterNumber = idx + 1
                )
            }.normalizedComicChapterOrder()
        } catch (e: Exception) {
        println("[ReadAllComics] Chapters failed: ${e.message}")
        emptyList()
    }
    }

    override suspend fun fetchPages(chapterUrl: String): List<String> {
        return try {
        val base = liveBase()
        val effectiveUrl = rewriteUrlOrigin(chapterUrl, base)
        val html = httpClient.get(effectiveUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        // ReadAllComics.com loads all sequential comic panels inside:
        //   <div class="scrolling-box"><img src="..." /><img src="..." />...</div>
        doc.select("div.scrolling-box img")
            .map { it.attr("src") }
            .filter { it.isNotBlank() && !it.contains("logo", ignoreCase = true) && !it.contains("avatar", ignoreCase = true) }
            .map { absoluteComicUrl(base, it) }
            .distinct()
            .ifEmpty {
                // Fallback: broad <img> extraction if scrolling-box isn't found
                doc.select("div.entry-content img, div.reader img, div.comic img, div.thecontent img, img[src*=/comic/]")
                    .map { it.attr("data-src").ifBlank { it.attr("src") } }
                    .filter { it.isNotBlank() && !it.contains("logo", ignoreCase = true) && !it.contains("avatar", ignoreCase = true) }
                    .map { absoluteComicUrl(base, it) }
                    .distinct()
            }
            .ifEmpty {
                Regex("""https?://[^\s"'>]+\.(?:jpg|jpeg|png|webp)[^\s"'>]*""")
                    .findAll(html)
                    .map { it.value }
                    .filter { it.contains("/comic/") || it.contains("/uploads/") || it.contains("/wp-content/") }
                    .toList()
            }
        } catch (e: Exception) {
        println("[ReadAllComics] Pages failed: ${e.message}")
        emptyList()
    }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BatCave.biz Scraper — clean DOM, high-res panels, single-page loading
//  Domain auto-heals via resolveLiveDomain().
// ─────────────────────────────────────────────────────────────────────────────
class BatCaveScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "BatCave"
    companion object {
        private const val FALLBACK_URL = "https://batcave.biz"
        private const val BRAND_QUERY = "batcave biz comics official"
    }

    private suspend fun liveBase(): String =
        resolveLiveDomain(httpClient, BRAND_QUERY, FALLBACK_URL)

    override suspend fun search(query: String): List<UnifiedSearchResult> {
        return try {
        val base = liveBase()
        val html = httpClient.get("$base/comics/") {
            parameter("s", query)
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        doc.select("div.bs, div.post, article, div.item, div.comic-item").mapNotNull { el ->
            val link = el.select("a[href]").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            val img = el.select("img").firstOrNull()
            val cover = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }
            val title = link.attr("title").ifBlank { link.text() }
                .ifBlank { img?.attr("alt").orEmpty() }
                .decodeHtmlEntitiesLite()
            if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null
            UnifiedSearchResult(
                id = "batcave_${href.substringAfter("://").replace("/", "_")}",
                title = title,
                coverUrl = absoluteComicUrl(base, cover),
                detailPageUrl = absoluteComicUrl(base, href),
                sourceName = sourceName,
                isComic = true,
                genre = "Western Comic"
            )
        }.distinctBy { it.detailPageUrl }.take(30)
        } catch (e: Exception) {
        println("[BatCave] Search failed: ${e.message}")
        emptyList()
    }
    }

    override suspend fun fetchChapters(comicUrl: String): List<MangaChapter> {
        return try {
        val base = liveBase()
        val effectiveUrl = rewriteUrlOrigin(comicUrl, base)
        val html = httpClient.get(effectiveUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        doc.select("a[href*=/issue/], a[href*=/chapter/], div.chapter-list a, ul.list a")
            .filter { it.attr("href").isNotBlank() }
            .distinctBy { it.attr("href") }
            .mapIndexed { idx, el ->
                MangaChapter(
                    title = el.text().decodeHtmlEntitiesLite().ifBlank { "Issue ${idx + 1}" },
                    url = absoluteComicUrl(base, el.attr("href")),
                    chapterNumber = idx + 1
                )
            }.normalizedComicChapterOrder()
        } catch (e: Exception) {
        println("[BatCave] Chapters failed: ${e.message}")
        emptyList()
    }
    }

    override suspend fun fetchPages(chapterUrl: String): List<String> {
        return try {
        val base = liveBase()
        val effectiveUrl = rewriteUrlOrigin(chapterUrl, base)
        val html = httpClient.get(effectiveUrl) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            header("Referer", "$base/")
        }.bodyAsText()
        if (html.isBlockedOrErrorPage()) return emptyList()

        val doc = Ksoup.parse(html)
        doc.select("div.reader-area img, div.reader img, div.comic img, img[src*=/uploads/], img[src*=/comics/]")
            .map { it.attr("data-src").ifBlank { it.attr("src") } }
            .filter { it.isNotBlank() && !it.contains("logo", ignoreCase = true) && !it.contains("avatar", ignoreCase = true) }
            .map { absoluteComicUrl(base, it) }
            .distinct()
            .ifEmpty {
                Regex("""https?://[^\s"'>]+\.(?:jpg|jpeg|png|webp)[^\s"'>]*""")
                    .findAll(html)
                    .map { it.value }
                    .filter { it.contains("/uploads/") || it.contains("/comics/") || it.contains("/wp-content/") }
                    .toList()
            }
        } catch (e: Exception) {
        println("[BatCave] Pages failed: ${e.message}")
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
