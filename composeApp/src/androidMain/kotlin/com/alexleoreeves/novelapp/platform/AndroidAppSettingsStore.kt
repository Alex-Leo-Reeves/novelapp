package com.alexleoreeves.novelapp.platform

import android.content.Context
import com.alexleoreeves.novelapp.data.AppSettingsStore
import java.util.Locale

/**
 * Android (phone/tablet) settings store.
 *
 * Backed by SharedPreferences — unlike the session store there is no install
 * sentinel here: settings are non-sensitive UI preferences and should survive
 * a reinstall-restore like any normal app preference.
 */
class AndroidAppSettingsStore(context: Context) : AppSettingsStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun showServerSelectors(): Boolean =
        prefs.getBoolean(KEY_SHOW_SERVER_SELECTORS, false)

    override fun setShowServerSelectors(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SERVER_SELECTORS, enabled).apply()
    }

    override fun languageOverride(): String? =
        prefs.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }

    override fun setLanguageOverride(code: String?) {
        prefs.edit()
            .also { editor ->
                if (code.isNullOrBlank()) editor.remove(KEY_LANGUAGE)
                else editor.putString(KEY_LANGUAGE, code)
            }
            .apply()
    }

    override fun deviceLanguageCode(): String =
        Locale.getDefault().toString().replace('_', '-')

    private companion object {
        const val PREFS_NAME = "novelapp_settings"
        const val KEY_SHOW_SERVER_SELECTORS = "show_server_selectors"
        const val KEY_LANGUAGE = "language"
    }
}
