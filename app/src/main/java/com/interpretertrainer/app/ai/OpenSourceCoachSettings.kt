package com.interpretertrainer.app.ai

import android.content.Context

object OpenSourceCoachSettings {
    const val MODEL_LABEL = "Qwen2.5-1.5B-Instruct"

    private const val PREFS = "open_source_interpreter_ai"
    private const val KEY_SERVER_URL = "server_url"

    fun getServerUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "")
            .orEmpty()

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
            .apply()
    }
}
