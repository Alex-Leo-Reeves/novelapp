package com.alexleoreeves.novelapp.platform

import android.content.Context

class AndroidUserSessionStore(context: Context) : UserSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("novelapp_user", Context.MODE_PRIVATE)

    override fun loadAccount(): SavedUserAccount? {
        val id = prefs.getString(KEY_ID, null).orEmpty()
        val username = prefs.getString(KEY_USERNAME, null).orEmpty()
        val email = prefs.getString(KEY_EMAIL, null).orEmpty()
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null).orEmpty()

        return if (username.isBlank() || email.isBlank() || authToken.isBlank()) {
            null
        } else {
            SavedUserAccount(
                id = id,
                username = username,
                email = email,
                authToken = authToken,
                plan = prefs.getString(KEY_PLAN, "free").orEmpty().ifBlank { "free" },
                billingStatus = prefs.getString(KEY_BILLING_STATUS, "none").orEmpty().ifBlank { "none" },
                createdAt = prefs.getString(KEY_CREATED_AT, "").orEmpty()
            )
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        prefs.edit()
            .putString(KEY_ID, account.id)
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_AUTH_TOKEN, account.authToken)
            .putString(KEY_PLAN, account.plan)
            .putString(KEY_BILLING_STATUS, account.billingStatus)
            .putString(KEY_CREATED_AT, account.createdAt)
            .commit()
    }

    override fun clearAccount() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_PLAN = "plan"
        const val KEY_BILLING_STATUS = "billing_status"
        const val KEY_CREATED_AT = "created_at"
    }
}
