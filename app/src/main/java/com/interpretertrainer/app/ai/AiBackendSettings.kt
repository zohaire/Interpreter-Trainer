package com.interpretertrainer.app.ai

import android.content.Context

object AiBackendSettings {
    private const val PREFS = "ai_backend_settings"
    private const val KEY_URL = "backend_url"

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, "")
            .orEmpty()

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .apply()
    }
}
