package com.interpretertrainer.app.ui.screens

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.interpretertrainer.app.speech.InterpreterLiveNativeBridge
import com.interpretertrainer.app.ui.theme.ThemeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps Interpreter AI synchronized with the app theme and installs the online AI + voice layers.
 *
 * Voice UI is deliberately NOT gated on the advanced full-duplex bridge. The microphone button,
 * language selector and Interpreter Live controls must always appear and remain usable through the
 * normal InterpreterNative bridge even when a device cannot expose the optional low-latency duplex
 * bridge. The large waveform on the welcome screen is also a real touch target, not decoration.
 */
@Composable
fun ResponsiveAiCoachScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    themeMode: ThemeMode
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val liveBridge = remember(context) {
        InterpreterLiveNativeBridge(context.applicationContext)
    }
    val attachedWebView = remember { mutableStateOf<WebView?>(null) }

    val coachHtml = remember(context) { readAsset(context, "interpreter_coach.html") }
    val aiBootstrapPatch = remember(context) { readAsset(context, "interpreter_ai_bootstrap.js") }
    val nativeDuplexPatch = remember(context) { readAsset(context, "interpreter_live_native_duplex.js") }
    val voicePatch = remember(context) { readAsset(context, "interpreter_fast_voice.js") }
    val preciseBargeInPatch = remember(context) { readAsset(context, "interpreter_precise_barge_in.js") }
    val liveLatencyPatch = remember(context) { readAsset(context, "interpreter_live_latency.js") }
    val standardArabicPatch = remember(context) { readAsset(context, "interpreter_standard_arabic.js") }

    DisposableEffect(liveBridge) {
        onDispose {
            liveBridge.dispose()
            attachedWebView.value = null
        }
    }

    // AiCoachScreen owns the Activity-context WebView and the reliable native mic/TTS bridge.
    AiCoachScreen(onBack = onBack, sessionViewModel = sessionViewModel)

    LaunchedEffect(
        rootView,
        darkTheme,
        liveBridge,
        coachHtml,
        aiBootstrapPatch,
        nativeDuplexPatch,
        voicePatch,
        preciseBargeInPatch,
        liveLatencyPatch,
        standardArabicPatch
    ) {
        // Retry long enough for slow Android WebView/Puter startup. Voice controls are installed as
        // soon as the coach DOM exists; optional duplex readiness can never block their appearance.
        repeat(140) {
            val webView = findCoachWebView(rootView)
            if (webView != null) {
                if (attachedWebView.value !== webView) {
                    // JavascriptInterface objects are exposed to JavaScript after a navigation.
                    // Attach the optional duplex bridge once and reload the bundled coach once.
                    liveBridge.attachWebView(webView)
                    webView.addJavascriptInterface(liveBridge, "InterpreterLiveNative")
                    attachedWebView.value = webView
                    webView.loadDataWithBaseURL(
                        "https://interpreter-trainer.app/",
                        coachHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                    delay(320)
                } else if (evaluateForResult(webView, coachDomReadyScript())) {
                    runCatching {
                        webView.setBackgroundColor(
                            if (darkTheme) 0xFF0E0F12.toInt() else 0xFFFBFBFD.toInt()
                        )
                        webView.evaluateJavascript(themeSyncScript(darkTheme), null)
                    }

                    // If an earlier WebView pass set the bootstrap marker before the voice shell was
                    // actually installed, clear only that stale marker and let the bootstrap repair
                    // itself. This prevents the old "visible symbol, nothing touchable" state.
                    runCatching { webView.evaluateJavascript(bootstrapRepairScript(), null) }

                    // Install the core bootstrap first and independently. It creates voiceLang,
                    // voiceBtn, voiceCallLaunch and voiceCallOverlay itself.
                    runCatching { webView.evaluateJavascript(aiBootstrapPatch, null) }
                    runCatching { webView.evaluateJavascript(standardArabicPatch, null) }

                    // Force real touch targets immediately. This layer does not depend on Puter or
                    // the optional duplex bridge and falls back to the native microphone if needed.
                    runCatching { webView.evaluateJavascript(forceTouchableVoiceUiScript(), null) }

                    // Advanced native duplex is optional. A pending/unsupported duplex layer must
                    // never prevent the normal microphone + voice conversation layer from loading.
                    runCatching { webView.evaluateJavascript(nativeDuplexPatch, null) }
                    runCatching { webView.evaluateJavascript(voicePatch, null) }
                    runCatching { webView.evaluateJavascript(preciseBargeInPatch, null) }
                    runCatching { webView.evaluateJavascript(liveLatencyPatch, null) }

                    // Re-apply touch behavior after optional layers in case they replaced handlers.
                    runCatching { webView.evaluateJavascript(forceTouchableVoiceUiScript(), null) }

                    if (evaluateForResult(webView, voiceUiReadyScript())) {
                        return@LaunchedEffect
                    }
                }
            }
            delay(140)
        }
    }
}

