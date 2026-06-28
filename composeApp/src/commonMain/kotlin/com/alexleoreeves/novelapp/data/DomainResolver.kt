package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.currentTimeMillis
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.decodeURLQueryComponent

// ─────────────────────────────────────────────────────────────────────────────
//  Shared domain resolver — used by both anime and manga scrapers.
//
//  DuckDuckGo HTML search is queried once per brand name; the result is cached
//  for 6 hours so domain-hop recovery is fully automatic and silent.
//  Falls back to the hardcoded URL on any network or parse failure.
// ─────────────────────────────────────────────────────────────────────────────

internal object DomainCache {
    private data class Entry(val domain: String, val expiresAt: Long)
    private val store = mutableMapOf<String, Entry>()
    private const val TTL_MS = 6L * 60 * 60 * 1000 // 6 hours

    fun get(key: String): String? {
        val entry = store[key] ?: return null
        return if (currentTimeMillis() < entry.expiresAt) entry.domain else null
    }

    fun put(key: String, domain: String) {
        store[key] = Entry(domain, currentTimeMillis() + TTL_MS)
    }
}

/**
 * Resolves the current live domain for [brandQuery] via DuckDuckGo HTML search.
 *
 * Steps:
 *  1. Check the in-memory TTL cache — return immediately if fresh.
 *  2. GET `html.duckduckgo.com/html/?q=<brandQuery>` with a mobile browser UA.
 *  3. Parse the first `a.result__a` href; strip path to get `scheme://host`.
 *  4. Cache and return the result; return [fallback] on any failure.
 *
 * @param client   Existing HttpClient — no new client is created.
 * @param brandQuery   Search string, e.g. "weebcentral manga official".
 * @param fallback     Hardcoded URL to use if DDG is unreachable or returns nothing useful.
 */
internal suspend fun resolveLiveDomain(
    client: HttpClient,
    brandQuery: String,
    fallback: String
): String {
    // Cache hit — skip network call
    DomainCache.get(brandQuery)?.let { return it }

    return runCatching {
        val html: String = client.get("https://html.duckduckgo.com/html/") {
            parameter("q", brandQuery)
            header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            header("Accept-Language", "en-US,en;q=0.9")
        }.body()

        val doc = Ksoup.parse(html)
        // DDG HTML result links appear as <a class="result__a" href="...">
        val firstHref = doc.select("a.result__a[href]").firstOrNull()?.attr("href").orEmpty()
        val targetHref = firstHref.extractDuckDuckGoTarget()

        val resolved: String = when {
            targetHref.startsWith("https://") || targetHref.startsWith("http://") -> targetHref.toOrigin()
            else -> {
                // Secondary: plain-text domain inside <a class="result__url">
                val urlText = doc.select("a.result__url").firstOrNull()?.text().orEmpty().trim()
                if (urlText.isNotBlank()) "https://${urlText.substringBefore("/")}" else fallback
            }
        }

        if (resolved.startsWith("http")) {
            println("[DomainResolver] '$brandQuery' → $resolved")
            DomainCache.put(brandQuery, resolved)
            resolved
        } else {
            fallback
        }
    }.getOrElse { e ->
        println("[DomainResolver] Failed for '$brandQuery': ${e.message} — using fallback $fallback")
        fallback
    }
}

private fun String.toOrigin(): String =
    substringBefore("?")
        .substringBefore("#")
        .let { raw ->
            val protocol = raw.substringBefore("://", "")
            val rest = raw.substringAfter("://", raw)
            if (protocol.isBlank()) raw else "$protocol://${rest.substringBefore("/")}"
        }

private fun String.extractDuckDuckGoTarget(): String {
    val encodedTarget = substringAfter("uddg=", "")
        .substringBefore("&")
    return if (encodedTarget.isBlank()) this else encodedTarget.decodeURLQueryComponent()
}

internal fun rewriteUrlOrigin(url: String, newOrigin: String): String {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return url
    val pathAndQuery = url
        .substringAfter("://")
        .substringAfter("/", "")
    return if (pathAndQuery.isBlank()) {
        newOrigin.trimEnd('/')
    } else {
        "${newOrigin.trimEnd('/')}/$pathAndQuery"
    }
}
