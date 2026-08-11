package com.alexleoreeves.novelapp.data

/**
 * Shared regex-based embed scraper used by the desktop and iOS actuals of
 * [extractStreamFromEmbed]. Mirrors the extraction the Android WebView scraper
 * performs: fetch the embed page HTML, then hunt for a direct .m3u8/.mp4/.webm
 * media URL embedded in the page's scripts.
 */
object EmbedStreamExtractor {

    private val DIRECT_URL_REGEX = Regex(
        """https?://[^"'\s<>\\]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^"'\s<>\\]*)?(?:[#&][^"'\s<>\\]*)*""",
        RegexOption.IGNORE_CASE
    )

    private val PROTOCOL_RELATIVE_REGEX = Regex(
        """(?:^|["'=(\s])//[^"'\s<>\\]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^"'\s<>\\]*)?(?:[#&][^"'\s<>\\]*)*""",
        RegexOption.IGNORE_CASE
    )

    /** Returns the first direct media URL found in [html], or null. */
    fun findDirectStream(html: String?): String? {
        if (html.isNullOrBlank()) return null

        // Normalize JSON / JS escaping so URLs like https:\/\/host\/x.m3u8
        // and https:\u002F\u002Fhost\u002Fx.m3u8 become plain URLs.
        val cleaned = html
            .replace("\\u002F", "/")
            .replace("\\/", "/")

        DIRECT_URL_REGEX.find(cleaned)?.let { match ->
            val value = match.value.trim()
            if (value.length > 12) return value
        }

        PROTOCOL_RELATIVE_REGEX.find(cleaned)?.let { match ->
            val value = match.value
                .trimStart(' ', '\t', '"', '\'', '=', '(')
                .trim()
            if (value.startsWith("//") && value.length > 12) {
                return "https:$value"
            }
        }

        return null
    }
}
