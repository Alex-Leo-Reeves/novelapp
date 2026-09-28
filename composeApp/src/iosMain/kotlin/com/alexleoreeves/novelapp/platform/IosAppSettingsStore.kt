package com.alexleoreeves.novelapp.platform

import com.alexleoreeves.novelapp.data.AppSettingsStore
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

/**
 * iOS settings store, backed by NSUserDefaults (persisted in the app's
 * standard defaults, so it survives relaunches and device backups).
 */
class IosAppSettingsStore : AppSettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun showServerSelectors(): Boolean =
        defaults.boolForKey(KEY_SHOW_SERVER_SELECTORS)

    override fun setShowServerSelectors(enabled: Boolean) {
        defaults.setBool(enabled, forKey = KEY_SHOW_SERVER_SELECTORS)
    }

    override fun languageOverride(): String? =
        defaults.stringForKey(KEY_LANGUAGE)?.takeIf { it.isNotBlank() }

    override fun setLanguageOverride(code: String?) {
        if (code.isNullOrBlank()) {
            defaults.removeObjectForKey(KEY_LANGUAGE)
        } else {
            defaults.setObject(code, forKey = KEY_LANGUAGE)
        }
    }

    /**
     * First entry of `NSLocale.preferredLanguages` — the user's ordered iOS
     * language list, e.g. `es-MX`. Falls back to the raw `AppleLanguages`
     * default, then to `en` so the caller always gets a usable tag.
     */
    override fun deviceLanguageCode(): String {
        val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
        if (!preferred.isNullOrBlank()) return preferred
        val raw = defaults.arrayForKey(APPLE_LANGUAGES_KEY)?.firstOrNull() as? String
        return raw?.takeIf { it.isNotBlank() } ?: "en"
    }

    private companion object {
        const val KEY_SHOW_SERVER_SELECTORS = "show_server_selectors"
        const val KEY_LANGUAGE = "language"
        const val APPLE_LANGUAGES_KEY = "AppleLanguages"
    }
}
