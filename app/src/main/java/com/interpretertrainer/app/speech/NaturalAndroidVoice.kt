package com.interpretertrainer.app.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.interpretertrainer.app.model.LanguageOption
import java.util.Locale

/**
 * Chooses the best available installed Android voice for Interpreter Trainer's supported languages.
 * Network voices are allowed when they offer materially higher quality; Android TTS remains a
 * fallback behind the online Interpreter Live voices.
 */
object NaturalAndroidVoice {
    fun localeFor(language: LanguageOption): Locale = when (language) {
        LanguageOption.ARABIC_MOROCCO -> Locale("ar", "MA")
        LanguageOption.FRENCH_FRANCE -> Locale.FRANCE
        LanguageOption.ENGLISH_US -> Locale.US
    }

    fun localeForTag(tag: String): Locale = when (tag.lowercase(Locale.ROOT)) {
        "ar", "ar-ma", "arabic" -> Locale("ar", "MA")
        "fr", "fr-fr", "french" -> Locale.FRANCE
        else -> Locale.US
    }

    fun configure(tts: TextToSpeech, language: LanguageOption, speechRate: Float = 1f): Boolean {
        return configure(tts, localeFor(language), speechRate)
    }

    fun configure(tts: TextToSpeech, languageTag: String, speechRate: Float = 1f): Boolean {
        return configure(tts, localeForTag(languageTag), speechRate)
    }

    private fun configure(tts: TextToSpeech, locale: Locale, speechRate: Float): Boolean {
        val availability = tts.setLanguage(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false
        }

        val candidates = tts.voices.orEmpty()
            .filter { voice -> voice.locale.language.equals(locale.language, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.latency }
                    .thenByDescending { it.locale.country.equals(locale.country, ignoreCase = true) }
            )

        candidates.firstOrNull()?.let { best ->
            runCatching { tts.voice = best }
        }

        tts.setSpeechRate(speechRate.coerceIn(0.72f, 1.18f))
        tts.setPitch(
            when (locale.language.lowercase(Locale.ROOT)) {
                "ar" -> 0.96f
                "fr" -> 1.01f
                else -> 0.99f
            }
        )
        return true
    }
}