private fun readAsset(context: android.content.Context, name: String): String =
    context.assets.open(name)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

private fun findCoachWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findCoachWebView(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}

private fun coachDomReadyScript(): String = """
(() => {
  const ready = (document.readyState === 'interactive' || document.readyState === 'complete') &&
    !!document.querySelector('.composer') && !!document.querySelector('.composer-shell') &&
    !!document.getElementById('sendBtn') && !!document.querySelector('.welcome-mark');
  return ready ? 'ready' : 'pending';
})();
""".trimIndent()

private fun bootstrapRepairScript(): String = """
(() => {
  const broken = window.__interpreterAiBootstrapV5 === true &&
    (typeof window.startVoiceCall !== 'function' ||
     !document.getElementById('voiceBtn') ||
     !document.getElementById('voiceCallLaunch'));
  if (broken) {
    try { delete window.__interpreterAiBootstrapV5; }
    catch (_) { window.__interpreterAiBootstrapV5 = false; }
  }
  return 'ready';
})();
""".trimIndent()

private fun forceTouchableVoiceUiScript(): String = """
(() => {
  const byId = id => document.getElementById(id);
  const native = window.InterpreterNative;
  const composer = document.querySelector('.composer');
  const composerShell = document.querySelector('.composer-shell');
  const sendButton = byId('sendBtn');
  const mark = document.querySelector('.welcome-mark');
  if (!composer || !composerShell || !sendButton || !mark) return 'pending';

  const currentLanguage = () => byId('voiceLang')?.value || byId('callVoiceLang')?.value || 'en-US';
  const setLanguage = value => {
    try { native?.setVoiceLanguage?.(value || 'en-US'); } catch (_) {}
  };
  const startOneShot = () => {
    setLanguage(currentLanguage());
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
    try { native?.stopSpeaking?.(); } catch (_) {}
    try { native?.startVoiceInput?.(); } catch (_) {}
  };
  const startConversation = () => {
    setLanguage(currentLanguage());
    try {
      if (typeof window.startVoiceCall === 'function') {
        window.startVoiceCall();
        return;
      }
    } catch (_) {}
    startOneShot();
  };

  let language = byId('voiceLang');
  if (!language) {
    language = document.createElement('select');
    language.id = 'voiceLang';
    language.setAttribute('aria-label', 'Voice language');
    language.innerHTML = '<option value="en-US">EN</option><option value="fr-FR">FR</option><option value="ar-MA">AR</option>';
    Object.assign(language.style, {
      height:'39px', minWidth:'52px', border:'1px solid var(--border)', borderRadius:'12px',
      background:'var(--surface-soft)', color:'var(--text)', padding:'0 7px', fontSize:'11px'
    });
    composer.insertBefore(language, sendButton);
  }
  language.onchange = event => setLanguage(event.target.value);

  let mic = byId('voiceBtn');
  if (!mic) {
    mic = document.createElement('button');
    mic.id = 'voiceBtn';
    mic.type = 'button';
    mic.setAttribute('aria-label', 'Speak to Interpreter AI');
    mic.title = 'Speak to Interpreter AI';
    mic.innerHTML = '<svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0"/><path d="M12 17v5"/></svg>';
    Object.assign(mic.style, {
      width:'42px', height:'42px', border:'0', borderRadius:'14px', display:'grid', placeItems:'center',
      flex:'0 0 auto', background:'var(--accent-soft)', color:'var(--accent-ink)', cursor:'pointer',
      pointerEvents:'auto', touchAction:'manipulation'
    });
    composer.insertBefore(mic, sendButton);
  }
  mic.onclick = event => { event.preventDefault(); startOneShot(); };
  mic.style.pointerEvents = 'auto';
  mic.style.touchAction = 'manipulation';

  let launch = byId('voiceCallLaunch');
  if (!launch) {
    let strip = byId('forcedVoiceCallStrip');
    if (!strip) {
      strip = document.createElement('div');
      strip.id = 'forcedVoiceCallStrip';
      Object.assign(strip.style, {
        width:'min(780px,100%)', margin:'0 auto 7px', display:'flex', justifyContent:'flex-end'
      });
      composerShell.insertBefore(strip, composerShell.firstChild);
    }
    launch = document.createElement('button');
    launch.id = 'voiceCallLaunch';
    launch.type = 'button';
    launch.textContent = '🎙  Interpreter Live';
    launch.setAttribute('aria-label', 'Start Interpreter Live voice conversation');
    Object.assign(launch.style, {
      minHeight:'42px', border:'1px solid var(--border)', borderRadius:'999px', padding:'8px 14px',
      background:'var(--accent-soft)', color:'var(--accent-ink)', fontSize:'12px', fontWeight:'750',
      cursor:'pointer', pointerEvents:'auto', touchAction:'manipulation'
    });
    strip.appendChild(launch);
  }
  launch.onclick = event => { event.preventDefault(); startConversation(); };
  launch.style.pointerEvents = 'auto';
  launch.style.touchAction = 'manipulation';

  // Turn the large waveform from a decorative div into a real accessible touch target.
  mark.id = 'welcomeVoiceButton';
  mark.setAttribute('role', 'button');
  mark.setAttribute('tabindex', '0');
  mark.setAttribute('aria-label', 'Start Interpreter Live voice conversation');
  mark.setAttribute('title', 'Tap to talk');
  mark.classList.add('voice-touch-ready');
  Object.assign(mark.style, {
    cursor:'pointer', pointerEvents:'auto', touchAction:'manipulation', userSelect:'none',
    WebkitUserSelect:'none', WebkitTapHighlightColor:'transparent', transition:'transform .12s ease, box-shadow .12s ease'
  });
  mark.onclick = event => { event.preventDefault(); startConversation(); };
  mark.onkeydown = event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      startConversation();
    }
  };
  mark.onpointerdown = () => { mark.style.transform = 'scale(.93)'; };
  mark.onpointerup = () => { mark.style.transform = 'scale(1)'; };
  mark.onpointercancel = () => { mark.style.transform = 'scale(1)'; };

  let hint = byId('welcomeVoiceHint');
  if (!hint) {
    hint = document.createElement('div');
    hint.id = 'welcomeVoiceHint';
    hint.textContent = 'Tap the waveform to talk';
    Object.assign(hint.style, {
      margin:'-9px auto 15px', color:'var(--accent-ink)', fontSize:'12px', fontWeight:'700'
    });
    mark.insertAdjacentElement('afterend', hint);
  }

  return 'ready';
})();
""".trimIndent()

