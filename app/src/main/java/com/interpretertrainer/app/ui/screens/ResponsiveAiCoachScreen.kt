package com.interpretertrainer.app.ui.screens

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.interpretertrainer.app.speech.InterpreterLiveNativeBridge
import com.interpretertrainer.app.ui.theme.ThemeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Keeps Interpreter AI visually synchronized with the app theme and installs the low-latency,
 * interruptible live-voice layer after the existing coach page has initialized.
 *
 * Important: the coach itself must keep the real Activity context. Supplying a synthetic
 * configuration Context to the WebView/permission/dialog stack can make some Android builds crash
 * as soon as the AI screen is opened. Theme synchronization is therefore done inside the page with
 * CSS variables instead of replacing LocalContext.
 */
@Composable
fun ResponsiveAiCoachScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    themeMode: ThemeMode
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val liveBridge = remember(context) {
        InterpreterLiveNativeBridge(context.applicationContext)
    }
    val attachedWebView = remember { mutableStateOf<WebView?>(null) }
    val coachHtml = remember(context) {
        context.assets.open("interpreter_coach.html")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val nativeDuplexPatch = remember(context) {
        context.assets.open("interpreter_live_native_duplex.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val voicePatch = remember(context) {
        context.assets.open("interpreter_fast_voice.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val preciseBargeInPatch = remember(context) {
        context.assets.open("interpreter_precise_barge_in.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val liveLatencyPatch = remember(context) {
        context.assets.open("interpreter_live_latency.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val standardArabicPatch = remember(context) {
        context.assets.open("interpreter_standard_arabic.js")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    DisposableEffect(liveBridge) {
        onDispose {
            liveBridge.dispose()
            attachedWebView.value = null
        }
    }

    // Keep the normal Android Activity context for WebView, microphone permission and Puter auth.
    AiCoachScreen(onBack = onBack, sessionViewModel = sessionViewModel)

    LaunchedEffect(
        rootView,
        darkTheme,
        liveBridge,
        coachHtml,
        nativeDuplexPatch,
        voicePatch,
        preciseBargeInPatch,
        liveLatencyPatch,
        standardArabicPatch
    ) {
        // Slow mobile WebView/Puter startup can exceed a few seconds. Keep retrying for about ten
        // seconds, while still returning immediately as soon as every voice layer reports ready.
        repeat(90) {
            val webView = findCoachWebView(rootView)
            if (webView != null) {
                // addJavascriptInterface objects become visible to page JavaScript on the next load.
                // Attach the native duplex bridge once, then reload the same bundled coach HTML.
                if (attachedWebView.value !== webView) {
                    liveBridge.attachWebView(webView)
                    webView.addJavascriptInterface(liveBridge, "InterpreterLiveNative")
                    attachedWebView.value = webView
                    webView.loadDataWithBaseURL(
                        "https://interpreter-trainer.app/",
                        coachHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                    delay(260)
                    return@repeat
                }

                runCatching {
                    webView.setBackgroundColor(if (darkTheme) 0xFF0E0F12.toInt() else 0xFFFBFBFD.toInt())
                    webView.evaluateJavascript(themeSyncScript(darkTheme), null)
                    webView.evaluateJavascript(standardArabicPatch, null)
                }

                // Order matters. The native duplex proxy must exist before the fast voice layer
                // captures window.InterpreterNative, otherwise it would keep using remote TTS.
                if (evaluateForResult(webView, nativeDuplexPatch)) {
                    if (evaluateForResult(webView, voicePatch)) {
                        if (evaluateForResult(webView, preciseBargeInPatch)) {
                            if (evaluateForResult(webView, liveLatencyPatch)) return@LaunchedEffect
                        }
                    }
                }
            }
            delay(120)
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

private fun themeSyncScript(darkTheme: Boolean): String = if (darkTheme) {
    """
    (() => {
      const root = document.documentElement;
      root.setAttribute('data-app-theme','dark');
      root.style.colorScheme = 'dark';
      const values = {
        '--bg':'#0e0f12','--surface':'#141519','--soft':'#1d1f24','--surface-soft':'#1d1f24',
        '--surface-strong':'#292c33','--text':'#f4f4f6','--muted':'#b7bac2','--faint':'#848995',
        '--accent':'#aeb0ff','--accent2':'#b896ff','--accent-soft':'#252541','--accent-ink':'#d0d1ff',
        '--border':'#2d3037','--danger':'#ffb4ac','--ok':'#76dfa8','--user':'#24262c',
        '--shadow':'0 18px 55px rgba(0,0,0,.32)'
      };
      Object.entries(values).forEach(([key,value]) => root.style.setProperty(key,value));
      return 'ready';
    })();
    """.trimIndent()
} else {
    """
    (() => {
      const root = document.documentElement;
      root.setAttribute('data-app-theme','light');
      root.style.colorScheme = 'light';
      const values = {
        '--bg':'#fbfbfd','--surface':'#ffffff','--soft':'#f2f3f7','--surface-soft':'#f2f3f7',
        '--surface-strong':'#e8eaf0','--text':'#17181c','--muted':'#727680','--faint':'#9b9fa8',
        '--accent':'#4b4ee8','--accent2':'#7b5cff','--accent-soft':'#eeeeff','--accent-ink':'#3539c8',
        '--border':'#e6e7ec','--danger':'#c9362b','--ok':'#138a55','--user':'#eff0f4',
        '--shadow':'0 18px 55px rgba(25,27,40,.10)'
      };
      Object.entries(values).forEach(([key,value]) => root.style.setProperty(key,value));
      return 'ready';
    })();
    """.trimIndent()
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
