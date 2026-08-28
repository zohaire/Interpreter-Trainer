package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
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
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.interpretertrainer.app.BuildConfig
import com.interpretertrainer.app.ai.AiPracticeBridge
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.privacy.AiPrivacyPreferences
import com.interpretertrainer.app.speech.MicrophoneSessionCoordinator
import com.interpretertrainer.app.speech.NaturalAndroidVoice
import com.interpretertrainer.app.viewmodel.SessionViewModel
import org.json.JSONObject
import java.util.Locale

/**
 * Online Interpreter AI powered through Puter/Qwen.
 *
 * The WebView owns the chat UX. Native Android supplies reliable microphone capture and a TTS
 * fallback. Microphone ownership is shared with all practice modes so AI voice, transcription and
 * MediaRecorder never fight over the same hardware input.
 */
@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
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

    ActiveAiCoachScreen(onBack = onBack, sessionViewModel = sessionViewModel)
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
                "To provide chat and evaluation, the app sends your messages, submitted evaluation material and up to five recent practice summaries—including saved notes or feedback—to Puter and the selected Qwen model.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Voice recognition may use Android's configured speech service. AI voice output may use Puter text-to-speech, with Android text-to-speech as a fallback.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "No neural model is downloaded. Puter authentication and its provider terms and privacy policy apply. Core practice modes remain available if you go back.",
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
private fun ActiveAiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
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
        PracticeContextBridge(context.applicationContext) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                runCatching { webView.loadUrl("about:blank") }
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
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
        )
    }
}

private class PracticeContextBridge(
    private val context: Context,
    private val requestMicrophonePermission: () -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {
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
    }

    @JavascriptInterface
    fun getPracticeContext(): String = contextValue

    @JavascriptInterface
    fun sendToPractice(mode: String, text: String): Boolean = AiPracticeBridge.sendToMode(mode, text)

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

    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    bridge.attachWebView(webView)
    configureCoachWebView(webView)
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    webView.addJavascriptInterface(bridge, "InterpreterNative")
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            if (!request.isForMainFrame) return false
            if (isCoachOrigin(uri)) return false
            if (request.hasGesture() && uri.scheme.equals("https", ignoreCase = true)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (isCoachOrigin(url?.let(Uri::parse))) {
                view?.evaluateJavascript(coachEnhancementScript(), null)
            }
        }
    }
    webView.webChromeClient = CoachChromeClient(context)

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    webView.loadDataWithBaseURL(
        COACH_ORIGIN,
        html,
        "text/html",
        "UTF-8",
        null
    )
    return webView
}

