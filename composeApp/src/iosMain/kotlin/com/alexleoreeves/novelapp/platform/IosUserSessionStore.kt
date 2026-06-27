package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSUserDefaults

class IosUserSessionStore : UserSessionStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun loadAccount(): SavedUserAccount? {
        val username = defaults.stringForKey(KEY_USERNAME).orEmpty()
        val email = defaults.stringForKey(KEY_EMAIL).orEmpty()
        val password = defaults.stringForKey(KEY_PASSWORD).orEmpty()

        return if (username.isBlank() || email.isBlank() || password.isBlank()) {
            null
        } else {
            SavedUserAccount(username = username, email = email, password = password)
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        defaults.setObject(account.username, forKey = KEY_USERNAME)
        defaults.setObject(account.email, forKey = KEY_EMAIL)
        defaults.setObject(account.password, forKey = KEY_PASSWORD)
    }

    override fun clearAccount() {
        defaults.removeObjectForKey(KEY_USERNAME)
        defaults.removeObjectForKey(KEY_EMAIL)
        defaults.removeObjectForKey(KEY_PASSWORD)
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
