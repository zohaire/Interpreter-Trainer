package com.interpretertrainer.app.privacy

import android.content.Context

/** Stores the user's explicit choice to enable online Interpreter AI features. */
object AiPrivacyPreferences {
    private const val PREFS = "interpreter_trainer_privacy"
    private const val KEY_AI_DISCLOSURE_VERSION = "ai_disclosure_version"
    private const val CURRENT_DISCLOSURE_VERSION = 1

    fun hasAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AI_DISCLOSURE_VERSION, 0) >= CURRENT_DISCLOSURE_VERSION

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_AI_DISCLOSURE_VERSION, CURRENT_DISCLOSURE_VERSION)
            .apply()
    }

    fun revoke(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_AI_DISCLOSURE_VERSION)
            .apply()
    }
}
