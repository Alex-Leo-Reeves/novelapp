package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSUserDefaults

class IosUserSessionStore : UserSessionStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun loadAccount(): SavedUserAccount? {
        val username = defaults.stringForKey(KEY_USERNAME).orEmpty()
        val email = defaults.stringForKey(KEY_EMAIL).orEmpty()
        val authToken = defaults.stringForKey(KEY_AUTH_TOKEN).orEmpty()

        return if (username.isBlank() || email.isBlank() || authToken.isBlank()) {
            null
        } else {
            SavedUserAccount(username = username, email = email, authToken = authToken)
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        defaults.setObject(account.username, forKey = KEY_USERNAME)
        defaults.setObject(account.email, forKey = KEY_EMAIL)
        defaults.setObject(account.authToken, forKey = KEY_AUTH_TOKEN)
    }

    override fun clearAccount() {
        defaults.removeObjectForKey(KEY_USERNAME)
        defaults.removeObjectForKey(KEY_EMAIL)
        defaults.removeObjectForKey(KEY_AUTH_TOKEN)
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_AUTH_TOKEN = "auth_token"
    }
}
