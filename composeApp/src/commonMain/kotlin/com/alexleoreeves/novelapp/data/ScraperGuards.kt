package com.alexleoreeves.novelapp.data

internal fun String.isBlockedOrErrorPage(): Boolean {
    val text = lowercase()
    return text.contains("just a moment") ||
        text.contains("enable javascript and cookies") ||
        text.contains("__cf_chl") ||
        text.contains("cf_chl_") ||
        text.contains("request is invalid") ||
        text.contains("page not found") ||
        text.contains("<title>400") ||
        text.contains("<title>403") ||
        text.contains("<title>404")
}

internal fun String.decodeHtmlEntitiesLite(): String =
    replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&rsquo;", "'")
        .replace("&ldquo;", "\"")
        .replace("&rdquo;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

internal fun String.isNavigationTitle(): Boolean {
    val normalized = lowercase().trim()
    return normalized in setOf(
        "action",
        "adventure",
        "anime",
        "browse",
        "cartoon",
        "chapter",
        "comedy",
        "contact",
        "fantasy",
        "genres",
        "home",
        "horror",
        "k-drama",
        "manga",
        "movies",
        "newest",
        "novel",
        "novels",
        "popular",
        "random",
        "romance",
        "search",
        "terms",
        "updated"
    )
}
