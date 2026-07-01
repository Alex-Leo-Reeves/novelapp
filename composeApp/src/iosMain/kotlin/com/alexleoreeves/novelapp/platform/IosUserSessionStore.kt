package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSUserDefaults

class IosUserSessionStore : UserSessionStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun loadAccount(): SavedUserAccount? {
        val id = defaults.stringForKey(KEY_ID).orEmpty()
        val username = defaults.stringForKey(KEY_USERNAME).orEmpty()
        val email = defaults.stringForKey(KEY_EMAIL).orEmpty()
        val authToken = defaults.stringForKey(KEY_AUTH_TOKEN).orEmpty()

        return if (username.isBlank() || email.isBlank() || authToken.isBlank()) {
            null
        } else {
            SavedUserAccount(
                id = id,
                username = username,
                email = email,
                authToken = authToken,
                plan = defaults.stringForKey(KEY_PLAN).orEmpty().ifBlank { "free" },
                billingStatus = defaults.stringForKey(KEY_BILLING_STATUS).orEmpty().ifBlank { "none" },
                paidUntil = defaults.stringForKey(KEY_PAID_UNTIL),
                createdAt = defaults.stringForKey(KEY_CREATED_AT).orEmpty()
            )
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        defaults.setObject(account.id, forKey = KEY_ID)
        defaults.setObject(account.username, forKey = KEY_USERNAME)
        defaults.setObject(account.email, forKey = KEY_EMAIL)
        defaults.setObject(account.authToken, forKey = KEY_AUTH_TOKEN)
        defaults.setObject(account.plan, forKey = KEY_PLAN)
        defaults.setObject(account.billingStatus, forKey = KEY_BILLING_STATUS)
        account.paidUntil?.let { defaults.setObject(it, forKey = KEY_PAID_UNTIL) }
            ?: defaults.removeObjectForKey(KEY_PAID_UNTIL)
        defaults.setObject(account.createdAt, forKey = KEY_CREATED_AT)
    }

    override fun clearAccount() {
        defaults.removeObjectForKey(KEY_ID)
        defaults.removeObjectForKey(KEY_USERNAME)
        defaults.removeObjectForKey(KEY_EMAIL)
        defaults.removeObjectForKey(KEY_AUTH_TOKEN)
        defaults.removeObjectForKey(KEY_PLAN)
        defaults.removeObjectForKey(KEY_BILLING_STATUS)
        defaults.removeObjectForKey(KEY_PAID_UNTIL)
        defaults.removeObjectForKey(KEY_CREATED_AT)
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_PLAN = "plan"
        const val KEY_BILLING_STATUS = "billing_status"
        const val KEY_PAID_UNTIL = "paid_until"
        const val KEY_CREATED_AT = "created_at"
    }
}
