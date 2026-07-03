package com.alexleoreeves.novelapp.platform

import android.content.Context
import java.io.File

class AndroidUserSessionStore(context: Context) : UserSessionStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val installSentinel = File(appContext.noBackupFilesDir, INSTALL_SENTINEL_FILE)

    override fun loadAccount(): SavedUserAccount? {
        if (!installSentinel.exists()) {
            if (hasStoredAccount()) {
                prefs.edit().clear().commit()
            }
            markCurrentInstall()
            return null
        }

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
                paidUntil = prefs.getString(KEY_PAID_UNTIL, null),
                createdAt = prefs.getString(KEY_CREATED_AT, "").orEmpty(),
                maxDevices = prefs.takeIf { it.contains(KEY_MAX_DEVICES) }?.getInt(KEY_MAX_DEVICES, 2)
            )
        }
    }

    override fun saveAccount(account: SavedUserAccount) {
        markCurrentInstall()
        prefs.edit()
            .putString(KEY_ID, account.id)
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_AUTH_TOKEN, account.authToken)
            .putString(KEY_PLAN, account.plan)
            .putString(KEY_BILLING_STATUS, account.billingStatus)
            .putString(KEY_PAID_UNTIL, account.paidUntil)
            .putString(KEY_CREATED_AT, account.createdAt)
            .also { editor ->
                account.maxDevices?.let { editor.putInt(KEY_MAX_DEVICES, it) }
                    ?: editor.remove(KEY_MAX_DEVICES)
            }
            .commit()
    }

    override fun clearAccount() {
        markCurrentInstall()
        prefs.edit().clear().commit()
    }

    private fun hasStoredAccount(): Boolean =
        !prefs.getString(KEY_EMAIL, null).isNullOrBlank() ||
            !prefs.getString(KEY_AUTH_TOKEN, null).isNullOrBlank()

    private fun markCurrentInstall() {
        runCatching {
            installSentinel.parentFile?.mkdirs()
            if (!installSentinel.exists()) {
                installSentinel.writeText("novelapp-local-install\n")
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "novelapp_user"
        const val INSTALL_SENTINEL_FILE = "novelapp_user_session.local"
        const val KEY_ID = "id"
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_PLAN = "plan"
        const val KEY_BILLING_STATUS = "billing_status"
        const val KEY_PAID_UNTIL = "paid_until"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_MAX_DEVICES = "max_devices"
    }
}
