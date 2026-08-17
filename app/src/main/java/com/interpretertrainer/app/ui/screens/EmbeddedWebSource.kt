package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val INTERPRETER_WEB_ORIGIN = "https://interpreter-trainer.app/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedWebSource(
    url: String,
    modifier: Modifier = Modifier
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { webView ->
                runCatching { webView.stopLoading() }
                runCatching { webView.loadUrl("about:blank") }
                runCatching { webView.destroy() }
            }
            webViewRef.value = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val sourceWebView = WebView(context).apply {
                setBackgroundColor(Color.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != "http" && scheme != "https"
                    }
                }
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(sourceWebView, true)
            }
            sourceWebView.tag = url
            loadEmbeddedSource(sourceWebView, url)
            webViewRef.value = sourceWebView
            sourceWebView
        },
        update = { webView ->
            if (webView.tag != url) {
                webView.tag = url
                loadEmbeddedSource(webView, url)
            }
        }
    )
}

private fun loadEmbeddedSource(webView: WebView, url: String) {
    val needsReferrer = url.contains("youtube.com/embed/") || url.contains("player.vimeo.com/video/")
    if (needsReferrer) {
        webView.loadUrl(url, mapOf("Referer" to INTERPRETER_WEB_ORIGIN))
    } else {
        webView.loadUrl(url)
    }
}
