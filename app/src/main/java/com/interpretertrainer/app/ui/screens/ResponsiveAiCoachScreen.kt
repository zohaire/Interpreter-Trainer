package com.interpretertrainer.app.ui.screens

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.interpretertrainer.app.ui.theme.ThemeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wraps the existing Interpreter AI screen with two app-level improvements:
 * 1) the WebView receives the same light/dark configuration as the Compose app; and
 * 2) a small idempotent JavaScript layer turns voice chat into low-latency, interruptible speech.
 *
 * Keeping this layer separate lets the existing Puter authentication and native microphone bridge
 * continue to work unchanged.
 */
@Composable
fun ResponsiveAiCoachScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    themeMode: ThemeMode
) {
    val baseContext = LocalContext.current
    val rootView = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val webContext = remember(baseContext, darkTheme) {
        val configuration = Configuration(baseContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        baseContext.createConfigurationContext(configuration)
    }

    CompositionLocalProvider(LocalContext provides webContext) {
        AiCoachScreen(
            onBack = onBack,
            sessionViewModel = sessionViewModel
        )
    }

    LaunchedEffect(rootView, darkTheme) {
        // AndroidView can appear a few frames after this composable. Re-find it until the page and
        // the original Interpreter AI enhancements are both ready, then install once.
        repeat(32) {
            val webView = findFirstWebView(rootView)
            if (webView != null) {
                val installed = evaluateForResult(webView, fastVoiceEnhancementScript())
                if (installed) return@LaunchedEffect
            }
            delay(180)
        }
    }
}

private fun findFirstWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findFirstWebView(view.getChildAt(index))?.let { return it }
        }
    }
    return null
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

/**
 * Voice behaviour added on top of coachEnhancementScript():
 * - begins speaking from the first completed phrase while the model is still streaming;
 * - keeps a JavaScript speech queue so native TTS can speak chunks continuously;
 * - arms recognition while AI audio is playing and ignores likely speaker echo;
 * - stops AI speech immediately when the user's words differ from the spoken AI text;
 * - makes a tap on the call microphone interrupt speech immediately rather than merely mute it;
 * - keeps voice answers shorter by default, reducing both model and TTS latency.
 */
