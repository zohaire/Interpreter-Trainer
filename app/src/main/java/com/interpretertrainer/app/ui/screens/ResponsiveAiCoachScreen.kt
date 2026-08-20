package com.interpretertrainer.app.ui.screens

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.interpretertrainer.app.ui.theme.ThemeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Keeps Interpreter AI visually synchronized with the app theme and installs the low-latency,
 * interruptible live-voice layer after the existing coach page has initialized.
 */
@Composable
fun ResponsiveAiCoachScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    themeMode: ThemeMode
) {
    val baseContext = LocalContext.current
    val rootView = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val webContext = remember(baseContext, darkTheme) {
        val configuration = Configuration(baseContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        baseContext.createConfigurationContext(configuration)
    }

    val voicePatch = remember(baseContext) {
        baseContext.assets.open("interpreter_fast_voice.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    CompositionLocalProvider(LocalContext provides webContext) {
        AiCoachScreen(onBack = onBack, sessionViewModel = sessionViewModel)
    }

    LaunchedEffect(rootView, darkTheme, voicePatch) {
        repeat(36) {
            val webView = findCoachWebView(rootView)
            if (webView != null) {
                webView.evaluateJavascript(themeSyncScript(darkTheme), null)
                if (evaluateForResult(webView, voicePatch)) return@LaunchedEffect
            }
            delay(160)
        }
    }
}

private fun findCoachWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findCoachWebView(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}

private fun themeSyncScript(darkTheme: Boolean): String {
    val theme = if (darkTheme) "dark" else "light"
    return "document.documentElement.setAttribute('data-app-theme','$theme');" +
        "document.documentElement.style.colorScheme='$theme';'ready';"
}

private suspend fun evaluateForResult(webView: WebView, script: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        runCatching {
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result?.contains("ready") == true)
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(false)
        }
    }
