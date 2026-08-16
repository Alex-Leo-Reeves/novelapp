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
    request.setValue(PLAYER_USER_AGENT, forHTTPHeaderField = "User-Agent")
    request.setValue(playerReferer(), forHTTPHeaderField = "Referer")
    request.setValue(playerOrigin(), forHTTPHeaderField = "Origin")
    request.setValue("*/*", forHTTPHeaderField = "Accept")
    request.setValue("en-US,en;q=0.9", forHTTPHeaderField = "Accept-Language")
    return request
}

internal fun String.toEmbedRequest(): NSMutableURLRequest {
    val fallbackUrl = NSURL.URLWithString("https://vidsrc.to")
        ?: error("Unable to create vidsrc.to fallback URL")
    val url = NSURL.URLWithString(this) ?: fallbackUrl
    val origin = embedOrigin()
    val request = NSMutableURLRequest()
    request.setURL(url)
    request.setValue(MA_EMBED_USER_AGENT, forHTTPHeaderField = "User-Agent")
    request.setValue("$origin/", forHTTPHeaderField = "Referer")
    request.setValue(origin, forHTTPHeaderField = "Origin")
    request.setValue("*/*", forHTTPHeaderField = "Accept")
    request.setValue("en-US,en;q=0.9", forHTTPHeaderField = "Accept-Language")
    return request
}

internal fun String.toYouTuBeRequest(): NSMutableURLRequest {
    val fallbackUrl = NSURL.URLWithString("https://www.youtube.com")
        ?: error("Unable to create youtube.com fallback URL")
    val url = NSURL.URLWithString(this) ?: fallbackUrl
    val request = NSMutableURLRequest()
    request.setURL(url)
    request.setValue(YOUTUBE_USER_AGENT, forHTTPHeaderField = "User-Agent")
    request.setValue("*/*", forHTTPHeaderField = "Accept")
    request.setValue("en-US,en;q=0.9", forHTTPHeaderField = "Accept-Language")
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
