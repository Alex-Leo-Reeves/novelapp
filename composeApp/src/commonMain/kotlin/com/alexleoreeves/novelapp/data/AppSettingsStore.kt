package com.alexleoreeves.novelapp.data

/**
 * Languages the app can run in.
 *
 * [SYSTEM] is the default: the app follows the device's own language, so a
 * phone set to Spanish starts in Spanish with no user action. Picking an
 * explicit entry overrides the device language until the user switches back.
 *
 * This enum is deliberately pure Kotlin (no `expect`) so the shared `data`
 * package can be consumed verbatim by both `composeApp` and `tvApp` — the
 * latter compiles this directory via `kotlin.srcDir` and excludes any file
 * that declares `expect`.
 */
enum class AppLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String
) {
    SYSTEM("", "Device language", "Device language"),
    ENGLISH("en", "English", "English"),
    SPANISH("es", "Spanish", "Español"),
    FRENCH("fr", "French", "Français"),
    GERMAN("de", "German", "Deutsch"),
    PORTUGUESE("pt", "Portuguese", "Português"),
    ITALIAN("it", "Italian", "Italiano"),
    RUSSIAN("ru", "Russian", "Русский"),
    ARABIC("ar", "Arabic", "العربية"),
    HINDI("hi", "Hindi", "हिन्दी"),
    CHINESE("zh", "Chinese", "中文"),
    JAPANESE("ja", "Japanese", "日本語"),
    KOREAN("ko", "Korean", "한국어"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia"),
    TURKISH("tr", "Turkish", "Türkçe"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt"),
    SWAHILI("sw", "Swahili", "Kiswahili");

    companion object {
        /** Resolves a BCP-47 tag (`es-MX`, `pt_BR`, `zh-Hans-CN`) to a language. */
        fun fromTag(tag: String?): AppLanguage? {
            val base = tag
                ?.trim()
                ?.lowercase()
                ?.replace('_', '-')
                ?.substringBefore('-')
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return entries.firstOrNull { it != SYSTEM && it.code == base }
        }
    }
}

/**
 * Persisted user settings shared by every platform.
 *
 * Mirrors the [com.alexleoreeves.novelapp.platform.UserSessionStore] shape:
 * a plain interface in common code, implemented per platform by each app.
 */
interface AppSettingsStore {

    /**
     * Whether the manual streaming-server selectors (movies / anime / donghua)
     * are shown on detail screens. Off by default — the app auto-picks the best
     * server, and the selectors are an opt-in power-user control.
     */
    fun showServerSelectors(): Boolean
    fun setShowServerSelectors(enabled: Boolean)

    /** Explicit language choice, or `null` to follow the device language. */
    fun languageOverride(): String?
    fun setLanguageOverride(code: String?)

    /** The device's current language as a BCP-47 tag, e.g. `en-US`, `es`. */
    fun deviceLanguageCode(): String

    /** The language the UI should use right now: the override, else the device's. */
    fun activeLanguage(): AppLanguage {
        AppLanguage.fromTag(languageOverride())?.let { return it }
        return AppLanguage.fromTag(deviceLanguageCode()) ?: AppLanguage.ENGLISH
    }
}

/** No-op store: defaults only, used for previews and as the `App()` fallback. */
object DefaultAppSettingsStore : AppSettingsStore {
    override fun showServerSelectors(): Boolean = false
    override fun setShowServerSelectors(enabled: Boolean) = Unit
    override fun languageOverride(): String? = null
    override fun setLanguageOverride(code: String?) = Unit
    override fun deviceLanguageCode(): String = "en"
}
