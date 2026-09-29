package com.alexleoreeves.novelapp.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.encodeURLQueryComponent

/**
 * DEVICE-SIDE donghuaworld.com scraper — the "Loong" donghua server.
 *
 * WHY (2026-09-29): the backend scrapes donghuaworld from Render, but
 * Cloudflare flags Render's egress IP (every request answers 403) and the
 * relay chain meant to route around it is dead (Jina 403s Render too,
 * allorigins 522, codetabs 503) — a resolve took ~70 s then failed, shown
 * in the app as an endless "Resolving stream..." with no player.
 * The phone/TV fetch from a residential IP where the site answers 200 in
 * ~3 s, so the same scrape runs HERE. The backend stays as a fallback.
 *
 * Parsing mirrors server/donghua-handlers.js one-to-one:
 *  - search   : GET /?s=<title> → ranked series links
 *  - episodes : the `eplister` grid (data-index <li>, epl-num/title)
 *  - resolve  : episode page `data-hash` base64 iframe blob →
 *               playing.donghuaworld player page → inline jwplayer setup →
 *               PUBLIC Rumble HLS master + per-quality mp4 + VTT tracks.
 *               Pages shipping only the Dailymotion server return the DM
 *               player URL as an EMBED stream (native players cannot meet
 *               the DM CDN fingerprint; the WebView players can).
 */
class DonghuaWorldScraper(private val client: HttpClient) {

    companion object {
        const val SITE = "https://donghuaworld.com"

        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** True when a URL belongs to this dedicated source (or its player host). */
        fun isDonghuaworldUrl(url: String): Boolean =
            url.contains("donghuaworld.com", ignoreCase = true) ||
                url.contains("playing.donghuaworld", ignoreCase = true)

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Pure-Kotlin base64 — commonMain code must not touch java.util (iOS). */
        private fun decodeBase64(encoded: String): String {
            val out = StringBuilder(encoded.length)
            var buffer = 0
            var bits = 0
            for (ch in encoded) {
                if (ch == '=') break
                val value = B64.indexOf(ch)
                if (value < 0) continue
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.append(((buffer shr bits) and 0xFF).toChar())
                }
            }
            return out.toString()
        }

        /** Navigation paths — never series pages. */
        private val NON_SERIES_SEGMENTS = setOf(
            "wp-content", "wp-includes", "wp-json", "wp-admin", "feed", "about",
            "faq", "schedule", "a-z-lists", "contact", "privacy", "terms",
            "tag", "category", "page", "pages", "author", "search", "comment",
            "comments", "network", "genre", "genres", "genres2", "login",
            "register", "advertise", "dmca", "sitemap", "ost", "movies"
        )

        /** Cloudflare / edge-block markers — a challenge page is never content. */
        private fun isBlockedPage(body: String): Boolean {
            val head = body.take(4000)
            return head.contains("Just a moment...", ignoreCase = true) ||
                head.contains("Attention Required", ignoreCase = true) ||
                head.contains("<title>403") ||
                head.contains("<title>429")
        }

        private fun slugTokens(value: String): List<String> =
            value.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .split(" ")
                .filter { it.isNotEmpty() }

        private val SUBTITLE_LANG_CODES = mapOf(
            "english" to "en", "indonesian" to "id", "malay" to "ms", "thai" to "th",
            "vietnamese" to "vi", "hindi" to "hi", "bangla" to "bn", "arabic" to "ar",
            "persian" to "fa", "turkish" to "tr", "russian" to "ru", "german" to "de",
            "french" to "fr", "spanish" to "es", "italian" to "it", "portuguese" to "pt",
            "polish" to "pl", "khmer" to "km", "chinese" to "zh"
        )

