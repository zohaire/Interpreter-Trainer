package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.interpretertrainer.app.ai.BackendAiBridge
import com.interpretertrainer.app.ai.AiPracticeBridge
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.speech.MicrophoneSessionCoordinator
import com.interpretertrainer.app.speech.NaturalAndroidVoice
import com.interpretertrainer.app.viewmodel.SessionViewModel
import org.json.JSONObject
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.privacy.AiPrivacyPreferences

/**
 * Online Interpreter AI through the authenticated application backend.
 *
 * The WebView owns the chat UX. Native Android supplies reliable microphone capture and a TTS
 * fallback. Microphone ownership is shared with all practice modes so AI voice, transcription and
 * MediaRecorder never fight over the same hardware input.
 */
@Composable
fun AiCoachScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    onOpenPractice: (String) -> Unit
) {
    val context = LocalContext.current
    val accepted = remember(context) {
        mutableStateOf(AiPrivacyPreferences.hasAccepted(context))
    }

    if (!accepted.value) {
        AiPrivacyDisclosure(
            onBack = onBack,
            onAccept = {
                AiPrivacyPreferences.accept(context)
                accepted.value = true
            }
        )
        return
    }

    ActiveAiCoachScreen(
        onBack = onBack,
        sessionViewModel = sessionViewModel,
        onOpenPractice = onOpenPractice
    )
}

@Composable
private fun AiPrivacyDisclosure(onBack: () -> Unit, onAccept: () -> Unit) {
    TrainerScaffold("Before you use Interpreter AI", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Interpreter AI is an optional online service.",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "To provide chat and evaluation, the app sends your messages, submitted evaluation material and up to five recent practice summaries—including saved notes or feedback—to the app’s authenticated backend and its configured AI provider.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Voice recognition may use Android's configured speech service. Spoken AI replies use Android text-to-speech and the voices installed on your device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "No neural model is downloaded. Your app account authenticates requests; provider credentials remain on the server. Core practice modes remain available if you go back.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Interpreter AI")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Not now")
            }
        }
    }
}

@Composable
private fun ActiveAiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel, onOpenPractice: (String) -> Unit) {
    val sessions by sessionViewModel.sessions.collectAsState()
    val context = LocalContext.current
    val bridgeHolder = remember { mutableStateOf<PracticeContextBridge?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bridgeHolder.value?.onMicrophonePermissionResult(granted)
    }

    val bridge = remember(context) {
        PracticeContextBridge(context.applicationContext, {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }, onOpenPractice)
    }

    SideEffect {
        bridgeHolder.value = bridge
        bridge.contextValue = buildPracticeContext(sessions)
    }

    DisposableEffect(Unit) {
        onDispose {
            bridge.dispose()
            webViewRef.value?.let { webView ->
                runCatching { webView.stopLoading() }
                runCatching { webView.removeJavascriptInterface("InterpreterNative") }
                runCatching { webView.destroy() }
            }
            webViewRef.value = null
            bridgeHolder.value = null
        }
    }

    TrainerScaffold("Interpreter Coach", onBack) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { webContext ->
                createCoachWebView(webContext, bridge).also { webViewRef.value = it }
            },
            update = { webView ->
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
            }
        )
    }
}

