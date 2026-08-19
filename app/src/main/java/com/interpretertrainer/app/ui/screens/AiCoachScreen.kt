package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import com.interpretertrainer.app.ai.AiPracticeBridge
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.viewmodel.SessionViewModel
import org.json.JSONObject
import java.util.Locale

/**
 * Interpreter Coach uses an online Qwen model through Puter.js.
 * Normal chat is streamed for lower perceived latency; evaluation keeps the full response flow.
 */
@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
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
    private var webView: WebView? = null
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
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
            beginVoiceRecognition()
        }
    }

    @JavascriptInterface
    fun stopVoiceInput() {
        mainHandler.post {
            recognizer?.stopListening()
            evaluateJs("window.__voiceInputStopped?.();")
        }
    }

    @JavascriptInterface
    fun speakText(text: String, languageTag: String) {
        val clean = text.trim().take(8_000)
        if (clean.isBlank()) return
        mainHandler.post {
            val tts = textToSpeech ?: return@post
            if (!ttsReady) return@post
            tts.language = localeFor(normalizeVoiceLanguage(languageTag))
            tts.setSpeechRate(1.0f)
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "interpreter-ai-reply")
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        mainHandler.post { textToSpeech?.stop() }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        mainHandler.post {
            val shouldStart = pendingVoiceStart
            pendingVoiceStart = false
            if (granted && shouldStart) beginVoiceRecognition()
            else if (!granted) sendVoiceError("Microphone permission is required for voice chat.")
        }
    }

    private fun beginVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            sendVoiceError("Speech recognition is not available on this device.")
            return
        }
        recognizer?.cancel()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        evaluateJs("window.__voiceInputStarted?.();")
        recognizer?.startListening(intent)
    }

    private fun evaluateJs(script: String) {
        mainHandler.post { webView?.evaluateJavascript(script, null) }
    }

    private fun sendVoiceText(functionName: String, text: String) {
        val quoted = JSONObject.quote(text)
        evaluateJs("window.$functionName?.($quoted);")
    }

    private fun sendVoiceError(message: String) = sendVoiceText("__voiceInputError", message)

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = evaluateJs("window.__voiceInputStopped?.();")

    override fun onError(error: Int) {
        sendVoiceError(
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that. Try speaking again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition network error."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognition is busy. Try again."
                else -> "Voice recognition error ($error)."
            }
        )
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isBlank()) sendVoiceError("I couldn't understand that.")
        else sendVoiceText("__voiceInputResult", text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) sendVoiceText("__voiceInputPartial", text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    fun dispose() {
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            webView = null
        }
    }

    private fun normalizeVoiceLanguage(tag: String): String = when (tag.lowercase(Locale.ROOT)) {
        "ar", "ar-ma", "arabic" -> "ar-MA"
        "fr", "fr-fr", "french" -> "fr-FR"
        else -> "en-US"
    }

    private fun localeFor(tag: String): Locale = when (tag) {
        "ar-MA" -> Locale("ar", "MA")
        "fr-FR" -> Locale.FRANCE
        else -> Locale.US
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
    webView.addJavascriptInterface(bridge, "InterpreterNative")
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.evaluateJavascript(coachEnhancementScript(), null)
        }
    }
    webView.webChromeClient = CoachChromeClient(context)

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
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

