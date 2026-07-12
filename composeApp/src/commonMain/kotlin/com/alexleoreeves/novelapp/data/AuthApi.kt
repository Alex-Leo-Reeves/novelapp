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
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(AuthRequest(username = username, email = email, password = password, recoverySecret = recoverySecret))
        }
        return response.toSavedUserAccount()
    }

    suspend fun login(email: String, password: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/login") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(AuthRequest(email = email, password = password))
        }
        return response.toSavedUserAccount()
    }

    // ── OTP Auth methods (Supabase Auth proxy) ──────────────────────────

    /**
     * Create Account Screen: fill username, email, password → otpSignup()
     * Server creates user + sends OTP email → client navigates to OTP screen.
     */
    suspend fun otpSignup(username: String, email: String, password: String) {
        val response = client.post("$baseUrl/auth/otp/signup") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(OtpSignupRequest(username = username, email = email, password = password))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val error = runCatching { authJson.decodeFromString<AuthErrorResponse>(rawBody).error }
                .getOrNull() ?: "Signup failed."
            throw AuthApiException(error, response.status.value)
        }
    }

    /**
     * OTP Screen (for signup or login): enter OTP code → verify.
     * Returns SavedUserAccount with session token → go to home.
     */
    suspend fun otpVerify(email: String, token: String, type: String = "magiclink"): SavedUserAccount {
        val response = client.post("$baseUrl/auth/otp/verify") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(OtpVerifyRequest(email = email, token = token, type = type))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.Created) {
            val error = runCatching { authJson.decodeFromString<AuthErrorResponse>(rawBody).error }
                .getOrNull() ?: "OTP verification failed."
            throw AuthApiException(error, response.status.value)
        }

        // For recovery (forgot password) flow, the response has accessToken instead of user session.
        // The caller should detect this and use the accessToken in otpSetNewPassword().
        val payload = authJson.decodeFromString<OtpVerifyResponse>(rawBody)
        if (payload.needsNewPassword == true) {
            throw OtpRecoveryRequiredException(payload.accessToken ?: "", email)
        }

        val sessionToken = payload.token ?: throw IllegalStateException("Session token was missing.")
        return payload.user!!.toAccount(sessionToken)
    }

    /**
     * Forgot Password Screen 1: enter email → sends OTP
     */
    suspend fun otpForgotPassword(email: String) {
        val response = client.post("$baseUrl/auth/otp/forgot-password") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(OtpEmailRequest(email = email))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val error = runCatching { authJson.decodeFromString<AuthErrorResponse>(rawBody).error }
                .getOrNull() ?: "Could not send recovery email."
            throw AuthApiException(error, response.status.value)
        }
    }

    /**
     * Forgot Password Screen 2: enter OTP → verify.
     * If valid, returns accessToken (throws OtpRecoveryRequiredException).
     */
    suspend fun otpVerifyRecovery(email: String, token: String): String {
        try {
            otpVerify(email, token, type = "recovery")
            // If it didn't throw, something is wrong
            throw AuthApiException("Unexpected response from recovery verification.", 500)
        } catch (e: OtpRecoveryRequiredException) {
            return e.accessToken
        }
    }

    /**
     * Forgot Password Screen 3: set new password + confirm.
     * This signs the user in and returns the session.
     */
    suspend fun otpSetNewPassword(email: String, accessToken: String, newPassword: String): SavedUserAccount {
        val response = client.post("$baseUrl/auth/otp/reset-password") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(OtpResetPasswordRequest(accessToken = accessToken, password = newPassword, email = email))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val error = runCatching { authJson.decodeFromString<AuthErrorResponse>(rawBody).error }
                .getOrNull() ?: "Password reset failed."
            throw AuthApiException(error, response.status.value)
        }
        val payload = authJson.decodeFromString<AuthResponse>(rawBody)
        val sessionToken = payload.token ?: throw IllegalStateException("Session token was missing.")
        return payload.user.toAccount(sessionToken)
    }

    /**
     * Login Screen: enter email → sends OTP (if user exists).
     * Then call otpVerify() with the code.
     */
    suspend fun otpLogin(email: String) {
        val response = client.post("$baseUrl/auth/otp/login") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            setBody(OtpEmailRequest(email = email))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val error = runCatching { authJson.decodeFromString<AuthErrorResponse>(rawBody).error }
                .getOrNull() ?: "Login failed."
            throw AuthApiException(error, response.status.value)
        }
    }

    suspend fun me(token: String): SavedUserAccount {
        val response = client.get("$baseUrl/auth/me") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
        }
        return response.toSavedUserAccount(existingToken = token)
    }

    suspend fun logout(token: String) {
        client.post("$baseUrl/auth/logout") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
        }
    }

    suspend fun getUserState(token: String): UserSyncState {
        val response = client.get("$baseUrl/user/state") {
            accept(ContentType.parse("application/json"))
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
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
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
            accept(ContentType.parse("application/json"))
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
            currentPlan = payload.currentPlan,
            monthlyFee = payload.monthlyFee,
            currency = payload.currency,
            maxDevices = payload.maxDevices,
            plans = payload.plans,
            freePreview = payload.freePreview
        )
    }

    suspend fun createBillingCheckout(token: String, planId: String = "premium_3_devices"): BillingCheckout {
        val response = client.post("$baseUrl/billing/checkout") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(BillingCheckoutRequest(planId = planId))
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

/** Thrown when OTP verify is for recovery (forgot password) and needs a new password set. */
class OtpRecoveryRequiredException(
    val accessToken: String,
    val email: String
) : Exception("OTP verified. Please set a new password.")

data class BillingStatus(
    val account: SavedUserAccount,
    val premium: Boolean,
    val currentPlan: String,
    val monthlyFee: Int,
    val currency: String,
    val maxDevices: Int?,
    val plans: List<BillingPlan>,
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

// ── OTP request types ──────────────────────────────────────────────────

@Serializable
private data class OtpSignupRequest(
    val username: String,
    val email: String,
    val password: String
)

@Serializable
private data class OtpEmailRequest(
    val email: String
)

@Serializable
private data class OtpVerifyRequest(
    val email: String,
    val token: String,
    val type: String = "magiclink"
)

@Serializable
private data class OtpResetPasswordRequest(
    val accessToken: String,
    val password: String,
    val email: String = ""
)

@Serializable
private data class AuthResponse(
    val token: String? = null,
    val user: AuthUserResponse
)

@Serializable
private data class OtpVerifyResponse(
    val token: String? = null,
    val user: AuthUserResponse? = null,
    val accessToken: String? = null,
    val needsNewPassword: Boolean? = null,
    val email: String? = null
)

@Serializable
private data class AuthUserResponse(
    val id: String = "",
    val username: String,
    val email: String,
    val plan: String = "free",
    val billingStatus: String = "none",
    val paidUntil: String? = null,
    val createdAt: String = "",
    val maxDevices: Int? = 2
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
            createdAt = createdAt,
            maxDevices = maxDevices
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
private data class BillingCheckoutRequest(
    val planId: String
)

@Serializable
data class BillingPlan(
    val id: String,
    val label: String,
    val amount: Int,
    val currency: String = "NGN",
    val maxDevices: Int? = null,
    val premium: Boolean = true,
    val description: String = ""
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
    val currentPlan: String = "free",
    val monthlyFee: Int = 1000,
    val currency: String = "NGN",
    val maxDevices: Int? = 2,
    val plans: List<BillingPlan> = emptyList(),
    val freePreview: BillingPreview = BillingPreview()
)

@Serializable
data class BillingCheckout(
    val link: String = "",
    val txRef: String = "",
    val amount: Int = 1000,
    val currency: String = "NGN",
    val plan: BillingPlan? = null,
    val alreadyPremium: Boolean = false,
    val premium: Boolean = false
)
