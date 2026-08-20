package com.interpretertrainer.app.ui.screens

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.speech.InterpreterVoiceBridge
import com.interpretertrainer.app.viewmodel.SessionViewModel
import java.util.Locale

private const val COACH_URL =
    "https://appassets.androidplatform.net/assets/interpreter_coach.html"

/**
 * Interpreter Coach uses an online Qwen model through Puter.js and native Android voice I/O.
 * The visual chat stays bundled in the APK; Android SpeechRecognizer/TextToSpeech handle voice.
 */
@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val sessions by sessionViewModel.sessions.collectAsState()
    val bridge = remember { PracticeContextBridge() }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val voiceBridgeRef = remember { mutableStateOf<InterpreterVoiceBridge?>(null) }

    SideEffect {
        bridge.contextValue = buildPracticeContext(sessions)
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceBridgeRef.value?.destroy()
            voiceBridgeRef.value = null
            webViewRef.value?.let { webView ->
                runCatching { webView.stopLoading() }
                runCatching { webView.removeJavascriptInterface("InterpreterVoice") }
                runCatching { webView.removeJavascriptInterface("InterpreterNative") }
                runCatching { webView.destroy() }
            }
            webViewRef.value = null
        }
    }

    TrainerScaffold("Interpreter Coach", onBack) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                createCoachWebView(
                    context = context,
                    practiceBridge = bridge,
                    onVoiceBridgeCreated = { voiceBridgeRef.value = it }
                ).also { webViewRef.value = it }
            },
            update = { webView ->
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
        )
    }
}

private class PracticeContextBridge {
    @Volatile
    var contextValue: String = "No saved practice sessions yet."

    @JavascriptInterface
    fun getPracticeContext(): String = contextValue
}

@SuppressLint("SetJavaScriptEnabled")
private fun createCoachWebView(
    context: Context,
    practiceBridge: PracticeContextBridge,
    onVoiceBridgeCreated: (InterpreterVoiceBridge) -> Unit
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    configureCoachWebView(webView)

    val voiceBridge = InterpreterVoiceBridge(context) { webView }
    onVoiceBridgeCreated(voiceBridge)
    webView.addJavascriptInterface(practiceBridge, "InterpreterNative")
    webView.addJavascriptInterface(voiceBridge, "InterpreterVoice")

    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return assetLoader.shouldInterceptRequest(request.url)
                ?: super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (url != COACH_URL) return
            view?.evaluateJavascript(nativeCoachEnhancementsScript(), null)
        }
    }
    webView.webChromeClient = CoachChromeClient(context)

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    webView.loadUrl(COACH_URL)
    return webView
}

