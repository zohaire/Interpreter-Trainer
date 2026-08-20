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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps Interpreter AI synchronized with the app theme and installs the online AI + voice layers.
 *
 * Voice UI is deliberately NOT gated on the advanced full-duplex bridge. The microphone button,
 * language selector and Interpreter Live controls must always appear and remain usable through the
 * normal InterpreterNative bridge even when a device cannot expose the optional low-latency duplex
 * bridge. This avoids the WebView race that previously left users with a text-only coach screen.
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

    val coachHtml = remember(context) { readAsset(context, "interpreter_coach.html") }
    val aiBootstrapPatch = remember(context) { readAsset(context, "interpreter_ai_bootstrap.js") }
    val nativeDuplexPatch = remember(context) { readAsset(context, "interpreter_live_native_duplex.js") }
    val voicePatch = remember(context) { readAsset(context, "interpreter_fast_voice.js") }
    val preciseBargeInPatch = remember(context) { readAsset(context, "interpreter_precise_barge_in.js") }
    val liveLatencyPatch = remember(context) { readAsset(context, "interpreter_live_latency.js") }
    val standardArabicPatch = remember(context) { readAsset(context, "interpreter_standard_arabic.js") }

    DisposableEffect(liveBridge) {
        onDispose {
            liveBridge.dispose()
            attachedWebView.value = null
        }
    }

    // AiCoachScreen owns the Activity-context WebView and the reliable native mic/TTS bridge.
    AiCoachScreen(onBack = onBack, sessionViewModel = sessionViewModel)

    LaunchedEffect(
        rootView,
        darkTheme,
        liveBridge,
        coachHtml,
        aiBootstrapPatch,
        nativeDuplexPatch,
        voicePatch,
        preciseBargeInPatch,
        liveLatencyPatch,
        standardArabicPatch
    ) {
        // Retry long enough for slow Android WebView/Puter startup. Voice controls are installed as
        // soon as the coach DOM exists; optional duplex readiness can never block their appearance.
        repeat(120) {
            val webView = findCoachWebView(rootView)
            if (webView != null) {
                if (attachedWebView.value !== webView) {
                    // JavascriptInterface objects are exposed to JavaScript after a navigation.
                    // Attach the optional duplex bridge once and reload the bundled coach once.
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
                    delay(320)
                } else if (evaluateForResult(webView, coachDomReadyScript())) {
                    runCatching {
                        webView.setBackgroundColor(
                            if (darkTheme) 0xFF0E0F12.toInt() else 0xFFFBFBFD.toInt()
                        )
                        webView.evaluateJavascript(themeSyncScript(darkTheme), null)
                    }

                    // Critical fix: install the core bootstrap first and independently. It creates
                    // voiceLang, voiceBtn, voiceCallLaunch and voiceCallOverlay itself.
                    runCatching { webView.evaluateJavascript(aiBootstrapPatch, null) }
                    runCatching { webView.evaluateJavascript(standardArabicPatch, null) }

                    // Advanced native duplex is optional. A pending/unsupported duplex layer must
                    // never prevent the normal microphone + voice conversation layer from loading.
                    runCatching { webView.evaluateJavascript(nativeDuplexPatch, null) }
                    runCatching { webView.evaluateJavascript(voicePatch, null) }
                    runCatching { webView.evaluateJavascript(preciseBargeInPatch, null) }
                    runCatching { webView.evaluateJavascript(liveLatencyPatch, null) }

                    // Re-apply the bootstrap once after the optional layers. It is idempotent and
                    // also refreshes the visible AIV5-LIVE connection marker on supported builds.
                    runCatching { webView.evaluateJavascript(aiBootstrapPatch, null) }

                    if (evaluateForResult(webView, voiceUiReadyScript())) {
                        return@LaunchedEffect
                    }
                }
            }
            delay(140)
        }
    }
}

private fun readAsset(context: android.content.Context, name: String): String =
    context.assets.open(name)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

private fun findCoachWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findCoachWebView(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}

private fun coachDomReadyScript(): String = """
(() => {
  const ready = (document.readyState === 'interactive' || document.readyState === 'complete') &&
    !!document.querySelector('.composer') && !!document.querySelector('.composer-shell') &&
    !!document.getElementById('sendBtn');
  return ready ? 'ready' : 'pending';
})();
""".trimIndent()

private fun voiceUiReadyScript(): String = """
(() => {
  const ids = ['voiceLang','voiceBtn','voiceCallLaunch','voiceCallOverlay'];
  const visible = ids.every(id => !!document.getElementById(id));
  return visible ? 'ready' : 'pending';
})();
""".trimIndent()

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