private fun fastVoiceEnhancementScript(): String = """
(() => {
  if (window.__fastInterpreterVoiceV2) return 'ready';
  if (!window.__interpreterEnhancementsV3) return 'pending';
  window.__fastInterpreterVoiceV2 = true;

  const native = window.InterpreterNative;
  if (!native) return 'pending';

  const state = {
    queue: [],
    speaking: false,
    streamComplete: false,
    streamAnswer: '',
    queuedThrough: 0,
    speechReference: '',
    bargeArmed: false,
    userBarging: false,
    responseId: 0,
    language: 'en-US'
  };
  window.__fastVoiceState = state;

  const inputNode = () => document.getElementById('chatInput');
  const callLanguage = () => document.getElementById('callVoiceLang')?.value || 'en-US';
  const oneShotLanguage = () => document.getElementById('voiceLang')?.value || 'en-US';

  const cleanForSpeech = value => String(value || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[`*_#>]/g, ' ')
    .replace(/\[(.*?)\]\([^)]*\)/g, '${1}')
    .replace(/\s+/g, ' ')
    .trim();

  const normalize = value => cleanForSpeech(value)
    .toLocaleLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  const likelyEcho = value => {
    const heard = normalize(value);
    const spoken = normalize(state.speechReference);
    if (heard.length < 4 || spoken.length < 4) return false;
    if (spoken.includes(heard)) return true;
    const heardWords = heard.split(' ').filter(Boolean);
    if (!heardWords.length) return false;
    const spokenWords = new Set(spoken.split(' ').filter(Boolean));
    const overlap = heardWords.filter(word => spokenWords.has(word)).length / heardWords.length;
    return overlap >= 0.72;
  };

  const setOrb = mode => {
    const orb = document.getElementById('voiceOrb');
    if (!orb) return;
    orb.classList.remove('listening', 'speaking');
    if (mode) orb.classList.add(mode);
  };

  const setCallStatus = (status, liveText) => {
    const statusNode = document.getElementById('voiceCallStatus');
    const liveNode = document.getElementById('voiceCallLive');
    if (statusNode) statusNode.textContent = status;
    if (liveNode && liveText !== undefined) liveNode.textContent = liveText;
  };

  const stopAllSpeech = () => {
    state.queue = [];
    state.speaking = false;
    state.bargeArmed = false;
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
    try { native.stopSpeaking?.(); } catch (_) {}
  };

  const scheduleNormalListening = (delay = 90) => {
    if (!window.__voiceCallActive || window.__voiceCallMuted || busy) return;
    setTimeout(() => {
      if (!window.__voiceCallActive || window.__voiceCallMuted || busy || state.speaking) return;
      const lang = callLanguage();
      native.setVoiceLanguage?.(lang);
      setCallStatus('Listening…', 'Speak now');
      setOrb('listening');
      native.startVoiceInput?.();
    }, delay);
  };

  const armBargeIn = () => {
    if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
    setTimeout(() => {
      if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
      state.bargeArmed = true;
      native.setVoiceLanguage?.(callLanguage());
      native.startVoiceInput?.();
    }, 360);
  };

  const pumpSpeech = () => {
    if (state.speaking || !state.queue.length) {
      if (!state.speaking && state.streamComplete && !state.queue.length && window.__voiceCallActive && !state.userBarging) {
        setOrb(null);
        setCallStatus('Your turn', 'Listening…');
        scheduleNormalListening(80);
      }
      return;
    }

    const next = state.queue.shift();
    if (!next || next.responseId !== state.responseId || !next.text) {
      pumpSpeech();
      return;
    }

    state.language = next.language;
    state.speaking = true;
    state.speechReference = (state.speechReference + ' ' + next.text).trim().slice(-1800);
    if (window.__voiceCallActive) {
      setCallStatus('Interpreter AI is speaking', state.streamAnswer || next.text);
      setOrb('speaking');
    }

    const started = native.speakText?.(next.text, next.language) === true;
    if (!started) {
      state.speaking = false;
      pumpSpeech();
    }
  };

  const enqueueSpeech = (text, language, responseId) => {
    const cleaned = cleanForSpeech(text);
    if (!cleaned) return;
    state.queue.push({ text: cleaned, language, responseId });
    pumpSpeech();
  };

  const findCut = (pending, force) => {
    if (!pending) return 0;
    if (force) return pending.length;
    if (pending.length < 58) return 0;

    const preferred = Math.min(pending.length, 155);
    const windowText = pending.slice(0, preferred);
    let punctuation = -1;
    for (let i = 42; i < windowText.length; i++) {
      if (/[.!?؟؛;:]/.test(windowText[i])) {
        punctuation = i + 1;
        break;
      }
    }
    if (punctuation > 0) return punctuation;
    if (pending.length < 118) return 0;

    const hard = Math.min(110, pending.length);
    const space = pending.lastIndexOf(' ', hard);
    return space >= 70 ? space : hard;
  };

  const queueReadySpeech = (force = false) => {
    const language = state.language;
    while (state.queuedThrough < state.streamAnswer.length) {
      const pending = state.streamAnswer.slice(state.queuedThrough);
      const cut = findCut(pending, force);
      if (!cut) break;
      const piece = pending.slice(0, cut);
      state.queuedThrough += cut;
      enqueueSpeech(piece, language, state.responseId);
      if (!force) break;
    }
  };

  window.__nativeSpeechStarted = () => {
    if (!state.speaking) return;
    if (window.__voiceCallActive) {
      setCallStatus('Interpreter AI is speaking', state.streamAnswer || 'You can interrupt at any time.');
      setOrb('speaking');
      armBargeIn();
    }
  };

  window.__nativeSpeechFinished = () => {
    if (!state.speaking) return;
    state.speaking = false;
    state.bargeArmed = false;
    try { native.stopVoiceInput?.(); } catch (_) {}
    pumpSpeech();
  };

  window.__voiceInputStarted = () => {
    const mic = document.getElementById('voiceBtn');
    mic?.classList.add('listening');
    if (mic) mic.title = 'Listening…';
    const error = document.getElementById('chatError');
    if (error) error.textContent = '';
    if (window.__voiceCallActive && !state.bargeArmed) {
      setCallStatus('Listening…', state.userBarging ? 'Go ahead — I stopped the AI.' : 'Speak now');
      setOrb('listening');
    }
  };

  window.__voiceInputStopped = () => {
    const mic = document.getElementById('voiceBtn');
    mic?.classList.remove('listening');
    if (mic) mic.title = 'Ask by voice';
  };

  const beginUserBargeIn = text => {
    state.userBarging = true;
    state.bargeArmed = false;
    state.queue = [];
    state.streamComplete = true;
    try { native.stopSpeaking?.(); } catch (_) {}
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
    state.speaking = false;
    setCallStatus('Listening…', text || 'Go ahead — I stopped the AI.');
    setOrb('listening');
  };

  window.__voiceInputPartial = text => {
    const value = String(text || '').trim();
    if (!value) return;

    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      if (likelyEcho(value)) return;
      if (normalize(value).length >= 3) beginUserBargeIn(value);
      return;
    }

    if (window.__voiceCallActive) {
      if (state.userBarging) {
        setCallStatus('Listening…', value);
      } else {
        setCallStatus('Listening…', value);
      }
      return;
    }

    const input = inputNode();
    if (!input) return;
    input.value = value;
    window.resizeComposer?.();
    window.updateSendState?.();
  };

  window.__voiceInputResult = text => {
    window.__voiceInputStopped?.();
    const value = String(text || '').trim();
    if (!value) return;

    if (window.__voiceCallActive && state.bargeArmed && state.speaking && likelyEcho(value)) {
      state.bargeArmed = false;
      armBargeIn();
      return;
    }

    if (window.__voiceCallActive && (state.bargeArmed || state.speaking || state.userBarging)) {
      beginUserBargeIn(value);
    }

    const input = inputNode();
    if (!input) return;
    input.value = value;
    window.resizeComposer?.();
    window.updateSendState?.();

    window.__voiceAutoSpeak = true;
    window.__voiceOneShot = !window.__voiceCallActive;
    setCallStatus('Thinking…', value);
    setOrb(null);
    state.userBarging = false;
    window.sendChat?.(true);
  };

  window.__voiceInputError = message => {
    window.__voiceInputStopped?.();
    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      state.bargeArmed = false;
      armBargeIn();
      return;
    }
    if (window.__voiceCallActive) {
      setCallStatus('Listening…', String(message || '') + ' Trying again…');
      setOrb(null);
      scheduleNormalListening(260);
    } else {
      const error = document.getElementById('chatError');
      if (error) error.textContent = message;
    }
  };

  const muteButton = document.getElementById('voiceMute');
  if (muteButton) {
    muteButton.onclick = event => {
      // While the AI is speaking this control behaves as an immediate interrupt button.
      if (state.speaking) {
        beginUserBargeIn('Go ahead — I stopped the AI.');
        native.setVoiceLanguage?.(callLanguage());
        native.startVoiceInput?.();
        return;
      }

      window.__voiceCallMuted = !window.__voiceCallMuted;
      event.currentTarget.classList.toggle('muted', window.__voiceCallMuted);
      event.currentTarget.textContent = window.__voiceCallMuted ? '🔇' : '🎙';
      if (window.__voiceCallMuted) {
        native.stopVoiceInput?.();
        setCallStatus('Muted', 'Tap the microphone button to continue.');
        setOrb(null);
      } else {
        scheduleNormalListening(70);
      }
    };
  }

  const originalEndVoiceCall = window.endVoiceCall;
  window.endVoiceCall = () => {
    state.responseId++;
    state.queue = [];
    state.speaking = false;
    state.streamComplete = true;
    state.bargeArmed = false;
    state.userBarging = false;
    stopAllSpeech();
    return originalEndVoiceCall?.();
  };
  const endButton = document.getElementById('voiceEnd');
  if (endButton) endButton.onclick = window.endVoiceCall;

  window.sendChat = async function(fromVoice = false) {
    if (busy) return;
    const input = inputNode();
    const text = input?.value?.trim() || '';
    if (!text) return;

    const errorNode = document.getElementById('chatError');
    if (errorNode) errorNode.textContent = '';
    addMessage('user', text);
    input.value = '';
    resizeComposer();
    updateSendState();

    if (!(await ensureConnected())) {
      if (window.__voiceCallActive) setCallStatus('Connection failed', 'Unable to reach Interpreter AI.');
      return;
    }

    busy = true;
    updateSendState();
    showTyping();

    let streamRow = null;
    let answer = '';
    const responseId = ++state.responseId;
    const voiceResponse = fromVoice || window.__voiceCallActive || window.__voiceAutoSpeak || window.__voiceOneShot;
    state.queue = [];
    state.speaking = false;
    state.streamComplete = false;
    state.streamAnswer = '';
    state.queuedThrough = 0;
    state.speechReference = '';
    state.bargeArmed = false;
    state.userBarging = false;
    state.language = window.__voiceCallActive ? callLanguage() : oneShotLanguage();

    try {
      const system = `You are Interpreter AI, a fast professional coach for interpreters. Work especially well across Arabic, English and French. Help with simultaneous and consecutive interpreting, shadowing, transcription, note-taking, memory, terminology, reformulation, numbers, names, fluency and delivery. In voice conversations, answer naturally and directly in the user's language. Unless the user explicitly asks for detail, keep a voice reply to 1–3 short sentences so the conversation stays fast. Never invent scores, transcripts, history or app facts. The authoritative app/context information below is reliable.\n\n${nativePracticeContext()}`;
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];
      const stream = await puter.ai.chat(conversation, {
        model:'qwen/qwen3.6-27b',
        stream:true,
        max_tokens:voiceResponse ? 260 : 650,
        temperature:0.22
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
        state.streamAnswer = answer;
        if (bubble) bubble.textContent = answer;
        requestAnimationFrame(scrollToBottom);

        // Start speaking as soon as a natural phrase is available instead of waiting for the whole
        // response. This is the main latency reduction users perceive in live voice mode.
        if (voiceResponse && responseId === state.responseId) queueReadySpeech(false);
      }

      answer = answer.trim();
      if (!answer) throw new Error('The AI returned an empty response.');
      state.streamAnswer = answer;
      state.streamComplete = true;
      if (voiceResponse && responseId === state.responseId) queueReadySpeech(true);

      streamRow?.remove();
      streamRow = null;
      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-16);
      saveHistory();
      addMessage('assistant', answer);
      setStatus('Online · ready', 'ok');

      if (!voiceResponse && window.__voiceCallActive) {
        // Defensive fallback; voice calls normally enter the streaming queue above.
        state.streamAnswer = answer;
        state.streamComplete = true;
        state.queuedThrough = 0;
        queueReadySpeech(true);
      }

      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
      pumpSpeech();
    } catch (error) {
      hideTyping();
      streamRow?.remove();
      state.queue = [];
      state.speaking = false;
      state.streamComplete = true;
      const message = error?.msg || error?.message || String(error);
      if (errorNode) errorNode.textContent = 'Interpreter AI could not answer: ' + message;
      setStatus('Request failed', 'bad');
      if (window.__voiceCallActive) {
        setCallStatus('Something went wrong', message);
        scheduleNormalListening(250);
      }
      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
    } finally {
      busy = false;
      updateSendState();
      if (!window.__voiceCallActive) setTimeout(() => input?.focus(), 40);
      else if (state.streamComplete && !state.speaking && !state.queue.length) scheduleNormalListening(80);
    }
  };

  return 'ready';
})()
""".trimIndent()
