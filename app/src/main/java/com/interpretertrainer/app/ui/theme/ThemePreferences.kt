package com.interpretertrainer.app.ui.theme

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

object ThemePreferences {
    private const val PREFS = "interpreter_trainer_ui"
    private const val KEY_THEME = "theme_mode"

    fun get(context: Context): ThemeMode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(stored.orEmpty()) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}
