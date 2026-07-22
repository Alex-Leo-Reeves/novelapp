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
//
//  NOTE: This is currently the only scrapable Western comic source.
//  ViewComic.com requires FingerprintJS (browser-only) and ComicBookPlus
//  uses Google Custom Search Engine (JS-rendered results), both impossible
//  to scrape server-side with Ktor's HTTP client.
// ─────────────────────────────────────────────────────────────────────────────
class NewComicScraper(private val httpClient: HttpClient) : ComicSource {

    override val sourceName = "NewComic"
    companion object {
        private const val BASE_URL = "https://newcomic.info"
        private val POPULAR_CATEGORIES = listOf(
            "/dc/", "/marvels/", "/idw/", "/dark-horse/", "/image-comics/", "/boom-studios/",
            "/dynamite/", "/vertigo-comics/", "/valiant-comics/", "/zenescope/",
            "/graphic-novels/", "/comics/", "/titan/", "/top-cow/", "/wildstorm/",
            "/disney-comics/", "/adult/"
        )
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
            emptyList()
        }
    }

    private fun parseSearchResults(html: String): List<UnifiedSearchResult> {
        val doc = Ksoup.parse(html)
        return doc.select("div.vidos div.preview-in").mapNotNull { el ->
            val img = el.select("a.preview-img.img-box img").firstOrNull()
            val titleFromImg = img?.attr("alt")?.decodeHtmlEntitiesLite().orEmpty()
            val txtLink = el.select("div.preview-text a").firstOrNull()
            val titleFromText = txtLink?.text()?.decodeHtmlEntitiesLite().orEmpty()
            val anyLink = el.select("a[href]").firstOrNull()

            val title = titleFromImg.ifBlank { titleFromText.ifBlank { anyLink?.text()?.decodeHtmlEntitiesLite().orEmpty() } }
            val href = txtLink?.attr("href")?.trim()
                ?: img?.parent()?.attr("href")?.trim()
                ?: anyLink?.attr("href")?.trim().orEmpty()

            if (href.isBlank() || title.isBlank() || title.isNavigationTitle()) return@mapNotNull null

            val cover = listOf("data-original", "data-lazy-src", "data-src", "src")
                .firstNotNullOfOrNull { attr -> img?.attr(attr)?.takeIf { v -> v.isNotBlank() } } ?: ""

            UnifiedSearchResult(
                id = "nc_${href.substringAfter("://").replace("/", "_")}",
                title = title,
                coverUrl = absoluteComicUrl(BASE_URL, cover).replace("&#58;", ":"),
                detailPageUrl = absoluteComicUrl(BASE_URL, href),
                sourceName = sourceName,
                isComic = true,
                genre = "Western Comic"
            )
        }.distinctBy { it.detailPageUrl }.take(48)
    }

    /** Fetches a popular feed by rotating through publisher category pages. */
    suspend fun fetchPopular(page: Int = 1): List<UnifiedSearchResult> {
        val category = POPULAR_CATEGORIES.getOrElse((page - 1).mod(POPULAR_CATEGORIES.size)) { POPULAR_CATEGORIES[0] }
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

            // Newcomic.info is a download site — each page is one issue.
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
            val images = mutableListOf<String>()

            // 1. Cover image from og:description
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
