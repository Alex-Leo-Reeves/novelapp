package com.alexleoreeves.novelapp.platform

import android.content.Context

class AndroidUserSessionStore(context: Context) : UserSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("novelapp_user", Context.MODE_PRIVATE)

    override fun loadAccount(): SavedUserAccount? {
        val username = prefs.getString(KEY_USERNAME, null).orEmpty()
        val email = prefs.getString(KEY_EMAIL, null).orEmpty()
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null).orEmpty()

        return if (username.isBlank() || email.isBlank() || authToken.isBlank()) {
            null
        } else {
            SavedUserAccount(username = username, email = email, authToken = authToken)
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        prefs.edit()
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_AUTH_TOKEN, account.authToken)
            .apply()
    }

    override fun clearAccount() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_AUTH_TOKEN = "auth_token"
    }
}
