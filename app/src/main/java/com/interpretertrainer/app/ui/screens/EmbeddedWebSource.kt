package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.View
import com.interpretertrainer.app.media.TranscriptionPlayback
import org.json.JSONObject
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
                TranscriptionPlayback.unregister(webView)
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
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    safeBrowsingEnabled = true
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
            TranscriptionPlayback.register(sourceWebView) { ready ->
                sourceWebView.evaluateJavascript(
                    if (ready) "window.restoreAfterRecognition?.()" else "window.prepareForRecognition?.()", null
                )
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
    val uri = Uri.parse(url)
    if (uri.host == "www.youtube.com" && uri.path.orEmpty().startsWith("/embed/")) {
        val videoId = uri.lastPathSegment.orEmpty()
        val template = webView.context.assets.open("interpreter_video_player.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(INTERPRETER_WEB_ORIGIN,
            template.replace("__VIDEO_ID_JSON__", JSONObject.quote(videoId)), "text/html", "UTF-8", null)
    } else if (uri.host == "player.vimeo.com") {
        webView.loadUrl(url, mapOf("Referer" to INTERPRETER_WEB_ORIGIN))
    } else {
        webView.loadUrl(url)
    }
}