        private fun subtitleLangCode(label: String): String {
            val key = label.trim().lowercase()
            SUBTITLE_LANG_CODES[key]?.let { return it }
            return key.filter { it in 'a'..'z' }.take(5).ifEmpty { "und" }
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private suspend fun fetchHtml(url: String): String? = try {
        val body: String = client.get(url) {
            header("User-Agent", BROWSER_UA)
            header("Accept", "text/html,application/xhtml+xml")
            header("Accept-Language", "en-US,en;q=0.9")
            // playing.donghuaworld answers 403 without a donghuaworld.com
            // Referer (verified live 2026-09-29: 403 → 200 +18 subtitles
            // as soon as the header is present); the episode pages get it too.
            header("Referer", "$SITE/")
        }.body()
        body.takeIf { it.isNotBlank() && !isBlockedPage(it) }
    } catch (_error: Exception) {
        null
    }

    private fun absoluteUrl(href: String, base: String): String? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("javascript:") || h.startsWith("#")) return null
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:$h"
        val baseClean = base.substringBefore("?").substringBefore("#")
        if (h.startsWith("/")) {
            val scheme = baseClean.substringBefore("://")
            val host = baseClean.substringAfter("://").substringBefore("/")
            return "$scheme://$host$h"
        }
        return baseClean.substringBeforeLast("/", "") + "/" + h
    }

    // ── Search ───────────────────────────────────────────────────────────

    /**
     * Ranked series candidates for a title — the device-side twin of the
     * backend's `collectSearchCandidates`: keeps only real series links
     * (nav/asset links dropped) and scores slug ↔ query token overlap.
     */
    suspend fun searchSeries(title: String): List<DonghuaSeriesRef> {
        val html = fetchHtml("$SITE/?s=${title.encodeURLQueryComponent()}") ?: return emptyList()
        val queryTokens = slugTokens(title).filter { it.length >= 2 }
        if (queryTokens.isEmpty()) return emptyList()

        val seen = LinkedHashSet<String>()
        val pattern = Regex("""href=["'](https://donghuaworld\.com/[^"'?#]*)["']""", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(html)) {
            seen.add(match.groupValues[1])
        }

        return seen.mapNotNull { url ->
            val path = url.removePrefix("https://donghuaworld.com").trim('/')
            if (path.isEmpty()) return@mapNotNull null
            val segments = path.split('/')
            if (segments.size > 2) return@mapNotNull null
            val slug = segments.last().lowercase()
            if (segments.size == 2 && segments[0].lowercase() in NON_SERIES_SEGMENTS) return@mapNotNull null
            if (slug in NON_SERIES_SEGMENTS) return@mapNotNull null

            val slugTokenList = slugTokens(slug)
            val matched = queryTokens.count { q -> slugTokenList.any { it.startsWith(q) || q.startsWith(it) } }
            if (matched == 0) return@mapNotNull null
            if (matched < queryTokens.size && matched < 2) return@mapNotNull null

            val score = matched * 1000 - path.length
            val pretty = slug.replace('-', ' ').replace(Regex("\\s+"), " ").trim()
            Triple(score, pretty, url)
        }
            .sortedByDescending { it.first }
            .take(8)
            .map { DonghuaSeriesRef(id = "", title = it.second, url = it.third) }
    }


    // ── Episode grid ─────────────────────────────────────────────────────

    /** Episode number from `epl-num` / title text, else the grid position. */
    private fun parseEpisodeNumber(numText: String, title: String, fallbackIndex: Int): Int {
        for (source in listOf(numText, title)) {
            if (source.isBlank()) continue
            val spelled = Regex("""(?:episode|ep\.?|e)\s*[-#]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(source)
            if (spelled != null) return spelled.groupValues[1].toIntOrNull() ?: fallbackIndex
            val bare = Regex("""^\s*(\d{1,4})\b""").find(source)
            if (bare != null) return bare.groupValues[1].toIntOrNull() ?: fallbackIndex
        }
        return fallbackIndex
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&#8211;", "-").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** The `eplister` grid → ascending [MediaEpisode] list. */
    suspend fun fetchEpisodes(seriesUrl: String): List<MediaEpisode> {
        val html = fetchHtml(seriesUrl) ?: return emptyList()
        val listStart = html.indexOf("eplister")
        val scope = if (listStart >= 0) html.substring(listStart) else html

        val out = ArrayList<MediaEpisode>()
        val seen = HashSet<String>()
        val itemPattern = Regex("""<li[^>]*data-index=["']?\d+["']?[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
        var index = 0
        for (item in itemPattern.findAll(scope)) {
            val chunk = item.groupValues[1]
            val href = Regex("""<a[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(chunk)?.groupValues[1]
                ?: continue
            val url = absoluteUrl(href, seriesUrl) ?: continue
            if (!url.contains("episode", ignoreCase = true) && !url.contains("watch", ignoreCase = true)) continue
            if (!seen.add(url)) continue
            index++

            val numText = Regex("""<div[^>]*class=["'][^"']*epl-num[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                .find(chunk)?.groupValues?.get(1).orEmpty()
            val titleText = Regex("""<div[^>]*class=["'][^"']*epl-title[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                .find(chunk)?.groupValues?.get(1).orEmpty()

            val number = parseEpisodeNumber(stripTags(numText), stripTags(titleText), index)
            out.add(
                MediaEpisode(
                    episodeNumber = number,
                    title = stripTags(titleText).ifEmpty { "Episode $number" },
                    url = url
                )
            )
        }
        return out.sortedBy { it.episodeNumber }
    }

    /** First ranked candidate that really carries an episode grid. */
    suspend fun fetchEpisodesForTitle(title: String): List<MediaEpisode> {
        val candidates = searchSeries(title)
        for (candidate in candidates.take(5)) {
            val episodes = fetchEpisodes(candidate.url)
            if (episodes.isNotEmpty()) return episodes
        }
        return emptyList()
    }


    // ── Stream resolve ───────────────────────────────────────────────────

    /** Servers found on one episode page. */
    private data class Servers(val own: String, val dailymotion: String)

    /** The episode page hides servers inside base64 `data-hash` iframe blobs. */
    private fun extractServerUrls(html: String): Servers {
        var own = ""
        var dailymotion = ""
        val hashPattern = Regex("""data-hash=["']([A-Za-z0-9+/=]{16,})["']""")
        for (match in hashPattern.findAll(html)) {
            val decoded = try {
                decodeBase64(match.groupValues[1])
            } catch (_error: Exception) {
                ""
            }
            if (decoded.isEmpty()) continue
            val src = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues[1]
                ?: continue
            when {
                src.contains("playing.donghuaworld") && own.isEmpty() -> own = src
                src.contains("dailymotion") && dailymotion.isEmpty() -> dailymotion = src
            }
        }
        if (own.isEmpty()) {
            own = Regex("""(https?://playing\.donghuaworld\.[a-z]+/[A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1).orEmpty()
        }
        if (dailymotion.isEmpty()) {
            val dm = Regex(
                """(https?://(?:www\.)?(?:geo\.)?dailymotion\.com/(?:player[^"'\\s]*[?&]video=|embed/video/)([A-Za-z0-9]+))""",
                RegexOption.IGNORE_CASE
            ).find(html)
            dailymotion = dm?.groupValues?.get(1).orEmpty()
        }
        return Servers(own, dailymotion)
    }

    private fun dailymotionVideoId(url: String): String {
        if (url.isEmpty()) return ""
        Regex("""[?&]video=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.let { return it }
        return Regex("""dailymotion\.com/(?:embed/)?(?:video/)?([A-Za-z0-9]{6,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1).orEmpty()
    }


    /**
     * Episode page → playable stream(s): Rumble HLS master (+ per-quality
     * mp4s + VTT tracks) when the source's own player exists; the DM player
     * URL as an EMBED stream otherwise; null when neither answers.
     */
    suspend fun resolveEpisodeStream(episodeUrl: String): DonghuaResolved? {
        if (!episodeUrl.startsWith("http", ignoreCase = true)) return null
        val resolvedUrl = absoluteUrl(episodeUrl, SITE) ?: return null
        if (!isDonghuaworldUrl(resolvedUrl)) return null
        val html = fetchHtml(resolvedUrl) ?: return null
        val title = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("""\s*[-–|]\s*Donghua World.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            .orEmpty()

        val servers = extractServerUrls(html)

        if (servers.own.isNotBlank()) {
            val playerPage = fetchHtml(servers.own)?.replace("\\/", "/")
            if (playerPage != null) {
                val streams = ArrayList<DonghuaStream>()
                val subtitles = ArrayList<DonghuaSubtitle>()

                val master = Regex(
                    """"file"\s*:\s*"(https://rumble\.com/hls-vod/[^"]+\.m3u8[^"]*)"""",
                    RegexOption.IGNORE_CASE
                ).find(playerPage)?.groupValues?.get(1).orEmpty()
                if (master.isNotBlank()) {
                    // Cheap liveness probe — only advertise a playlist the CDN
                    // really answers (mirrors the backend's playlistIsPlayable).
                    val probe = try {
                        client.get(master) { header("User-Agent", BROWSER_UA) }.body<String>()
                    } catch (_error: Exception) {
                        ""
                    }
                    if (probe.contains("#EXTM3U")) {
                        streams.add(DonghuaStream(url = master, quality = "auto", label = "Donghuaworld (HLS)", type = "hls"))
                    }
                }

                val qualityPattern = Regex(
                    """"file"\s*:\s*"(https://hugh\.cdn\.rumble\.cloud/[^"]+)"\s*,\s*"type"\s*:\s*"video/mp4"\s*,\s*"label"\s*:\s*"([^"]+)"""",
                    RegexOption.IGNORE_CASE
                )
                for (quality in qualityPattern.findAll(playerPage)) {
                    streams.add(
                        DonghuaStream(
                            url = quality.groupValues[1],
                            quality = quality.groupValues[2],
                            label = "Donghuaworld " + quality.groupValues[2],
                            type = "mp4"
                        )
                    )
                }

                val subPattern = Regex("""\{"file"\s*:\s*"(https:[^"]+\.vtt)"\s*,\s*"label"\s*:\s*"([^"]+)"\}""", RegexOption.IGNORE_CASE)
                for (sub in subPattern.findAll(playerPage)) {
                    subtitles.add(
                        DonghuaSubtitle(
                            url = sub.groupValues[1],
                            label = sub.groupValues[2],
                            lang = subtitleLangCode(sub.groupValues[2])
                        )
                    )
                }

                if (streams.isNotEmpty()) {
                    return DonghuaResolved(title = title, streams = streams, subtitles = subtitles)
                }
            }
        }

        // Dailymotion-only pages: hand the WebView players the embed (native
        // players cannot satisfy the DM CDN's TLS/header fingerprint).
        val dmUrl = servers.dailymotion.ifBlank {
            dailymotionVideoId(html).let { id ->
                if (id.isNotBlank()) "https://www.dailymotion.com/embed/video/$id" else ""
            }
        }
        if (dmUrl.isNotBlank()) {
            val embed = if (dmUrl.contains("autoplay", ignoreCase = true)) dmUrl
            else dmUrl + (if (dmUrl.contains("?")) "&" else "?") + "autoplay=1"
            return DonghuaResolved(
                title = title,
                streams = listOf(DonghuaStream(url = embed, quality = "auto", label = "Dailymotion", type = "embed")),
                subtitles = emptyList()
            )
        }
        return null
    }


    /**
     * Title + episode number → stream. Walks the ranked candidates like the
     * backend's `resolveBestSeriesForTitle`, resolving the best-fitting
     * episode of the first series that actually answers.
     */
    suspend fun resolveByTitle(title: String, episodeNumber: Int): DonghuaResolved? {
        val want = episodeNumber.coerceAtLeast(1)
        val candidates = searchSeries(title)
        for (candidate in candidates.take(5)) {
            val episodes = fetchEpisodes(candidate.url)
            if (episodes.isEmpty()) continue
            val target = episodes.firstOrNull { it.episodeNumber == want }
                ?: episodes.filter { it.episodeNumber <= want }.maxByOrNull { it.episodeNumber }
                ?: episodes.first()
            val resolved = resolveEpisodeStream(target.url)
            if (resolved != null) return resolved
        }
        return null
    }
}

