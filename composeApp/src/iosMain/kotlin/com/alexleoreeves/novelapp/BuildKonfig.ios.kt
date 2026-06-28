package com.alexleoreeves.novelapp

import platform.Foundation.NSBundle

private fun infoString(name: String, defaultValue: String): String {
    val value = NSBundle.mainBundle.objectForInfoDictionaryKey(name)?.toString()?.trim().orEmpty()
    return value
        .takeIf { it.isNotEmpty() && !it.startsWith("$(") }
        ?: defaultValue
}

actual object BuildKonfig {
    actual val GEMINI_API_KEY: String = infoString("GEMINI_API_KEY", "mock_gemini_api_key")
    actual val RAPID_API_KEY: String = infoString("RAPID_API_KEY", "mock_rapid_api_key")
    actual val RAPID_API_HOST: String = infoString("RAPID_API_HOST", "webnovel.p.rapidapi.com")
    actual val MANGADEX_CLIENT_ID: String = infoString("MANGADEX_CLIENT_ID", "mock_client_id")
    actual val MANGADEX_CLIENT_SECRET: String = infoString("MANGADEX_CLIENT_SECRET", "mock_client_secret")
    actual val MANGADEX_USERNAME: String = infoString("MANGADEX_USERNAME", "mock_user")
    actual val MANGADEX_PASSWORD: String = infoString("MANGADEX_PASSWORD", "mock_pass")
    actual val TMDB_API_KEY: String = infoString("TMDB_API_KEY", "mock_tmdb_api_key")
    actual val TMDB_READ_ACCESS_TOKEN: String = infoString("TMDB_READ_ACCESS_TOKEN", "mock_tmdb_read_access_token")
}
