package com.interpretertrainer.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.interpretertrainer.app.BuildConfig
import com.interpretertrainer.app.auth.AccountSession
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Bundled, network-isolated page only. Firebase tokens never cross the JS bridge. */
internal class BackendAiBridge(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var view: WebView? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var job: Job? = null
    @Volatile private var requestId: String? = null
    @Volatile private var disposed = false
    private val owner = AccountSession.uid()
    private val history by lazy { com.interpretertrainer.app.auth.AccountHistoryStore(context, requireNotNull(owner)) }
    @JavascriptInterface fun loadHistory(): String = try {
        if (owner == null || AccountSession.uid() != owner) "[]" else history.read()
    } catch (_: Exception) { "{\"error\":\"HISTORY_UNAVAILABLE\"}" }
    @JavascriptInterface fun saveHistory(value: String): Boolean = try {
        if (owner == null || AccountSession.uid() != owner) false else {
            val array = org.json.JSONArray(value)
            require(array.length() <= 20)
            history.save(value); true
        }
    } catch (_: Exception) { false }
    fun attach(webView: WebView) { view=webView }
    @JavascriptInterface fun accountId(): String = owner.orEmpty()
    @JavascriptInterface fun available(): Boolean = BuildConfig.AI_BACKEND_URL.startsWith("https://") && owner != null
    @JavascriptInterface fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    @JavascriptInterface fun start(id: String, body: String): Boolean {
        if (disposed || !id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || body.length>262144 || !active.compareAndSet(false,true)) return false
        requestId=id
        job=scope.launch {
            val deadline = scope.launch { delay(80000); connection?.disconnect(); job?.cancel() }
            try {
                if (!available()) { emitError(id,"CONFIGURATION_ERROR"); return@launch }
                if (!online()) { emitError(id,"NETWORK_UNAVAILABLE"); return@launch }
                val user=AccountSession.auth().currentUser
                if(user==null || user.uid!=owner) { emitError(id,"AUTH_EXPIRED"); return@launch }
                val token=withTimeout(15000) { user.getIdToken(false).await().token }
                    ?: throw IllegalStateException("Missing session")
                ensureActive()
                val payload=JSONObject(body).put("requestId",id)
                val url=URL(BuildConfig.AI_BACKEND_URL.trimEnd('/')+"/v1/chat")
                require(url.protocol=="https" && url.userInfo==null)
                val http=url.openConnection() as HttpURLConnection
                connection=http
                http.requestMethod="POST"; http.instanceFollowRedirects=false
                http.connectTimeout=10000; http.readTimeout=25000; http.doOutput=true; http.useCaches=false
                http.setRequestProperty("Authorization","Bearer $token")
                http.setRequestProperty("Content-Type","application/json; charset=utf-8")
                http.setRequestProperty("Accept","application/x-ndjson")
                val bytes=payload.toString().toByteArray(Charsets.UTF_8)
                http.setFixedLengthStreamingMode(bytes.size)
                debug("request_start",id)
                http.outputStream.use { it.write(bytes) }
                val status=http.responseCode
                debug("http_$status",id)
                if(status !in 200..299) {
                    emitError(id, when(status) {401->"AUTH_EXPIRED";403->"EMAIL_UNVERIFIED";409->"BUSY";429->"RATE_LIMITED";400,413->"INVALID_REQUEST";else->"SERVER_ERROR"})
                    return@launch
                }
                if(!http.contentType.orEmpty().startsWith("application/x-ndjson")) { emitError(id,"INVALID_RESPONSE"); return@launch }
                var complete=false; var total=0
                http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val line=StringBuilder(); val buffer=CharArray(2048)
                    while(!complete) {
                        ensureActive()
                        val count=reader.read(buffer); if(count<0) break
                        total+=count; if(total>2_000_000) throw IllegalArgumentException("Response size")
                        for(index in 0 until count) {
                            if(buffer[index]=='\n') {
                                if(line.isNotBlank()) {
                                    val event=JSONObject(line.toString())
                                    emit(id,event)
                                    if(event.optString("type") in listOf("done","error")) complete=true
                                }
                                line.setLength(0)
                            } else line.append(buffer[index])
                        }
                    }
                }
                if(!complete) emitError(id,"INVALID_RESPONSE")
                debug("stream_end",id)
            } catch (_: TimeoutCancellationException) { emitError(id,"TIMEOUT")
            } catch (e: CancellationException) { throw e
            } catch (_: SocketTimeoutException) { emitError(id,"TIMEOUT")
            } catch (_: java.io.IOException) { emitError(id,"NETWORK_UNAVAILABLE")
            } catch (_: Exception) { emitError(id,"INVALID_RESPONSE")
            } finally { deadline.cancel(); connection?.disconnect(); connection=null; active.set(false) }
        }
        return true
    }
    private fun emitError(id: String, code: String) { debug("error_$code",id); emit(id,JSONObject().put("type","error").put("code",code)) }
    private fun emit(id: String,event: JSONObject) { handler.post {
        if(!disposed && id==requestId && AccountSession.uid()==owner) view?.evaluateJavascript(
            "window.TrainerBackend?.onEvent(${JSONObject.quote(id)},$event)",null)
    } }
    private fun debug(event: String,id: String) { if(BuildConfig.DEBUG) android.util.Log.d("InterpreterAI","event=$event requestId=$id") }
    @JavascriptInterface fun cancel(id: String) {
        if(id!=requestId) return
        requestId=null; job?.cancel(); connection?.disconnect()
    }
    fun dispose() { disposed=true; requestId=null; job?.cancel(); connection?.disconnect(); scope.cancel(); handler.removeCallbacksAndMessages(null); view=null }
}