private fun coachEnhancementScript(): String = """
(() => {
  if (window.__interpreterEnhancementsV3) return;
  window.__interpreterEnhancementsV3 = true;

  const native = window.InterpreterNative;
  const speakVoice = (text, lang) => {
    try {
      if (typeof window.playNaturalInterpreterVoice === 'function') {
        return window.playNaturalInterpreterVoice(text, lang) === true;
      }
    } catch (_) {}
    return native?.speakText?.(text, lang) === true;
  };
  const stopVoice = () => {
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
    try { native?.stopSpeaking?.(); } catch (_) {}
  };

  const mode = document.getElementById('mode');
  if (mode) {
    mode.innerHTML = '<option>Simultaneous Interpretation</option><option>Shadowing</option><option>Consecutive Interpretation</option><option>Live Transcription</option>';
  }

  const targets = [
    ['SIMULTANEOUS', 'Use in Simultaneous'],
    ['SHADOWING', 'Use in Shadowing'],
    ['CONSECUTIVE', 'Use in Consecutive'],
    ['TRANSCRIPTION', 'Use in Transcription']
  ];

  const style = document.createElement('style');
  style.textContent = `
    .voice-language {
      height:38px; border:1px solid var(--border); border-radius:12px; background:var(--surface-soft);
      color:var(--text); padding:0 7px; font-size:11px; flex:0 0 auto;
    }
    .voice-mic-btn { color:var(--accent) !important; }
    .voice-mic-btn.listening { background:color-mix(in srgb,var(--danger) 12%,transparent) !important; color:var(--danger) !important; }
    .voice-call-strip {
      width:min(760px,100%); margin:0 auto 7px; display:flex; justify-content:flex-end; align-items:center;
    }
    .voice-call-launch {
      display:flex; align-items:center; gap:7px; border:1px solid color-mix(in srgb,var(--accent) 36%,var(--border));
      border-radius:999px; padding:8px 12px; background:var(--accent-soft); color:var(--accent-ink);
      font-size:12px; font-weight:750; cursor:pointer;
    }
    .voice-call-launch:active { transform:scale(.98); }
    .voice-call-overlay {
      position:fixed; inset:0; z-index:9999; display:none; flex-direction:column; align-items:center;
      background:radial-gradient(circle at 50% 32%, color-mix(in srgb,var(--accent) 20%,var(--bg)) 0%, var(--bg) 48%);
      color:var(--text); padding:max(22px,env(safe-area-inset-top)) 22px max(26px,env(safe-area-inset-bottom));
    }
    .voice-call-overlay.active { display:flex; }
    .voice-call-top { width:100%; display:flex; align-items:center; justify-content:space-between; }
    .voice-call-title { font-size:15px; font-weight:780; }
    .voice-call-badge { font-size:11px; color:var(--muted); }
    .voice-orb-wrap { flex:1; width:100%; display:flex; flex-direction:column; justify-content:center; align-items:center; min-height:0; }
    .voice-orb {
      width:154px; height:154px; border-radius:50%; display:grid; place-items:center; font-size:32px; font-weight:850;
      color:white; background:linear-gradient(145deg,var(--accent),color-mix(in srgb,var(--accent) 52%,#8d6cff));
      box-shadow:0 24px 70px color-mix(in srgb,var(--accent) 30%,transparent); transition:transform .22s ease,box-shadow .22s ease;
    }
    @media (prefers-color-scheme: dark) { .voice-orb { color:#101116; } }
    .voice-orb.listening { transform:scale(1.07); box-shadow:0 0 0 12px color-mix(in srgb,var(--accent) 8%,transparent),0 26px 80px color-mix(in srgb,var(--accent) 34%,transparent); animation:voicePulse 1.25s infinite ease-in-out; }
    .voice-orb.speaking { transform:scale(1.04); animation:voiceSpeak 1.05s infinite ease-in-out; }
    .voice-call-status { margin-top:30px; font-size:18px; font-weight:760; text-align:center; }
    .voice-call-live { margin-top:10px; width:min(560px,92vw); min-height:52px; color:var(--muted); text-align:center; font-size:14px; line-height:1.5; }
    .voice-call-controls { display:flex; align-items:center; gap:18px; }
    .voice-round-control {
      width:58px; height:58px; border-radius:50%; border:1px solid var(--border); background:var(--surface-soft); color:var(--text);
      display:grid; place-items:center; font-size:21px; cursor:pointer;
    }
    .voice-round-control.end { width:68px; height:68px; border:0; background:#d93025; color:white; transform:rotate(135deg); }
    .voice-round-control.muted { background:var(--surface-strong); color:var(--muted); }
    @keyframes voicePulse { 0%,100%{transform:scale(1.04)} 50%{transform:scale(1.10)} }
    @keyframes voiceSpeak { 0%,100%{transform:scale(1.02)} 50%{transform:scale(1.07)} }
  `;
  document.head.appendChild(style);

  const composer = document.querySelector('.composer');
  const composerShell = document.querySelector('.composer-shell');
  const sendButton = document.getElementById('sendBtn');

  let language = document.getElementById('voiceLang');
  if (composer && sendButton && !language) {
    language = document.createElement('select');
    language.id = 'voiceLang';
    language.className = 'voice-language';
    language.setAttribute('aria-label', 'Voice language');
    language.innerHTML = '<option value="en-US">EN</option><option value="fr-FR">FR</option><option value="ar-MA">AR</option>';
    language.onchange = () => native?.setVoiceLanguage?.(language.value);
    composer.insertBefore(language, sendButton);
  }

  if (composer && sendButton && !document.getElementById('voiceBtn')) {
    const mic = document.createElement('button');
    mic.id = 'voiceBtn';
    mic.type = 'button';
    mic.className = 'icon-btn voice-mic-btn';
    mic.setAttribute('aria-label', 'Ask Interpreter AI by voice');
    mic.title = 'Ask by voice';
    mic.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0"/><path d="M12 17v5"/></svg>';
    mic.onclick = () => {
      window.__voiceCallActive = false;
      window.__voiceOneShot = true;
      native?.setVoiceLanguage?.(language?.value || 'en-US');
      stopVoice();
      native?.startVoiceInput?.();
    };
    composer.insertBefore(mic, sendButton);
  }

  if (composerShell && !document.getElementById('voiceCallLaunch')) {
    const strip = document.createElement('div');
    strip.className = 'voice-call-strip';
    const call = document.createElement('button');
    call.id = 'voiceCallLaunch';
    call.type = 'button';
    call.className = 'voice-call-launch';
    call.innerHTML = '<span>☎</span><span>Voice call</span>';
    call.onclick = () => window.startVoiceCall?.();
    strip.appendChild(call);
    composerShell.insertBefore(strip, composerShell.firstChild);
  }

  const overlay = document.createElement('div');
  overlay.id = 'voiceCallOverlay';
  overlay.className = 'voice-call-overlay';
  overlay.innerHTML = `
    <div class="voice-call-top">
      <div><div class="voice-call-title">Interpreter AI</div><div class="voice-call-badge">Live voice conversation</div></div>
      <select id="callVoiceLang" class="voice-language" aria-label="Call language">
        <option value="en-US">English</option><option value="fr-FR">Français</option><option value="ar-MA">العربية</option>
      </select>
    </div>
    <div class="voice-orb-wrap">
      <div id="voiceOrb" class="voice-orb">AI</div>
      <div id="voiceCallStatus" class="voice-call-status">Ready</div>
      <div id="voiceCallLive" class="voice-call-live">Start speaking naturally. Interpreter AI will answer aloud and keep the conversation going.</div>
    </div>
    <div class="voice-call-controls">
      <button id="voiceMute" class="voice-round-control" type="button" aria-label="Mute microphone">🎙</button>
      <button id="voiceEnd" class="voice-round-control end" type="button" aria-label="End voice call">☎</button>
    </div>
  `;
  document.body.appendChild(overlay);

  window.__voiceCallActive = false;
  window.__voiceCallMuted = false;
  window.__voiceOneShot = false;
  window.__voiceAutoSpeak = false;
  let callRetryTimer = null;

  const callStatus = (status, liveText) => {
    const statusNode = document.getElementById('voiceCallStatus');
    const liveNode = document.getElementById('voiceCallLive');
    if (statusNode) statusNode.textContent = status;
    if (liveNode && liveText !== undefined) liveNode.textContent = liveText;
  };

  const setOrbState = state => {
    const orb = document.getElementById('voiceOrb');
    if (!orb) return;
    orb.classList.remove('listening','speaking');
    if (state) orb.classList.add(state);
  };

  const scheduleListening = (delay = 350) => {
    clearTimeout(callRetryTimer);
    if (!window.__voiceCallActive || window.__voiceCallMuted || busy) return;
    callRetryTimer = setTimeout(() => {
      if (!window.__voiceCallActive || window.__voiceCallMuted || busy) return;
      const callLang = document.getElementById('callVoiceLang')?.value || 'en-US';
      native?.setVoiceLanguage?.(callLang);
      callStatus('Listening…', 'Speak now');
      setOrbState('listening');
      native?.startVoiceInput?.();
    }, delay);
  };

  window.startVoiceCall = async () => {
    if (window.__voiceCallActive) return;
    overlay.classList.add('active');
    window.__voiceCallActive = true;
    window.__voiceCallMuted = false;
    window.__voiceOneShot = false;
    window.__voiceAutoSpeak = true;
    document.getElementById('voiceMute')?.classList.remove('muted');
    callStatus('Connecting…', 'Preparing Interpreter AI');
    setOrbState(null);

    const connected = await ensureConnected();
    if (!connected) {
      callStatus('Connection failed', 'Check your internet connection and try again.');
      return;
    }
    scheduleListening(250);
  };

  window.endVoiceCall = () => {
    window.__voiceCallActive = false;
    window.__voiceCallMuted = false;
    window.__voiceOneShot = false;
    window.__voiceAutoSpeak = false;
    clearTimeout(callRetryTimer);
    native?.stopVoiceInput?.();
    stopVoice();
    setOrbState(null);
    overlay.classList.remove('active');
    callStatus('Ready', 'Start speaking naturally. Interpreter AI will answer aloud and keep the conversation going.');
  };

  document.getElementById('voiceEnd').onclick = window.endVoiceCall;
  document.getElementById('callVoiceLang').onchange = event => {
    const value = event.target.value;
    if (language) language.value = value;
    native?.setVoiceLanguage?.(value);
  };
  document.getElementById('voiceMute').onclick = event => {
    window.__voiceCallMuted = !window.__voiceCallMuted;
    event.currentTarget.classList.toggle('muted', window.__voiceCallMuted);
    event.currentTarget.textContent = window.__voiceCallMuted ? '🔇' : '🎙';
    if (window.__voiceCallMuted) {
      native?.stopVoiceInput?.();
      callStatus('Muted', 'Tap the microphone button to continue.');
      setOrbState(null);
    } else {
      scheduleListening(200);
    }
  };

  window.__voiceInputStarted = () => {
    const mic = document.getElementById('voiceBtn');
    mic?.classList.add('listening');
    if (mic) mic.title = 'Listening…';
    document.getElementById('chatError').textContent = '';
    if (window.__voiceCallActive) {
      callStatus('Listening…', 'Speak now');
      setOrbState('listening');
    }
  };

  window.__voiceInputStopped = () => {
    const mic = document.getElementById('voiceBtn');
    mic?.classList.remove('listening');
    if (mic) mic.title = 'Ask by voice';
  };

  window.__voiceInputPartial = text => {
    if (window.__voiceCallActive) {
      callStatus('Listening…', text);
      return;
    }
    const input = document.getElementById('chatInput');
    if (!input) return;
    input.value = text;
    window.resizeComposer?.();
    window.updateSendState?.();
  };

  window.__voiceInputResult = text => {
    window.__voiceInputStopped();
    const input = document.getElementById('chatInput');
    if (!input) return;
    input.value = text;
    window.resizeComposer?.();
    window.updateSendState?.();

    if (window.__voiceCallActive) {
      callStatus('Thinking…', text);
      setOrbState(null);
      window.__voiceAutoSpeak = true;
      window.sendChat?.(true);
    } else {
      window.__voiceAutoSpeak = true;
      window.sendChat?.(true);
    }
  };

  window.__voiceInputError = message => {
    window.__voiceInputStopped();
    if (window.__voiceCallActive) {
      callStatus('Listening…', message + ' Trying again…');
      setOrbState(null);
      scheduleListening(900);
    } else {
      document.getElementById('chatError').textContent = message;
    }
  };

  window.__nativeSpeechStarted = () => {
    if (!window.__voiceCallActive) return;
    callStatus('Interpreter AI is speaking', 'You can listen, then the microphone will reopen automatically.');
    setOrbState('speaking');
  };

  window.__nativeSpeechFinished = () => {
    if (!window.__voiceCallActive) return;
    setOrbState(null);
    callStatus('Your turn', 'The microphone is reopening…');
    scheduleListening(350);
  };

  const enhanceAssistantMessages = () => {
    document.querySelectorAll('.message.assistant').forEach(row => {
      if (row.dataset.streaming === '1' || row.dataset.practiceActions === '1') return;
      const bubble = row.querySelector('.bubble');
      if (!bubble) return;
      let actions = row.querySelector('.message-actions');
      if (!actions) {
        const body = bubble.parentElement;
        if (!body) return;
        actions = document.createElement('div');
        actions.className = 'message-actions';
        body.appendChild(actions);
      }

      const speak = document.createElement('button');
      speak.type = 'button';
      speak.className = 'message-action';
      speak.textContent = 'Speak';
      speak.onclick = () => {
        const text = (bubble.innerText || bubble.textContent || '').trim();
        const lang = document.getElementById('voiceLang')?.value || 'en-US';
        speakVoice(text, lang);
      };
      actions.appendChild(speak);

      targets.forEach(([target, label]) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'message-action';
        button.textContent = label;
        button.onclick = () => {
          const practiceText = (bubble.innerText || bubble.textContent || '').trim();
          const ok = native?.sendToPractice?.(target, practiceText) === true;
          const original = label;
          button.textContent = ok ? 'Added ✓' : 'Could not add';
          setTimeout(() => { button.textContent = original; }, 1400);
        };
        actions.appendChild(button);
      });
      row.dataset.practiceActions = '1';
    });
  };

  window.__interpreterPracticeObserver?.disconnect?.();
  window.__interpreterPracticeObserver = new MutationObserver(enhanceAssistantMessages);
  window.__interpreterPracticeObserver.observe(document.body, { childList:true, subtree:true });

  window.sendChat = async function(fromVoice = false) {
    if (busy) return;
    const input = document.getElementById('chatInput');
    const text = input?.value?.trim() || '';
    if (!text) return;

    document.getElementById('chatError').textContent = '';
    addMessage('user', text);
    input.value = '';
    resizeComposer();
    updateSendState();

    if (!(await ensureConnected())) {
      if (window.__voiceCallActive) callStatus('Connection failed', 'Unable to reach Interpreter AI.');
      return;
    }

    busy = true;
    updateSendState();
    showTyping();

    let streamRow = null;
    let answer = '';
    try {
      const system = `You are Interpreter AI, a fast professional coach for interpreters. Work especially well across Arabic, English and French. Help with simultaneous and consecutive interpreting, shadowing, transcription, note-taking, memory, terminology, reformulation, numbers, names, fluency and delivery. In voice conversations, sound natural, concise and conversational rather than like a written report. Respond directly in the user's language. Never invent scores, transcripts, history or app facts. The authoritative app/context information below is reliable.\n\n${nativePracticeContext()}`;
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];
      const stream = await puter.ai.chat(conversation, {
        model:'qwen/qwen3.6-27b',
        stream:true,
        max_tokens:fromVoice ? 420 : 650,
        temperature:0.24
      });

      hideTyping();
      streamRow = messageElement('assistant', '');
      streamRow.dataset.streaming = '1';
      document.getElementById('messages').appendChild(streamRow);
      const bubble = streamRow.querySelector('.bubble');

      for await (const part of stream) {
        if (part?.type === 'error') throw new Error(part?.error?.message || part?.message || 'Streaming request failed.');
        const chunk = typeof part === 'string'
          ? part
          : (typeof part?.text === 'string'
              ? part.text
              : (typeof part?.delta?.content === 'string' ? part.delta.content : ''));
        if (!chunk) continue;
        answer += chunk;
        if (bubble) bubble.textContent = answer;
        requestAnimationFrame(scrollToBottom);
      }

      answer = answer.trim();
      if (!answer) throw new Error('The AI returned an empty response.');
      streamRow?.remove();
      streamRow = null;

      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-16);
      saveHistory();
      addMessage('assistant', answer);
      setStatus('Online · ready', 'ok');

      if (window.__voiceCallActive) {
        callStatus('Interpreter AI is speaking', answer);
        const callLang = document.getElementById('callVoiceLang')?.value || 'en-US';
        const started = speakVoice(answer, callLang);
        if (!started) {
          callStatus('Your turn', 'Voice output is unavailable; listening again.');
          setTimeout(() => scheduleListening(150), 0);
        }
      } else if (window.__voiceAutoSpeak || window.__voiceOneShot) {
        const lang = document.getElementById('voiceLang')?.value || 'en-US';
        speakVoice(answer, lang);
      }

      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
    } catch (error) {
      hideTyping();
      streamRow?.remove();
      const message = error?.msg || error?.message || String(error);
      document.getElementById('chatError').textContent = 'Interpreter AI could not answer: ' + message;
      setStatus('Request failed', 'bad');
      if (window.__voiceCallActive) {
        callStatus('Something went wrong', message);
        setTimeout(() => scheduleListening(450), 0);
      }
      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
    } finally {
      busy = false;
      updateSendState();
      if (window.__voiceCallActive && !window.__voiceCallMuted && !window.__voiceAutoSpeak) {
        const statusText = document.getElementById('voiceCallStatus')?.textContent || '';
        if (statusText === 'Your turn' || statusText === 'Something went wrong') scheduleListening(350);
      }
      if (!window.__voiceCallActive) setTimeout(() => input?.focus(), 50);
    }
  };

  enhanceAssistantMessages();
})();
""".trimIndent()

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
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        safeBrowsingEnabled = true
        mediaPlaybackRequiresUserGesture = false
        cacheMode = WebSettings.LOAD_DEFAULT
    }
}

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

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return uri.scheme != "https" && uri.toString() != "about:blank"
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        dialog.setContentView(popupContainer)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            runCatching { popup.stopLoading() }
            runCatching { popup.loadUrl("about:blank") }
            runCatching { popupContainer.removeAllViews() }
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

private const val COACH_ORIGIN = "https://app.interpretertrainer.invalid/"

private fun isCoachOrigin(uri: Uri?): Boolean =
    uri?.scheme.equals("https", ignoreCase = true) &&
        uri?.host.equals("app.interpretertrainer.invalid", ignoreCase = true)

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
