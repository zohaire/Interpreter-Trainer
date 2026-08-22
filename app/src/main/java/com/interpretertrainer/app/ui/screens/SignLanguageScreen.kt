package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun SignLanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val fileCallbackRef = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileCallbackRef.value?.onReceiveValue(uris.toTypedArray())
        fileCallbackRef.value = null
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallbackRef.value?.onReceiveValue(null)
            fileCallbackRef.value = null
            webViewRef.value?.let { webView ->
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
            webViewRef.value = null
        }
    }

    TrainerScaffold("Sign Language", onBack) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { webContext ->
                createSignLanguageWebView(webContext) { callback ->
                    fileCallbackRef.value?.onReceiveValue(null)
                    fileCallbackRef.value = callback
                    filePicker.launch(arrayOf("*/*"))
                    true
                }.also { webViewRef.value = it }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createSignLanguageWebView(
    context: android.content.Context,
    onFileChooser: (ValueCallback<Array<Uri>>) -> Boolean
): WebView {
    val html = context.assets.open("sign_language_emulator.html")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webViewClient = WebViewClient()
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val callback = filePathCallback ?: return false
                return onFileChooser(callback)
            }
        }
        loadDataWithBaseURL(
            "https://interpreter-trainer.app/sign-language/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }
}