private fun nativeCoachEnhancementsScript(): String = """
(() => {
  const mode = document.getElementById('mode');
  if (mode) {
    mode.innerHTML = '<option>Simultaneous Interpretation</option><option>Consecutive Interpretation</option><option>Live Transcription</option>';
  }
  const suggestions = Array.from(document.querySelectorAll('.suggestion'));
  const oldShadowing = suggestions.find(button => button.textContent.includes('Shadowing'));
  if (oldShadowing) {
    oldShadowing.textContent = 'Simultaneous exercise';
    oldShadowing.onclick = () => window.useSuggestion?.('Give me a short simultaneous interpreting exercise in English, French, or Arabic.');
  }

  if (window.__interpreterNativeVoiceInstalled) return;
  window.__interpreterNativeVoiceInstalled = true;

  const composer = document.querySelector('.composer');
  const input = document.getElementById('chatInput');
  const send = document.getElementById('sendBtn');
  const messages = document.getElementById('messages');
  if (!composer || !input || !send || !messages || !window.InterpreterVoice) return;

  const style = document.createElement('style');
  style.id = 'nativeVoiceStyle';
  style.textContent = `
    .voice-mic-btn {
      width:38px;height:38px;flex:0 0 auto;border:0;border-radius:14px;
      display:grid;place-items:center;background:transparent;color:var(--muted);
      cursor:pointer;transition:background .16s ease,color .16s ease,transform .12s ease;
    }
    .voice-mic-btn:active { transform:scale(.94); }
    .voice-mic-btn.active { background:var(--accent-soft);color:var(--accent-ink); }
    .voice-mic-btn.speaking { background:var(--accent);color:#fff; }
    .voice-mic-btn svg { width:19px;height:19px; }
  `;
  document.head.appendChild(style);

  const mic = document.createElement('button');
  mic.id = 'voiceMicBtn';
  mic.type = 'button';
  mic.className = 'voice-mic-btn';
  mic.title = 'Start voice conversation';
  mic.setAttribute('aria-label', 'Start voice conversation');
  mic.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"></rect><path d="M5 10a7 7 0 0 0 14 0"></path><path d="M12 17v5"></path><path d="M8 22h8"></path></svg>';
  composer.insertBefore(mic, send);

  const voiceState = {
    active:false,
    speaking:false,
    listening:false,
    lastLanguage:localStorage.getItem('interpreterVoiceLanguage') || navigator.language || 'auto'
  };

  function detectLanguage(text) {
    const value = String(text || '');
    if (/[\u0600-\u06FF]/.test(value)) return 'ar-MA';
    const lower = ' ' + value.toLowerCase() + ' ';
    const french = [' je ',' tu ',' vous ',' une ',' des ',' est ',' avec ',' pour ',' dans ',' mais ',' que ',' qui ',' le ',' la ',' les ',' de '];
    if (french.some(word => lower.includes(word)) || /[àâçéèêëîïôùûüÿœ]/i.test(value)) return 'fr-FR';
    return 'en-US';
  }

  function rememberLanguage(text) {
    voiceState.lastLanguage = detectLanguage(text);
    try { localStorage.setItem('interpreterVoiceLanguage', voiceState.lastLanguage); } catch (_) {}
  }

  function restoreAiStatus() {
    try {
      if (window.puter && puter.auth.isSignedIn()) setStatus('Online · ready', 'ok');
      else setStatus('Ready to connect');
    } catch (_) { setStatus('Ready'); }
  }

  function renderVoiceState() {
    mic.classList.toggle('active', voiceState.active && !voiceState.speaking);
    mic.classList.toggle('speaking', voiceState.speaking);
    mic.title = voiceState.active ? 'Stop voice conversation' : 'Start voice conversation';
    mic.setAttribute('aria-label', mic.title);
  }

  function startVoiceConversation() {
    voiceState.active = true;
    voiceState.speaking = false;
    voiceState.listening = true;
    renderVoiceState();
    setStatus('Listening…', 'ok');
    window.InterpreterVoice.startListening(voiceState.lastLanguage || 'auto', true);
  }

  function stopVoiceConversation() {
    voiceState.active = false;
    voiceState.speaking = false;
    voiceState.listening = false;
    window.InterpreterVoice.stopAll();
    input.placeholder = 'Message Interpreter AI';
    renderVoiceState();
    restoreAiStatus();
  }

  function submitVoiceText(text) {
    const clean = String(text || '').trim();
    if (!clean || !voiceState.active) return;
    rememberLanguage(clean);
    input.value = clean;
    if (typeof resizeComposer === 'function') resizeComposer();
    if (typeof updateSendState === 'function') updateSendState();
    setStatus('Thinking…', 'ok');
    if (typeof sendChat === 'function') sendChat();
  }

  window.InterpreterVoiceNative = {
    onState(state) {
      if (!voiceState.active && state !== 'ready') return;
      voiceState.listening = state === 'listening';
      voiceState.speaking = state === 'speaking';
      if (state === 'listening') {
        input.placeholder = 'Listening…';
        setStatus('Listening…', 'ok');
      } else if (state === 'speaking') {
        input.placeholder = 'You can interrupt me…';
        setStatus('Speaking…', 'ok');
      } else if (state === 'ready' && voiceState.active) {
        setStatus('Voice ready', 'ok');
      }
      renderVoiceState();
    },
    onPartial(text) {
      if (!voiceState.active || voiceState.speaking) return;
      input.value = String(text || '');
      if (typeof resizeComposer === 'function') resizeComposer();
      if (typeof updateSendState === 'function') updateSendState();
    },
    onResult(text) {
      submitVoiceText(text);
    },
    onBargeIn(text) {
      if (!voiceState.active) return;
      voiceState.speaking = false;
      renderVoiceState();
      setStatus('Listening to you…', 'ok');
    },
    onSpeechStart() {
      if (!voiceState.active) return;
      voiceState.speaking = true;
      voiceState.listening = true;
      renderVoiceState();
      setStatus('Speaking…', 'ok');
    },
    onSpeechDone() {
      if (!voiceState.active) return;
      voiceState.speaking = false;
      voiceState.listening = true;
      renderVoiceState();
      setStatus('Listening…', 'ok');
    },
    onError(message) {
      const text = String(message || 'Voice error');
      const errorHost = document.getElementById('chatError');
      if (errorHost) errorHost.textContent = text;
      setStatus('Voice needs attention', 'bad');
      if (text.toLowerCase().includes('permission')) {
        voiceState.active = false;
        voiceState.speaking = false;
        voiceState.listening = false;
        renderVoiceState();
      }
    }
  };

  mic.onclick = () => {
    if (voiceState.active && voiceState.speaking) {
      window.InterpreterVoice.manualInterrupt(voiceState.lastLanguage || 'auto');
      voiceState.speaking = false;
      voiceState.listening = true;
      renderVoiceState();
      setStatus('Listening…', 'ok');
      return;
    }
    if (voiceState.active) stopVoiceConversation();
    else startVoiceConversation();
  };

  function enhanceAssistantMessage(row) {
    if (!(row instanceof HTMLElement) || !row.classList.contains('assistant')) return;
    if (row.dataset.nativeVoiceEnhanced === '1') return;
    row.dataset.nativeVoiceEnhanced = '1';
    const bubble = row.querySelector('.bubble');
    if (!bubble) return;
    const text = bubble.innerText.trim();
    if (!text) return;

    let actions = row.querySelector('.message-actions');
    if (!actions) {
      actions = document.createElement('div');
      actions.className = 'message-actions';
      row.querySelector('.message-body')?.appendChild(actions);
    }
    const listen = document.createElement('button');
    listen.type = 'button';
    listen.className = 'message-action';
    listen.textContent = 'Listen';
    listen.onclick = () => {
      rememberLanguage(text);
      window.InterpreterVoice.speak(text, voiceState.lastLanguage, false);
    };
    actions?.appendChild(listen);

    if (voiceState.active) {
      rememberLanguage(text);
      window.InterpreterVoice.speak(text, voiceState.lastLanguage, true);
    }
  }

  document.querySelectorAll('.message.assistant').forEach(enhanceAssistantMessage);
  const observer = new MutationObserver(mutations => {
    mutations.forEach(mutation => {
      mutation.addedNodes.forEach(node => {
        if (!(node instanceof HTMLElement)) return;
        if (node.classList.contains('message') && node.classList.contains('assistant')) {
          enhanceAssistantMessage(node);
        }
        node.querySelectorAll?.('.message.assistant').forEach(enhanceAssistantMessage);
      });
    });
  });
  observer.observe(messages, { childList:true, subtree:true });
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
