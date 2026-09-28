package com.alexleoreeves.novelapp.tv.platform

import android.content.Context
import com.alexleoreeves.novelapp.data.AppSettingsStore
import java.util.Locale

/**
 * Android TV settings store — same keys as the phone build
 * (`AndroidAppSettingsStore`) but a separate prefs file, since the TV app
 * installs side-by-side with a different applicationId.
 */
class TvAppSettingsStore(context: Context) : AppSettingsStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        const val PREFS_NAME = "novelapp_tv_settings"
        const val KEY_SHOW_SERVER_SELECTORS = "show_server_selectors"
        const val KEY_LANGUAGE = "language"
    }
}