private fun voiceUiReadyScript(): String = """
(() => {
  const ids = ['voiceLang','voiceBtn','voiceCallLaunch'];
  const controls = ids.every(id => !!document.getElementById(id));
  const mark = document.getElementById('welcomeVoiceButton');
  const touchable = !!mark && mark.classList.contains('voice-touch-ready');
  return controls && touchable ? 'ready' : 'pending';
})();
""".trimIndent()

private fun themeSyncScript(darkTheme: Boolean): String = if (darkTheme) {
    """
    (() => {
      const root = document.documentElement;
      root.setAttribute('data-app-theme','dark');
      root.style.colorScheme = 'dark';
      const values = {
        '--bg':'#0e0f12','--surface':'#141519','--soft':'#1d1f24','--surface-soft':'#1d1f24',
        '--surface-strong':'#292c33','--text':'#f4f4f6','--muted':'#b7bac2','--faint':'#848995',
        '--accent':'#aeb0ff','--accent2':'#b896ff','--accent-soft':'#252541','--accent-ink':'#d0d1ff',
        '--border':'#2d3037','--danger':'#ffb4ac','--ok':'#76dfa8','--user':'#24262c',
        '--shadow':'0 18px 55px rgba(0,0,0,.32)'
      };
      Object.entries(values).forEach(([key,value]) => root.style.setProperty(key,value));
      return 'ready';
    })();
    """.trimIndent()
} else {
    """
    (() => {
      const root = document.documentElement;
      root.setAttribute('data-app-theme','light');
      root.style.colorScheme = 'light';
      const values = {
        '--bg':'#fbfbfd','--surface':'#ffffff','--soft':'#f2f3f7','--surface-soft':'#f2f3f7',
        '--surface-strong':'#e8eaf0','--text':'#17181c','--muted':'#727680','--faint':'#9b9fa8',
        '--accent':'#4b4ee8','--accent2':'#7b5cff','--accent-soft':'#eeeeff','--accent-ink':'#3539c8',
        '--border':'#e6e7ec','--danger':'#c9362b','--ok':'#138a55','--user':'#eff0f4',
        '--shadow':'0 18px 55px rgba(25,27,40,.10)'
      };
      Object.entries(values).forEach(([key,value]) => root.style.setProperty(key,value));
      return 'ready';
    })();
    """.trimIndent()
}

private suspend fun evaluateForResult(webView: WebView, script: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        runCatching {
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result?.contains("ready") == true)
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(false)
        }
    }