private fun coachEnhancementScript(): String = """
(() => {
  if (window.__interpreterEnhancementsV2) return;
  window.__interpreterEnhancementsV2 = true;

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

  const composer = document.querySelector('.composer');
  const sendButton = document.getElementById('sendBtn');
  if (composer && sendButton && !document.getElementById('voiceBtn')) {
    const language = document.createElement('select');
    language.id = 'voiceLang';
    language.setAttribute('aria-label', 'Voice chat language');
    language.innerHTML = '<option value="en-US">EN</option><option value="fr-FR">FR</option><option value="ar-MA">AR</option>';
    language.style.cssText = 'height:38px;border:1px solid var(--border);border-radius:12px;background:var(--surface-soft);color:var(--text);padding:0 6px;font-size:11px;';
    language.onchange = () => window.InterpreterNative?.setVoiceLanguage?.(language.value);

    const mic = document.createElement('button');
    mic.id = 'voiceBtn';
    mic.type = 'button';
    mic.className = 'icon-btn';
    mic.setAttribute('aria-label', 'Voice chat');
    mic.textContent = '🎙';
    mic.style.cssText += 'font-size:18px;';
    mic.onclick = () => {
      window.InterpreterNative?.setVoiceLanguage?.(language.value);
      window.InterpreterNative?.stopSpeaking?.();
      window.__voiceAutoSpeak = true;
      window.InterpreterNative?.startVoiceInput?.();
    };

    composer.insertBefore(language, sendButton);
    composer.insertBefore(mic, sendButton);
  }

  window.__voiceAutoSpeak = false;
  window.__voiceInputStarted = () => {
    const mic = document.getElementById('voiceBtn');
    if (mic) { mic.textContent = '●'; mic.title = 'Listening…'; }
    const error = document.getElementById('chatError');
    if (error) error.textContent = '';
  };
  window.__voiceInputStopped = () => {
    const mic = document.getElementById('voiceBtn');
    if (mic) { mic.textContent = '🎙'; mic.title = 'Voice chat'; }
  };
  window.__voiceInputPartial = text => {
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
    window.__voiceAutoSpeak = true;
    window.sendChat?.();
  };
  window.__voiceInputError = message => {
    window.__voiceInputStopped();
    const error = document.getElementById('chatError');
    if (error) error.textContent = message;
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
        window.InterpreterNative?.speakText?.(text, lang);
      };
      actions.appendChild(speak);

      targets.forEach(([target, label]) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'message-action';
        button.textContent = label;
        button.onclick = () => {
          const practiceText = (bubble.innerText || bubble.textContent || '').trim();
          const ok = window.InterpreterNative?.sendToPractice?.(target, practiceText) === true;
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
  window.__interpreterPracticeObserver.observe(document.body, { childList: true, subtree: true });

  window.sendChat = async function() {
    if (busy) return;
    const input = document.getElementById('chatInput');
    const text = input?.value?.trim() || '';
    if (!text) return;

    document.getElementById('chatError').textContent = '';
    addMessage('user', text);
    input.value = '';
    resizeComposer();
    updateSendState();

    if (!(await ensureConnected())) return;

    busy = true;
    updateSendState();
    showTyping();

    let streamRow = null;
    let answer = '';
    try {
      const system = `You are Interpreter AI, a fast professional coach for interpreters. Work especially well across Arabic, English and French. Help with simultaneous and consecutive interpreting, shadowing, transcription, note-taking, memory, terminology, reformulation, numbers, names, fluency and delivery. Respond directly in the user's language. Be concise by default. Never invent scores, transcripts, history or app facts. The authoritative app/context information below is reliable.\n\n${nativePracticeContext()}`;
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];
      const stream = await puter.ai.chat(conversation, {
        model: 'qwen/qwen3.6-27b',
        stream: true,
        max_tokens: 650,
        temperature: 0.24
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
          : (typeof part?.text === 'string' ? part.text : (typeof part?.delta?.content === 'string' ? part.delta.content : ''));
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

      if (window.__voiceAutoSpeak) {
        const lang = document.getElementById('voiceLang')?.value || 'en-US';
        window.InterpreterNative?.speakText?.(answer, lang);
      }
      window.__voiceAutoSpeak = false;
    } catch (error) {
      hideTyping();
      streamRow?.remove();
      const message = error?.msg || error?.message || String(error);
      document.getElementById('chatError').textContent = 'Interpreter AI could not answer: ' + message;
      setStatus('Request failed', 'bad');
      window.__voiceAutoSpeak = false;
    } finally {
      busy = false;
      updateSendState();
      setTimeout(() => input?.focus(), 50);
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
