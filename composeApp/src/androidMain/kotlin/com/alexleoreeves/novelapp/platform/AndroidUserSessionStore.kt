package com.alexleoreeves.novelapp.platform

import android.content.Context

class AndroidUserSessionStore(context: Context) : UserSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("novelapp_user", Context.MODE_PRIVATE)

    override fun loadAccount(): SavedUserAccount? {
        val username = prefs.getString(KEY_USERNAME, null).orEmpty()
        val email = prefs.getString(KEY_EMAIL, null).orEmpty()
        val password = prefs.getString(KEY_PASSWORD, null).orEmpty()

        return if (username.isBlank() || email.isBlank() || password.isBlank()) {
            null
        } else {
            SavedUserAccount(username = username, email = email, password = password)
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        prefs.edit()
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_PASSWORD, account.password)
            .apply()
    }

    override fun clearAccount() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
