package com.interpretertrainer.app.ai

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Used only by the bundled coach when WebView's authenticated XHR fails. No arbitrary URLs. */
internal class NativeAiNetworkBridge {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val calls = ConcurrentHashMap<String, PuterChatHttpClient>()
    @Volatile private var disposed = false
    private var webView: WebView? = null

    fun attachWebView(view: WebView) { webView = view }

    @JavascriptInterface
    fun startChat(id: String, body: String): Boolean {
        if (disposed || !id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || body.length > 262_144) return false
        val valid = runCatching {
            val request = JSONObject(body)
            request.optString("interface") == "puter-chat-completion" &&
                request.optString("driver") == "ai-chat" &&
                request.optString("method") == "complete" &&
                !request.optBoolean("test_mode", false) &&
                request.optString("auth_token").isNotBlank() &&
                request.getJSONObject("args").getJSONArray("messages").length() > 0
        }.getOrDefault(false)
        if (!valid || calls.size >= 2) return false
        val client = PuterChatHttpClient()
        if (calls.putIfAbsent(id, client) != null) return false
        return runCatching {
            executor.execute {
                try {
                    client.execute(body) { kind, data, status ->
                        // JSONObject.quote prevents provider text from becoming executable JS.
                        val event = JSONObject().put("kind", kind).put("data", data).put("status", status)
                        handler.post {
                            val view = webView
                            if (!disposed && view?.url == COACH_URL) {
                                view.evaluateJavascript(
                                    "window.InterpreterAiProvider?.onNativeEvent(${JSONObject.quote(id)}, $event);",
                                    null
                                )
                            }
                        }
                    }
                } finally {
                    calls.remove(id, client)
                }
            }
            true
        }.getOrElse {
            calls.remove(id, client)
            client.cancel()
            false
        }
    }

    @JavascriptInterface
    fun cancelChat(id: String) { calls.remove(id)?.cancel() }

    fun dispose() {
        disposed = true
        calls.values.forEach(PuterChatHttpClient::cancel)
        calls.clear()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        webView = null
    }

    private companion object {
        const val COACH_URL = "https://appassets.androidplatform.net/assets/interpreter_coach.html"
    }
}
