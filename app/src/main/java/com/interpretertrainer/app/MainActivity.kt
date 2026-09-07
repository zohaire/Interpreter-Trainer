package com.interpretertrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.interpretertrainer.app.auth.LocalProfile
import com.interpretertrainer.app.ui.InterpreterTrainerApp
import com.interpretertrainer.app.ui.theme.InterpreterTrainerTheme
import com.interpretertrainer.app.ui.theme.ThemePreferences
import com.interpretertrainer.app.viewmodel.SessionViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as InterpreterTrainerApplication
        setContent {
            var themeMode by remember { mutableStateOf(ThemePreferences.get(this)) }
            InterpreterTrainerTheme(themeMode = themeMode) {
                val sessions: SessionViewModel = viewModel(
                    key = "sessions_${LocalProfile.id}",
                    factory = SessionViewModel.Factory(app.repositoryFor(LocalProfile.id))
                )
                InterpreterTrainerApp(
                    sessionViewModel = sessions,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        ThemePreferences.set(this@MainActivity, mode)
                        themeMode = mode
                    }
                )
            }
        }
    }
}
