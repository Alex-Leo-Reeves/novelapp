package com.alexleoreeves.novelapp.data

import androidx.compose.runtime.mutableStateOf

/**
 * App-wide runtime language state (compose-observable).
 *
 * The chosen language is PERSISTED by [AppSettingsStore]; this singleton holds
 * the *effective* language of the running session so layers that don't receive
 * a Compose environment (TmdbSource, repositories, screens across files) can
 * read it — and so changing the language visibly re-localizes the app:
 *
 *  * [tmdbLanguage] re-queries TMDB in that language → every catalog title,
 *    overview and genre label switches language (the most visible effect);
 *  * [AppStrings.active] translates the navigation / settings chrome.
 *
 * Reading [current] or [effective] inside a composable subscribes it to
 * changes, so a live language switch recomposes the affected UI immediately.
 */
object AppLanguageState {
    private val state = mutableStateOf(AppLanguage.SYSTEM)

    /** The language the user picked (SYSTEM = follow the device). */
    var current: AppLanguage
        get() = state.value
        set(value) {
            state.value = value
        }

    /** Device BCP-47 tag (e.g. "es-MX"), used to resolve SYSTEM. */
    var deviceTag: String = "en"

    fun update(language: AppLanguage, deviceLanguageTag: String = deviceTag) {
        state.value = language
        if (deviceLanguageTag.isNotBlank()) deviceTag = deviceLanguageTag
    }

    /** The language actually in effect: SYSTEM resolves through the device tag. */
    val effective: AppLanguage
        get() = if (current == AppLanguage.SYSTEM) {
            AppLanguage.fromTag(deviceTag) ?: AppLanguage.ENGLISH
        } else {
            current
        }

    /**
     * TMDB `language` query value, or null to keep TMDB's default (en-US).
     * English keeps TMDB's native behaviour; every other language re-requests
     * titles/overviews/genres in that language.
     */
    val tmdbLanguage: String?
        get() = when (val lang = effective) {
            AppLanguage.ENGLISH -> null
            else -> tmdbTag(lang)
        }

    private fun tmdbTag(lang: AppLanguage): String? = when (lang) {
        AppLanguage.SPANISH -> "es-ES"
        AppLanguage.FRENCH -> "fr-FR"
        AppLanguage.GERMAN -> "de-DE"
        AppLanguage.PORTUGUESE -> "pt-BR"
        AppLanguage.ITALIAN -> "it-IT"
        AppLanguage.RUSSIAN -> "ru-RU"
        AppLanguage.ARABIC -> "ar-SA"
        AppLanguage.HINDI -> "hi-IN"
        AppLanguage.CHINESE -> "zh-CN"
        AppLanguage.JAPANESE -> "ja-JP"
        AppLanguage.KOREAN -> "ko-KR"
        AppLanguage.INDONESIAN -> "id-ID"
        AppLanguage.TURKISH -> "tr-TR"
        AppLanguage.VIETNAMESE -> "vi-VN"
        AppLanguage.SWAHILI -> "sw-KE"
        else -> null
    }
}
