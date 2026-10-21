package com.interpretertrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.interpretertrainer.app.ui.screens.TikTokReviewerScreen
import com.interpretertrainer.app.ui.theme.InterpreterTrainerTheme
import com.interpretertrainer.app.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InterpreterTrainerTheme(themeMode = ThemePreferences.get(this)) {
                TikTokReviewerScreen()
            }
        }
    }
}
