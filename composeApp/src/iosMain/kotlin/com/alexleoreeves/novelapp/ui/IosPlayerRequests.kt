package com.alexleoreeves.novelapp.ui

import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest

internal fun String.toPlayerRequest(): NSURLRequest {
    val fallback = NSURL.URLWithString("https://vidsrc.to")
        ?: error("Invalid fallback URL")
    val url = NSURL.URLWithString(this) ?: fallback
    return NSURLRequest.requestWithURL(url)
        ?: error("Unable to create NSURLRequest for $this")
}

internal fun String.toEmbedRequest(): NSURLRequest {
    val fallback = NSURL.URLWithString("https://vidsrc.to")
        ?: error("Invalid fallback URL")
    val url = NSURL.URLWithString(this) ?: fallback
    return NSURLRequest.requestWithURL(url)
        ?: error("Unable to create NSURLRequest for $this")
}

internal fun String.toYouTuBeRequest(): NSURLRequest {
    val fallback = NSURL.URLWithString("https://www.youtube.com")
        ?: error("Invalid fallback URL")
    val url = NSURL.URLWithString(this) ?: fallback
    return NSURLRequest.requestWithURL(url)
        ?: error("Unable to create NSURLRequest for $this")
}
