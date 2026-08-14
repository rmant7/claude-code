package com.whispertranscriber.app

/**
 * Languages offered for transcription. Whisper can auto-detect, but it decides from only the first
 * 30 seconds of audio — so a recording that opens with noise, music or a stray phrase in another
 * language can be mis-detected, and every later window then inherits that wrong choice. Naming the
 * language up front removes that failure mode entirely, which matters most for the languages
 * Whisper is weakest at.
 */
data class WhisperLanguage(val code: String, val displayName: String) {

    companion object {
        const val AUTO_CODE = "auto"

        val ALL = listOf(
            WhisperLanguage(AUTO_CODE, "Auto-detect"),
            WhisperLanguage("en", "English"),
            WhisperLanguage("he", "Hebrew / עברית"),
            WhisperLanguage("ru", "Russian / Русский"),
            WhisperLanguage("uk", "Ukrainian / Українська"),
            WhisperLanguage("ar", "Arabic / العربية"),
            WhisperLanguage("de", "German / Deutsch"),
            WhisperLanguage("fr", "French / Français"),
            WhisperLanguage("es", "Spanish / Español"),
            WhisperLanguage("it", "Italian / Italiano"),
            WhisperLanguage("pt", "Portuguese / Português"),
            WhisperLanguage("pl", "Polish / Polski"),
            WhisperLanguage("tr", "Turkish / Türkçe"),
            WhisperLanguage("nl", "Dutch / Nederlands"),
            WhisperLanguage("zh", "Chinese / 中文"),
            WhisperLanguage("ja", "Japanese / 日本語"),
            WhisperLanguage("ko", "Korean / 한국어"),
            WhisperLanguage("hi", "Hindi / हिन्दी"),
        )
    }
}