private class PracticeContextBridge(
    private val context: Context,
    private val requestMicrophonePermission: () -> Unit,
    private val onOpenPractice: (String) -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {
    val backend = BackendAiBridge(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val microphoneOwnerId = "ai-voice-${System.identityHashCode(this)}"

    private var webView: WebView? = null
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var recognitionActive = false
    private var recognitionStartToken = 0L

    @Volatile
    private var ttsReady = false

    @Volatile
    private var voiceLanguage = "en-US"

    private var pendingVoiceStart = false

    @Volatile
    var contextValue: String = buildPracticeContext(emptyList())

    init {
        mainHandler.post { textToSpeech = TextToSpeech(context, this) }
    }

    fun attachWebView(view: WebView) {
        webView = view
        backend.attach(view)
    }

    @JavascriptInterface
    fun getPracticeContext(): String {
        val uid = com.interpretertrainer.app.auth.AccountSession.uid() ?: return ""
        val languages = context.getSharedPreferences("preferences_$uid", Context.MODE_PRIVATE)
            .getStringSet("languages", setOf("English", "العربية الفصحى", "Français")).orEmpty()
        return contextValue + "\nPreferred languages: " + languages.joinToString(", ")
    }

    @JavascriptInterface
    fun sendToPractice(mode: String, text: String): Boolean {
        val accepted = AiPracticeBridge.sendToMode(mode, text)
        if (accepted) mainHandler.post { onOpenPractice(mode.trim().uppercase(Locale.ROOT)) }
        return accepted
    }

    @JavascriptInterface
    fun setVoiceLanguage(tag: String) {
        voiceLanguage = normalizeVoiceLanguage(tag)
    }

    @JavascriptInterface
    fun startVoiceInput() {
        mainHandler.post {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingVoiceStart = true
                requestMicrophonePermission()
                return@post
            }
            acquireAndBeginRecognition()
        }
    }

    @JavascriptInterface
    fun stopVoiceInput() {
        mainHandler.post {
            stopRecognition(releaseLease = true, notifyWeb = true)
        }
    }

    private fun acquireAndBeginRecognition() {
        MicrophoneSessionCoordinator.acquire(microphoneOwnerId) {
            stopRecognition(releaseLease = false, notifyWeb = true)
        }
        beginVoiceRecognition(recreate = false)
    }

    /** Returns true when a native fallback utterance can be queued. */
    @JavascriptInterface
    fun speakText(text: String, languageTag: String): Boolean {
        val clean = text.trim().take(8_000)
        if (clean.isBlank() || !ttsReady) return false

        val targetLanguage = normalizeVoiceLanguage(languageTag)
        mainHandler.post {
            val tts = textToSpeech ?: run {
                evaluateJs("window.__nativeSpeechFinished?.();")
                return@post
            }
            if (!NaturalAndroidVoice.configure(tts, targetLanguage, 0.98f)) {
                evaluateJs("window.__nativeSpeechFinished?.();")
                return@post
            }
            val result = tts.speak(
                clean,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "interpreter-ai-reply-${System.nanoTime()}"
            )
            if (result == TextToSpeech.ERROR) {
                evaluateJs("window.__nativeSpeechFinished?.();")
            }
        }
        return true
    }

    @JavascriptInterface
    fun stopSpeaking() {
        mainHandler.post { textToSpeech?.stop() }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        mainHandler.post {
            val shouldStart = pendingVoiceStart
            pendingVoiceStart = false
            if (granted && shouldStart) {
                acquireAndBeginRecognition()
            } else if (!granted) {
                sendVoiceError("Microphone permission is required for voice chat.")
            }
        }
    }

    private fun ensureRecognizer(recreate: Boolean) {
        if (recreate) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            recognitionActive = false
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        }
    }

    private fun beginVoiceRecognition(recreate: Boolean) {
        if (!MicrophoneSessionCoordinator.isOwner(microphoneOwnerId)) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            MicrophoneSessionCoordinator.release(microphoneOwnerId)
            sendVoiceError("Speech recognition is not available on this device.")
            return
        }

        if (recognitionActive) {
            runCatching { recognizer?.cancel() }
            recognitionActive = false
        }

        ensureRecognizer(recreate)
        val token = ++recognitionStartToken
        mainHandler.postDelayed({
            if (token != recognitionStartToken || !MicrophoneSessionCoordinator.isOwner(microphoneOwnerId)) {
                return@postDelayed
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            runCatching {
                recognizer?.startListening(intent)
                recognitionActive = true
                evaluateJs("window.__voiceInputStarted?.();")
            }.onFailure {
                recognitionActive = false
                runCatching { recognizer?.destroy() }
                recognizer = null
                MicrophoneSessionCoordinator.release(microphoneOwnerId)
                sendVoiceError("The microphone could not start. Try again.")
            }
        }, if (recreate) 220L else 90L)
    }

    private fun stopRecognition(releaseLease: Boolean, notifyWeb: Boolean) {
        recognitionStartToken++
        runCatching { recognizer?.cancel() }
        recognitionActive = false
        if (releaseLease) MicrophoneSessionCoordinator.release(microphoneOwnerId)
        if (notifyWeb) evaluateJs("window.__voiceInputStopped?.();")
    }

    private fun evaluateJs(script: String) {
        mainHandler.post { webView?.evaluateJavascript(script, null) }
    }

    private fun sendVoiceText(functionName: String, text: String) {
        val quoted = JSONObject.quote(text)
        evaluateJs("window.$functionName?.($quoted);")
    }

    private fun sendVoiceError(message: String) = sendVoiceText("__voiceInputError", message)

    override fun onReadyForSpeech(params: Bundle?) {
        recognitionActive = true
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        recognitionActive = false
        MicrophoneSessionCoordinator.release(microphoneOwnerId)

        if (
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_AUDIO
        ) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }

        sendVoiceError(
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition network error."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_AUDIO -> "The microphone is resetting. Try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                else -> "Voice recognition error ($error)."
            }
        )
    }

    override fun onResults(results: Bundle?) {
        recognitionActive = false
        MicrophoneSessionCoordinator.release(microphoneOwnerId)

        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

        if (text.isBlank()) sendVoiceError("I couldn't understand that.")
        else sendVoiceText("__voiceInputResult", text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) sendVoiceText("__voiceInputPartial", text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return

        mainHandler.post {
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    evaluateJs("window.__nativeSpeechStarted?.();")
                }

                override fun onDone(utteranceId: String?) {
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }
            })
        }
    }

    fun dispose() {
        backend.dispose()
        mainHandler.post {
            recognitionStartToken++
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            recognitionActive = false
            MicrophoneSessionCoordinator.release(microphoneOwnerId)
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            ttsReady = false
            webView = null
        }
    }

    private fun normalizeVoiceLanguage(tag: String): String = when (tag.lowercase(Locale.ROOT)) {
        "ar", "ar-ma", "arabic" -> "ar-MA"
        "fr", "fr-fr", "french" -> "fr-FR"
        else -> "en-US"
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createCoachWebView(context: Context, bridge: PracticeContextBridge): WebView {
    val html = context.assets.open("interpreter_coach.html")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
        .replace("<!--BACKEND_SCRIPT-->", "<script>" + context.assets.open("interpreter_backend.js").bufferedReader().use { it.readText() } + "</script>")
        .replace("</body>", listOf("interpreter_coach_runtime.js", "interpreter_standard_arabic.js").joinToString("") { asset ->
            "<script>" + context.assets.open(asset).bufferedReader().use { it.readText() } + "</script>"
        } + "</body>")


    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    bridge.attachWebView(webView)
    configureCoachWebView(webView)
    webView.addJavascriptInterface(bridge, "InterpreterNative")
    webView.addJavascriptInterface(bridge.backend, "InterpreterBackend")
    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse =
            android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            if (!request.isForMainFrame) return false
            if (isClassicCoachOrigin(uri)) return true
            if (request.hasGesture() && uri.scheme.equals("https", ignoreCase = true)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            return true
        }

    }
    webView.webChromeClient = WebChromeClient()

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, false)
    }

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
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        allowFileAccess = false
        allowContentAccess = false
        safeBrowsingEnabled = true
        mediaPlaybackRequiresUserGesture = false
        cacheMode = WebSettings.LOAD_DEFAULT
    }
}

private fun isClassicCoachOrigin(uri: Uri?): Boolean =
    uri?.scheme.equals("https", ignoreCase = true) &&
        uri?.host.equals("interpreter-trainer.app", ignoreCase = true)

private fun buildPracticeContext(sessions: List<PracticeSessionEntity>): String = buildString {
    appendLine("AUTHORITATIVE APP IDENTITY:")
    appendLine("Interpreter Trainer was created and developed by Zouhair Elachaqi.")
    appendLine("Zouhair Elachaqi is the creator of this app, not the AI model or Puter service.")
    appendLine("If the user asks who created, developed, designed, owns, or made the app, answer with Zouhair Elachaqi and do not claim that the creator is unknown.")
    appendLine()

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
