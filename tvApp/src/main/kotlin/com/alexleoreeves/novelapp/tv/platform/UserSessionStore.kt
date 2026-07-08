package com.alexleoreeves.novelapp.tv.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SavedUserAccount(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val authToken: String = "",
    val plan: String = "free",
    val billingStatus: String = "none",
    val paidUntil: String? = null,
    val createdAt: String = "",
    val maxDevices: Int? = 2
) {
    val isPremium: Boolean
        get() = plan == "premium" || plan == "premium_3_devices" || plan == "premium_unlimited" || billingStatus == "active"
}

class UserSessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("novelapp_tv_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadAccount(): SavedUserAccount? {
        val raw = prefs.getString("account", null) ?: return null
        return try { json.decodeFromString<SavedUserAccount>(raw) } catch (_: Exception) { null }
    }

    fun saveAccount(account: SavedUserAccount) {
        prefs.edit().putString("account", json.encodeToString(account)).apply()
    }

    fun clearAccount() {
        prefs.edit().remove("account").apply()
    }
}
