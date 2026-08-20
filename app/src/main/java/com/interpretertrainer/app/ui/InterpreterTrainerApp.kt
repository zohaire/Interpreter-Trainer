package com.interpretertrainer.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.interpretertrainer.app.ui.screens.*
import com.interpretertrainer.app.ui.theme.ThemeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel

object Routes {
    const val HOME = "home"
    const val SIMULTANEOUS = "simultaneous"
    const val SHADOWING = "shadowing"
    const val CONSECUTIVE = "consecutive"
    const val TRANSCRIPTION = "transcription"
    const val AI_COACH = "ai-coach"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{id}"
}

@Composable
fun InterpreterTrainerApp(
    sessionViewModel: SessionViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigate = navController::navigate,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange
            )
        }
        composable(Routes.SIMULTANEOUS) {
            SimultaneousScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navController.navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.SHADOWING) {
            ShadowingScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navController.navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.CONSECUTIVE) {
            ConsecutiveScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navController.navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.TRANSCRIPTION) {
            LiveTranscriptionScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navController.navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.AI_COACH) {
            // Use the coach-owned WebView directly. The previous responsive wrapper reloaded the
            // page and patched it after composition, which could leave a perfectly rendered but
            // non-interactive surface on some Android/WebView combinations. AiCoachScreen owns
            // the real AndroidView/WebView and its native microphone/TTS bridge from creation.
            AiCoachScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel
            )
        }
        composable(Routes.HISTORY) { HistoryScreen(onBack = navController::popBackStack, sessionViewModel) { id -> navController.navigate("history/$id") } }
        composable(
            Routes.HISTORY_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            HistoryDetailScreen(
                id = backStackEntry.arguments?.getLong("id") ?: 0,
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel
            )
        }
    }
}
