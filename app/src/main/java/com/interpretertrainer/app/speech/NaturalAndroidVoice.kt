package com.interpretertrainer.app.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.interpretertrainer.app.model.LanguageOption
import java.util.Locale

/**
 * Chooses an installed Android voice for Interpreter Trainer's supported languages.
 *
 * Normal practice modes still prioritize voice quality. Interpreter AI's live-call path requests
 * 0.98f and is deliberately treated as latency-sensitive: local voices and lower reported latency
 * are preferred, and speech is made slightly faster so turn-taking feels conversational.
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

        val liveConversation = speechRate in 0.95f..0.99f
        val matchingVoices = tts.voices.orEmpty()
            .filter { voice -> voice.locale.language.equals(locale.language, ignoreCase = true) }

        val candidates = if (liveConversation) {
            matchingVoices.sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenBy { it.latency }
                    .thenByDescending { it.quality }
                    .thenByDescending { it.locale.country.equals(locale.country, ignoreCase = true) }
            )
        } else {
            matchingVoices.sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.latency }
                    .thenByDescending { it.locale.country.equals(locale.country, ignoreCase = true) }
            )
        }

        candidates.firstOrNull()?.let { best ->
            runCatching { tts.voice = best }
        }

        val requestedRate = if (liveConversation) 1.08f else speechRate
        tts.setSpeechRate(requestedRate.coerceIn(0.72f, 1.18f))
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
