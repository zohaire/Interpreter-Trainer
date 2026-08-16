package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.viewmodel.SessionViewModel
import java.util.Locale

/**
 * Interpreter Coach uses an online Qwen model through Puter.js.
 *
 * There are no on-device model weights, model downloads, provider API keys, or private backend
 * endpoints in the APK. Puter handles browser authentication and AI access for each user.
 */
@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val sessions by sessionViewModel.sessions.collectAsState()
    val bridge = remember { PracticeContextBridge() }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    SideEffect {
        bridge.contextValue = buildPracticeContext(sessions)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { webView ->
                runCatching { webView.stopLoading() }
                runCatching { webView.removeJavascriptInterface("InterpreterNative") }
                runCatching { webView.destroy() }
            }
            webViewRef.value = null
        }
    }

    TrainerScaffold("Interpreter Coach", onBack) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                createCoachWebView(context, bridge).also { webViewRef.value = it }
            },
            update = { webView ->
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
        )
    }
}

private class PracticeContextBridge {
    @Volatile
    var contextValue: String = "No saved practice sessions yet."

    @JavascriptInterface
    fun getPracticeContext(): String = contextValue
}

@SuppressLint("SetJavaScriptEnabled")
private fun createCoachWebView(context: Context, bridge: PracticeContextBridge): WebView {
    val html = context.assets.open("interpreter_coach.html")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    configureCoachWebView(webView)
    webView.addJavascriptInterface(bridge, "InterpreterNative")
    webView.webViewClient = WebViewClient()
    webView.webChromeClient = CoachChromeClient(context)

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    // A stable HTTPS origin gives Puter.js normal browser auth/cookie semantics while the page
    // itself remains bundled with the APK.
    webView.loadDataWithBaseURL(
        "https://interpreter-trainer.app/",
        html,
        "text/html",
        "UTF-8",
        null
    )
    return webView
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureCoachWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_DEFAULT
    }
}

/**
 * Puter authentication opens a JavaScript popup. Android WebView delivers that popup through
 * onCreateWindow(), but a Dialog whose window stays WRAP_CONTENT can collapse a MATCH_PARENT
 * child WebView to effectively 0x0. The user then only sees the dimmed parent screen and closing
 * it makes Puter report auth_window_closed.
 *
 * Keep the popup as a true child WebView (so window.opener/postMessage and shared cookies keep
 * working), but place it in a full-screen container and force the Dialog window itself to
 * MATCH_PARENT after show().
 */
private class CoachChromeClient(private val context: Context) : WebChromeClient() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }
        configureCoachWebView(popup)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(popup, true)
        }

        val dialog = Dialog(context)
        val popupContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            addView(
                popup,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        popup.webViewClient = WebViewClient()
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        dialog.setContentView(popupContainer)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            runCatching { popup.stopLoading() }
            runCatching { popup.destroy() }
        }

        transport.webView = popup
        resultMsg.sendToTarget()

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return true
    }
}

private fun buildPracticeContext(sessions: List<PracticeSessionEntity>): String = buildString {
    if (sessions.isEmpty()) {
        append("No saved practice sessions yet.")
        return@buildString
    }

    appendLine("Recent saved Interpreter Trainer practice:")
    sessions.sortedByDescending { it.startedAt }.take(5).forEachIndexed { index, session ->
        append(
            "${index + 1}. ${readableMode(session.practiceMode)}; " +
                "${session.sourceLanguage} -> ${session.targetLanguage}; " +
                "${session.durationMillis / 1000}s"
        )
        session.notes.takeIf { it.isNotBlank() }?.let {
            append("; notes=${it.replace('\n', ' ').take(180)}")
        }
        session.aiFeedback?.takeIf { it.isNotBlank() }?.let {
            append("; saved feedback=${it.replace('\n', ' ').take(350)}")
        }
        appendLine()
    }
}.take(5_000)

private fun readableMode(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
    "SIMULTANEOUS_INTERPRETATION" -> "Simultaneous Interpretation"
    "SHADOWING" -> "Shadowing"
    "CONSECUTIVE" -> "Consecutive Interpretation"
    "SIGHT_TRANSLATION" -> "Sight Translation"
    "LIVE_TRANSCRIPTION" -> "Live Transcription"
    else -> mode.replace('_', ' ')
}
