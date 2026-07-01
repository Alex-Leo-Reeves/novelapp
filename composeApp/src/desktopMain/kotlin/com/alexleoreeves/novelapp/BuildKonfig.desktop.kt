package com.alexleoreeves.novelapp

private fun envOrDefault(name: String, defaultValue: String): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultValue

private fun envOrDefault(names: List<String>, defaultValue: String): String =
    names.firstNotNullOfOrNull { name -> System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } } ?: defaultValue

actual object BuildKonfig {
    actual val RAPID_API_KEY: String = envOrDefault("RAPID_API_KEY", "mock_rapid_api_key")
    actual val RAPID_API_HOST: String = envOrDefault("RAPID_API_HOST", "webnovel.p.rapidapi.com")
    actual val MANGADEX_CLIENT_ID: String = envOrDefault("MANGADEX_CLIENT_ID", "mock_client_id")
    actual val MANGADEX_CLIENT_SECRET: String = envOrDefault("MANGADEX_CLIENT_SECRET", "mock_client_secret")
    actual val MANGADEX_USERNAME: String = envOrDefault("MANGADEX_USERNAME", "mock_user")
    actual val MANGADEX_PASSWORD: String = envOrDefault("MANGADEX_PASSWORD", "mock_pass")
    actual val TMDB_API_KEY: String = envOrDefault("TMDB_API_KEY", "mock_tmdb_api_key")
    actual val TMDB_READ_ACCESS_TOKEN: String = envOrDefault("TMDB_READ_ACCESS_TOKEN", "mock_tmdb_read_access_token")
    actual val GROQ_API_KEY: String = envOrDefault(listOf("GROQ_API_KEY", "GROQ_CLOUD_API_KEY", "groq_cloud_api_key"), "mock_groq_api_key")
}
