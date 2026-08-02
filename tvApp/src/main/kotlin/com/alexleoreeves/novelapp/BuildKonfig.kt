package com.alexleoreeves.novelapp

object BuildKonfig {
    val RAPID_API_KEY: String = com.alexleoreeves.novelapp.tv.BuildConfig.RAPID_API_KEY
    val RAPID_API_HOST: String = com.alexleoreeves.novelapp.tv.BuildConfig.RAPID_API_HOST
    val MANGADEX_CLIENT_ID: String = com.alexleoreeves.novelapp.tv.BuildConfig.MANGADEX_CLIENT_ID
    val MANGADEX_CLIENT_SECRET: String = com.alexleoreeves.novelapp.tv.BuildConfig.MANGADEX_CLIENT_SECRET
    val MANGADEX_USERNAME: String = com.alexleoreeves.novelapp.tv.BuildConfig.MANGADEX_USERNAME
    val MANGADEX_PASSWORD: String = com.alexleoreeves.novelapp.tv.BuildConfig.MANGADEX_PASSWORD
    val TMDB_API_KEY: String = com.alexleoreeves.novelapp.tv.BuildConfig.TMDB_API_KEY
    val TMDB_READ_ACCESS_TOKEN: String = com.alexleoreeves.novelapp.tv.BuildConfig.TMDB_READ_ACCESS_TOKEN
    val GROQ_API_KEY: String =
        listOf(
            com.alexleoreeves.novelapp.tv.BuildConfig.GROQ_API_KEY,
            com.alexleoreeves.novelapp.tv.BuildConfig.GROQ_CLOUD_API_KEY
        )
            .firstOrNull { it.isNotBlank() && !it.startsWith("mock_", ignoreCase = true) }
            ?: com.alexleoreeves.novelapp.tv.BuildConfig.GROQ_API_KEY
}
