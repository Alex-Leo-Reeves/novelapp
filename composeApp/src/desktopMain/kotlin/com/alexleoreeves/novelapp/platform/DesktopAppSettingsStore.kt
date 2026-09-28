package com.alexleoreeves.novelapp.platform

import com.alexleoreeves.novelapp.data.AppSettingsStore
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Desktop (Windows/macOS/Linux) settings store.
 *
 * Uses the JVM's Preferences API, which maps to the registry on Windows and
 * a plist/dotfile elsewhere — no extra file management needed.
 */
class DesktopAppSettingsStore : AppSettingsStore {
    private val prefs: Preferences = Preferences.userRoot().node("com/alexleoreeves/novelapp")

    override fun showServerSelectors(): Boolean =
        prefs.getBoolean(KEY_SHOW_SERVER_SELECTORS, false)

    override fun setShowServerSelectors(enabled: Boolean) {
        prefs.putBoolean(KEY_SHOW_SERVER_SELECTORS, enabled)
    }

    override fun languageOverride(): String? =
        prefs.get(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }

    override fun setLanguageOverride(code: String?) {
        if (code.isNullOrBlank()) {
            prefs.remove(KEY_LANGUAGE)
        } else {
            prefs.put(KEY_LANGUAGE, code)
        }
    }

    override fun deviceLanguageCode(): String =
        Locale.getDefault().toString().replace('_', '-')

    private companion object {
        const val KEY_SHOW_SERVER_SELECTORS = "show_server_selectors"
        const val KEY_LANGUAGE = "language"
    }
}
