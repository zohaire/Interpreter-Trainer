package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection
import java.io.ByteArrayInputStream
import java.time.Instant
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
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        if (uri.scheme != "https" || uri.host != "interpreter-trainer.app" || uri.path != "/profile-data") return null
                        return profileDataResponse(uri.getQueryParameter("username").orEmpty())
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return true
                        if (uri.scheme == "reviewer-download" && uri.host == "video" && request.isForMainFrame && request.hasGesture()) {
                            val target = Uri.parse(uri.getQueryParameter("url").orEmpty())
                            if (target.scheme == "https" && !target.host.isNullOrBlank() && target.userInfo == null) {
                                try {
                                    val download = DownloadManager.Request(target)
                                        .setTitle("TikTok Reviewer generated video")
                                        .setMimeType("video/mp4")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TikTokReviewer-${System.currentTimeMillis()}.mp4")
                                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(download)
                                    Toast.makeText(context, "Video download started. Check Downloads.", Toast.LENGTH_LONG).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not start download. Your clip is saved in Puter.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        return true
                    }
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

/** Only fetch the public creator embed. No login cookies, credentials or arbitrary URLs. */
private fun profileDataResponse(username: String): WebResourceResponse {
    val data = try {
        require(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,23}").matches(username)) { "Enter a valid TikTok username." }
        val connection = URL("https://www.tiktok.com/embed/@$username").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "text/html")
            check(connection.responseCode == 200) { "TikTok could not return this public profile. Try again later." }
            val html = connection.inputStream.bufferedReader().use { it.readTextLimited(2_000_000) }
            parsePublicProfile(html, username)
        } finally { connection.disconnect() }
    } catch (e: Exception) {
        JSONObject().put("error", if (e is IllegalArgumentException || e is IllegalStateException) e.message else "TikTok could not be reached. Check your connection and try again.")
    }
    return WebResourceResponse("application/json", "UTF-8", 200, "OK",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data.toString().toByteArray(Charsets.UTF_8)))
}

private fun java.io.Reader.readTextLimited(limit: Int): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(result.length + count <= limit) { "TikTok returned an unexpectedly large page." }
        result.append(buffer, 0, count)
    }
    return result.toString()
}

internal fun parsePublicProfile(html: String, username: String): JSONObject {
    val state = Regex("""<script[^>]*id=["']__FRONTITY_CONNECT_STATE__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1)
    check(state != null) { "TikTok did not provide readable public posts. The profile may be unavailable or TikTok may be limiting requests." }
    val source = JSONObject(state).getJSONObject("source").getJSONObject("data")
    val page = source.optJSONObject("/embed/@$username")
        ?: throw IllegalStateException("TikTok did not return the requested profile.")
    val info = page.optJSONObject("userInfo")
        ?: throw IllegalStateException("This public profile is unavailable.")
    check(!page.optBoolean("isError") && !info.optBoolean("privateAccount")) { "This profile is not available for public review." }
    check(info.optString("uniqueId").equals(username, ignoreCase = true)) { "TikTok returned a different profile. Please check the username." }
    val result = JSONObject().put("username", info.optString("uniqueId"))
        .put("displayName", info.optString("nickname")).put("bio", info.optString("signature"))
        .put("sourceUrl", "https://www.tiktok.com/@$username")
        .put("retrievedAt", Instant.now().toString())
    for ((input, output) in listOf("followerCount" to "followers", "followingCount" to "following", "heartCount" to "totalLikes")) {
        if (info.opt(input) is Number) result.put(output, info.get(input))
    }
    val posts = JSONArray()
    val list = page.optJSONArray("videoList") ?: JSONArray()
    for (i in 0 until minOf(list.length(), 10)) {
        val item = list.optJSONObject(i) ?: continue
        val id = item.optString("id")
        if (!Regex("[0-9]+").matches(id) || item.optBoolean("privateItem")) continue
        val post = JSONObject().put("id", id).put("caption", item.optString("desc"))
            .put("url", "https://www.tiktok.com/@$username/video/$id")
        if (item.opt("playCount") is Number) post.put("views", item.get("playCount"))
        posts.put(post)
    }
    return result.put("posts", posts).put("limits", "Public embed snapshot, up to ten posts. Counts may be rounded. No post ages, watch time, per-post likes, comments, shares, audio or video content were retrieved. View differences alone do not establish quality or cause.")
}
