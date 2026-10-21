package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Creator workspace using the existing Puter authentication and online AI service. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TikTokReviewerScreen() {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingFile by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingFile?.onReceiveValue(uri?.let { arrayOf(it) })
        pendingFile = null
    }
    DisposableEffect(Unit) {
        onDispose {
            pendingFile?.onReceiveValue(null)
            webView?.stopLoading()
            webView?.destroy()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        factory = { context ->
            WebView(context).apply {
                configureCoachWebView(this)
                settings.allowContentAccess = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                }
                webChromeClient = object : CoachChromeClient(context) {
                    override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                        pendingFile?.onReceiveValue(null)
                        pendingFile = callback
                        picker.launch(arrayOf("video/*"))
                        return true
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                val html = context.assets.open("tiktok_reviewer.html").bufferedReader().use { it.readText() }
                    .replace("/* REVIEWER_SCRIPT */", context.assets.open("tiktok_reviewer.js").bufferedReader().use { it.readText() })
                loadDataWithBaseURL("https://interpreter-trainer.app/", html, "text/html", "UTF-8", null)
                webView = this
            }
        }
    )
}
