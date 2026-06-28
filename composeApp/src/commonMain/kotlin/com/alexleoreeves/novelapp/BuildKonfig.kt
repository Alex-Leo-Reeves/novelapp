package com.alexleoreeves.novelapp

/**
 * Bridges platform BuildConfig (injected by Secrets Gradle Plugin) into shared KMP code.
 * On Android: reads from generated BuildConfig class.
 * On iOS: reads from compiled constants.
 */
expect object BuildKonfig {
    val GEMINI_API_KEY: String
    val RAPID_API_KEY: String
    val RAPID_API_HOST: String
    val MANGADEX_CLIENT_ID: String
    val MANGADEX_CLIENT_SECRET: String
    val MANGADEX_USERNAME: String
    val MANGADEX_PASSWORD: String
    val TMDB_API_KEY: String
    val TMDB_READ_ACCESS_TOKEN: String
}
