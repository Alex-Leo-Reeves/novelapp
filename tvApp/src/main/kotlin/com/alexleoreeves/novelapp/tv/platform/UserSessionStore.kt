package com.alexleoreeves.novelapp.tv.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

typealias SavedUserAccount = com.alexleoreeves.novelapp.platform.SavedUserAccount

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
