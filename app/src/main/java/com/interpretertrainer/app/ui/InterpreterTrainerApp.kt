package com.interpretertrainer.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.interpretertrainer.app.ai.AiPracticeBridge
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
    const val PRIVACY = "privacy"
    const val HISTORY_DETAIL = "history/{id}"
}

@Composable
fun InterpreterTrainerApp(
    sessionViewModel: SessionViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val navController = rememberNavController()
    val navigate: (String) -> Unit = remember(navController) {
        { route ->
            if (navController.currentDestination?.route != route) {
                navController.navigate(route) {
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { forwardEnterTransition() },
        exitTransition = { forwardExitTransition() },
        popEnterTransition = { backwardEnterTransition() },
        popExitTransition = { backwardExitTransition() }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigate = navigate,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange
            )
        }
        composable(Routes.SIMULTANEOUS) {
            SimultaneousScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.SHADOWING) {
            ShadowingScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.CONSECUTIVE) {
            ConsecutiveScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.TRANSCRIPTION) {
            LiveTranscriptionScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenAiCoach = { navigate(Routes.AI_COACH) }
            )
        }
        composable(Routes.AI_COACH) {
            AiCoachScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpenPractice = { mode ->
                    val destination = when (mode) {
                        AiPracticeBridge.MODE_SIMULTANEOUS -> Routes.SIMULTANEOUS
                        AiPracticeBridge.MODE_SHADOWING -> Routes.SHADOWING
                        AiPracticeBridge.MODE_CONSECUTIVE -> Routes.CONSECUTIVE
                        AiPracticeBridge.MODE_TRANSCRIPTION -> Routes.TRANSCRIPTION
                        else -> null
                    }
                    if (destination != null) {
                        navController.navigate(destination) {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = navController::popBackStack,
                sessionViewModel = sessionViewModel,
                onOpen = { id -> navigate("history/$id") }
            )
        }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = navController::popBackStack) }
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

private const val ScreenMotionDurationMillis = 240
private const val ScreenFadeDurationMillis = 180

private fun forwardEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(ScreenFadeDurationMillis, delayMillis = 35)) +
        slideInHorizontally(
            initialOffsetX = { width -> (width * 0.08f).toInt() },
            animationSpec = tween(ScreenMotionDurationMillis, easing = FastOutSlowInEasing)
        )

private fun forwardExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(ScreenFadeDurationMillis)) +
        slideOutHorizontally(
            targetOffsetX = { width -> -(width * 0.025f).toInt() },
            animationSpec = tween(ScreenMotionDurationMillis, easing = FastOutSlowInEasing)
        )

private fun backwardEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(ScreenFadeDurationMillis, delayMillis = 25)) +
        slideInHorizontally(
            initialOffsetX = { width -> -(width * 0.025f).toInt() },
            animationSpec = tween(ScreenMotionDurationMillis, easing = FastOutSlowInEasing)
        )

private fun backwardExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(ScreenFadeDurationMillis)) +
        slideOutHorizontally(
            targetOffsetX = { width -> (width * 0.08f).toInt() },
            animationSpec = tween(ScreenMotionDurationMillis, easing = FastOutSlowInEasing)
        )
