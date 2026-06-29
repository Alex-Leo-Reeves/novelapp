package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
            json(authJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }

    suspend fun register(username: String, email: String, password: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/register") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username = username, email = email, password = password))
        }
        return response.toSavedUserAccount()
    }

    suspend fun login(email: String, password: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/login") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email = email, password = password))
        }
        return response.toSavedUserAccount()
    }

    suspend fun me(token: String): SavedUserAccount {
        val response = client.get("$baseUrl/auth/me") {
            accept(ContentType.Application.Json)
            bearerAuth(token)
        }
        return response.toSavedUserAccount(existingToken = token)
    }

    suspend fun logout(token: String) {
        client.post("$baseUrl/auth/logout") {
            accept(ContentType.Application.Json)
            bearerAuth(token)
        }
    }

    suspend fun getUserState(token: String): UserSyncState {
        val response = client.get("$baseUrl/user/state") {
            accept(ContentType.Application.Json)
            bearerAuth(token)
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException(rawBody.decodeAuthError() ?: "Could not load account history.")
        }
        return authJson.decodeFromString<UserStateResponse>(rawBody).state
    }

    suspend fun putUserState(token: String, state: UserSyncState): UserSyncState {
        val response = client.put("$baseUrl/user/state") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UserStateRequest(state = state))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException(rawBody.decodeAuthError() ?: "Could not sync account history.")
        }
        return authJson.decodeFromString<UserStateResponse>(rawBody).state
    }

    private suspend fun HttpResponse.toSavedUserAccount(existingToken: String? = null): SavedUserAccount {
        val rawBody = bodyAsText()

        if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
            val error = rawBody.decodeAuthError()
            throw IllegalStateException(error ?: "Authentication failed.")
        }

        if (rawBody.isBlank()) {
            throw IllegalStateException(
                "Authentication server returned an empty response. Make sure Render is running the NovelApp web service, not a static site."
            )
        }

        val payload = rawBody.decodeAuthResponse()
        val token = payload.token ?: existingToken
        if (token.isNullOrBlank()) {
            throw IllegalStateException("Authentication token was missing.")
        }

        return SavedUserAccount(
            id = payload.user.id,
            username = payload.user.username,
            email = payload.user.email,
            authToken = token,
            plan = payload.user.plan,
            billingStatus = payload.user.billingStatus,
            createdAt = payload.user.createdAt
        )
    }

    private fun String.decodeAuthResponse(): AuthResponse =
        runCatching { authJson.decodeFromString<AuthResponse>(this) }
            .getOrElse {
                throw IllegalStateException(
                    "Authentication server returned invalid JSON. Make sure Render is running the NovelApp web service."
                )
            }

    private fun String.decodeAuthError(): String? =
        takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { authJson.decodeFromString<AuthErrorResponse>(raw).error }.getOrNull() }
}

private val authJson = Json { ignoreUnknownKeys = true; isLenient = true }

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
    val id: String = "",
    val username: String,
    val email: String,
    val plan: String = "free",
    val billingStatus: String = "none",
    val createdAt: String = ""
)

@Serializable
private data class AuthErrorResponse(
    val error: String
)

@Serializable
private data class UserStateRequest(
    val state: UserSyncState
)

@Serializable
private data class UserStateResponse(
    val user: AuthUserResponse,
    val state: UserSyncState
)
