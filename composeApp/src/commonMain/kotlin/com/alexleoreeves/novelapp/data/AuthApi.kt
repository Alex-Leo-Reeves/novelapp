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

    suspend fun register(username: String, email: String, password: String, recoverySecret: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/register") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username = username, email = email, password = password, recoverySecret = recoverySecret))
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

    suspend fun recoverAccount(recoverySecret: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/recover") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(RecoveryRequest(recoverySecret = recoverySecret))
        }
        return response.toSavedUserAccount()
    }

    suspend fun resetPassword(token: String, password: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/reset-password") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(PasswordResetRequest(password = password))
        }
        return response.toSavedUserAccount(existingToken = token)
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
            throw AuthApiException(rawBody.decodeAuthError() ?: "Could not load account history.", response.status.value)
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
            throw AuthApiException(rawBody.decodeAuthError() ?: "Could not sync account history.", response.status.value)
        }
        return authJson.decodeFromString<UserStateResponse>(rawBody).state
    }

    suspend fun billingStatus(token: String): BillingStatus {
        val response = client.get("$baseUrl/billing/status") {
            accept(ContentType.Application.Json)
            bearerAuth(token)
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw AuthApiException(rawBody.decodeAuthError() ?: "Could not load subscription.", response.status.value)
        }
        val payload = authJson.decodeFromString<BillingStatusResponse>(rawBody)
        return BillingStatus(
            account = payload.user.toAccount(token),
            premium = payload.premium,
            monthlyFee = payload.monthlyFee,
            currency = payload.currency,
            freePreview = payload.freePreview
        )
    }

    suspend fun createBillingCheckout(token: String): BillingCheckout {
        val response = client.post("$baseUrl/billing/checkout") {
            accept(ContentType.Application.Json)
            bearerAuth(token)
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw AuthApiException(rawBody.decodeAuthError() ?: "Could not start subscription checkout.", response.status.value)
        }
        return authJson.decodeFromString<BillingCheckout>(rawBody)
    }

    private suspend fun HttpResponse.toSavedUserAccount(existingToken: String? = null): SavedUserAccount {
        val rawBody = bodyAsText()

        if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
            val error = rawBody.decodeAuthError()
            throw AuthApiException(error ?: "Authentication failed.", status.value)
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

        return payload.user.toAccount(token)
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

data class BillingStatus(
    val account: SavedUserAccount,
    val premium: Boolean,
    val monthlyFee: Int,
    val currency: String,
    val freePreview: BillingPreview
)

class AuthApiException(
    message: String,
    val statusCode: Int
) : IllegalStateException(message)

private val authJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
private data class AuthRequest(
    val username: String? = null,
    val email: String? = null,
    val password: String,
    val recoverySecret: String? = null
)

@Serializable
private data class RecoveryRequest(
    val recoverySecret: String
)

@Serializable
private data class PasswordResetRequest(
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
    val paidUntil: String? = null,
    val createdAt: String = ""
) {
    fun toAccount(token: String): SavedUserAccount =
        SavedUserAccount(
            id = id,
            username = username,
            email = email,
            authToken = token,
            plan = plan,
            billingStatus = billingStatus,
            paidUntil = paidUntil,
            createdAt = createdAt
        )
}

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

@Serializable
data class BillingPreview(
    val episodicFraction: Double = 0.2,
    val movieMs: Long = 1_200_000L
)

@Serializable
private data class BillingStatusResponse(
    val user: AuthUserResponse,
    val premium: Boolean = false,
    val monthlyFee: Int = 1000,
    val currency: String = "NGN",
    val freePreview: BillingPreview = BillingPreview()
)

@Serializable
data class BillingCheckout(
    val link: String = "",
    val txRef: String = "",
    val amount: Int = 1000,
    val currency: String = "NGN",
    val alreadyPremium: Boolean = false,
    val premium: Boolean = false
)
