package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AuthApi(
    private val baseUrl: String = AppReleaseConfig.API_BASE_URL
) {
    private val client = platformHttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }

    suspend fun register(username: String, email: String, password: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username = username, email = email, password = password))
        }
        return response.toSavedUserAccount()
    }

    suspend fun login(email: String, password: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email = email, password = password))
        }
        return response.toSavedUserAccount()
    }

    suspend fun me(token: String): SavedUserAccount {
        val response = client.get("$baseUrl/auth/me") {
            bearerAuth(token)
        }
        return response.toSavedUserAccount(existingToken = token)
    }

    suspend fun logout(token: String) {
        client.post("$baseUrl/auth/logout") {
            bearerAuth(token)
        }
    }

    private suspend fun HttpResponse.toSavedUserAccount(existingToken: String? = null): SavedUserAccount {
        if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
            val error = runCatching { body<AuthErrorResponse>().error }.getOrNull()
            throw IllegalStateException(error ?: "Authentication failed.")
        }

        val payload = body<AuthResponse>()
        val token = payload.token ?: existingToken
        if (token.isNullOrBlank()) {
            throw IllegalStateException("Authentication token was missing.")
        }

        return SavedUserAccount(
            username = payload.user.username,
            email = payload.user.email,
            authToken = token
        )
    }
}

@Serializable
private data class AuthRequest(
    val username: String? = null,
    val email: String,
    val password: String
)

@Serializable
private data class AuthResponse(
    val token: String? = null,
    val user: AuthUserResponse
)

@Serializable
private data class AuthUserResponse(
    val username: String,
    val email: String
)

@Serializable
private data class AuthErrorResponse(
    val error: String
)
