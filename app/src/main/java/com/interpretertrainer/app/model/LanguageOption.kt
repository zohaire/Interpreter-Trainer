package com.interpretertrainer.app.model

enum class LanguageOption(val tag: String, val label: String) {
    // Legacy enum name retained for source compatibility; the app now uses Modern Standard Arabic.
    ARABIC_MOROCCO("ar-SA", "Arabic (MSA)"),
    ENGLISH_US("en-US", "English"),
    FRENCH_FRANCE("fr-FR", "French")
}
