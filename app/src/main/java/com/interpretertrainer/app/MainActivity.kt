package com.interpretertrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.interpretertrainer.app.ui.InterpreterTrainerApp
import com.interpretertrainer.app.ui.theme.InterpreterTrainerTheme
import com.interpretertrainer.app.viewmodel.SessionViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as InterpreterTrainerApplication
        setContent {
            InterpreterTrainerTheme {
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModel.Factory(app.sessionRepository)
                )
                InterpreterTrainerApp(sessionViewModel)
            }
        }
    }
}
