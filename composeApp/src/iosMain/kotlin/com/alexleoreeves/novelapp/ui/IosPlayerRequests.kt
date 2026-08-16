package com.alexleoreeves.novelapp.ui

import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL

internal const val PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

internal const val MA_EMBED_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

internal const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

internal fun String.toPlayerRequest(): NSMutableURLRequest {
    val fallbackUrl = NSURL.URLWithString("https://vidsrc.to")
        ?: error("Unable to create vidsrc.to fallback URL")
    val url = NSURL.URLWithString(this) ?: fallbackUrl
    val request = NSMutableURLRequest()
    request.setURL(url)
    request.allHTTPHeaderFields = mapOf<Any?, String>(
        "User-Agent" to PLAYER_USER_AGENT,
        "Referer" to playerReferer(),
        "Origin" to playerOrigin(),
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    return request
}

internal fun String.toEmbedRequest(): NSMutableURLRequest {
    val fallbackUrl = NSURL.URLWithString("https://vidsrc.to")
        ?: error("Unable to create vidsrc.to fallback URL")
    val url = NSURL.URLWithString(this) ?: fallbackUrl
    val origin = embedOrigin()
    val request = NSMutableURLRequest()
    request.setURL(url)
    request.allHTTPHeaderFields = mapOf<Any?, String>(
        "User-Agent" to MA_EMBED_USER_AGENT,
        "Referer" to "$origin/",
        "Origin" to origin,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    return request
}

internal fun String.toYouTuBeRequest(): NSMutableURLRequest {
    val fallbackUrl = NSURL.URLWithString("https://www.youtube.com")
        ?: error("Unable to create youtube.com fallback URL")
    val url = NSURL.URLWithString(this) ?: fallbackUrl
    val request = NSMutableURLRequest()
    request.setURL(url)
    request.allHTTPHeaderFields = mapOf<Any?, String>(
        "User-Agent" to YOUTUBE_USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    return request
}

internal fun String.playerReferer(): String = "${playerOrigin()}/"

internal fun String.playerOrigin(): String {
    val url = NSURL.URLWithString(this)
    val scheme = url?.scheme ?: "https"
    val host = url?.host ?: "vidsrc.to"
    return "$scheme://$host"
}

internal fun String.embedOrigin(): String {
    val url = NSURL.URLWithString(this)
    val scheme = url?.scheme ?: "https"
    val host = url?.host ?: "vidsrc.to"
    return "$scheme://$host"
}
