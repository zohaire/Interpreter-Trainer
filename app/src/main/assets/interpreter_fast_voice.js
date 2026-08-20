(() => {
  if (window.__fastInterpreterVoiceV4) return 'ready';
  if (!window.__interpreterEnhancementsV3 || !window.InterpreterNative) return 'pending';
  window.__fastInterpreterVoiceV4 = true;

  const native = window.InterpreterNative;
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
  window.__fastInterpreterVoiceState = state;

  const themeStyle = document.createElement('style');
  themeStyle.id = 'interpreter-app-theme-sync';
  themeStyle.textContent = `
    html[data-app-theme="light"] {
      --bg:#fbfbfd; --surface:#ffffff; --soft:#f2f3f7; --surface-soft:#f2f3f7;
      --surface-strong:#e8eaf0; --text:#17181c; --muted:#727680; --faint:#9b9fa8;
      --accent:#4b4ee8; --accent2:#7b5cff; --accent-soft:#eeeeff; --accent-ink:#3539c8;
      --border:#e6e7ec; --danger:#c9362b; --ok:#138a55; --user:#eff0f4;
      --shadow:0 18px 55px rgba(25,27,40,.10);
    }
    html[data-app-theme="dark"] {
      --bg:#0e0f12; --surface:#141519; --soft:#1d1f24; --surface-soft:#1d1f24;
      --surface-strong:#292c33; --text:#f4f4f6; --muted:#b7bac2; --faint:#848995;
      --accent:#aeb0ff; --accent2:#b896ff; --accent-soft:#252541; --accent-ink:#d0d1ff;
      --border:#2d3037; --danger:#ffb4ac; --ok:#76dfa8; --user:#24262c;
      --shadow:0 18px 55px rgba(0,0,0,.32);
    }
    html[data-app-theme="dark"], html[data-app-theme="dark"] body,
    html[data-app-theme="dark"] .app { background:#0e0f12 !important; color:#f4f4f6 !important; }
  `;
  document.head.appendChild(themeStyle);

  const inputNode = () => document.getElementById('chatInput');
  const callLanguage = () => document.getElementById('callVoiceLang')?.value || 'en-US';
  const oneShotLanguage = () => document.getElementById('voiceLang')?.value || 'en-US';

  const cleanForSpeech = value => String(value || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[`*_#>]/g, ' ')
    .replace(/\[(.*?)\]\([^)]*\)/g, (_, label) => label)
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

  const scheduleNormalListening = (delay = 85) => {
    if (!window.__voiceCallActive || window.__voiceCallMuted || busy) return;
    setTimeout(() => {
      if (!window.__voiceCallActive || window.__voiceCallMuted || busy || state.speaking) return;
      native.setVoiceLanguage?.(callLanguage());
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
    }, 320);
  };

  const pumpSpeech = () => {
    if (state.speaking || !state.queue.length) {
      if (!state.speaking && state.streamComplete && !state.queue.length && window.__voiceCallActive && !state.userBarging) {
        setOrb(null);
        setCallStatus('Your turn', 'Listening…');
        scheduleNormalListening(70);
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
    if (pending.length < 32) return 0;

    const preferred = Math.min(pending.length, 105);
    const windowText = pending.slice(0, preferred);
    for (let i = 24; i < windowText.length; i++) {
      if (/[.!?؟؛;:]/.test(windowText[i])) return i + 1;
    }

    if (pending.length < 78) return 0;
    const hard = Math.min(72, pending.length);
    const space = pending.lastIndexOf(' ', hard);
    return space >= 48 ? space : hard;
  };

  const queueReadySpeech = (force = false) => {
    while (state.queuedThrough < state.streamAnswer.length) {
      const pending = state.streamAnswer.slice(state.queuedThrough);
      const cut = findCut(pending, force);
      if (!cut) break;
      const piece = pending.slice(0, cut);
      state.queuedThrough += cut;
      enqueueSpeech(piece, state.language, state.responseId);
      if (!force) break;
    }
  };

  const cancelCurrentResponseForBargeIn = () => {
    if (!state.userBarging) {
      state.responseId += 1;
      if (busy) {
        busy = false;
        try { hideTyping(); } catch (_) {}
        try { updateSendState(); } catch (_) {}
      }
    }
  };

  const beginUserBargeIn = text => {
    cancelCurrentResponseForBargeIn();
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

  window.__voiceInputPartial = text => {
    const value = String(text || '').trim();
    if (!value) return;

    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      if (likelyEcho(value)) return;
      if (normalize(value).length >= 3) beginUserBargeIn(value);
      return;
    }

    if (window.__voiceCallActive) {
      setCallStatus('Listening…', value);
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
    if (window.__voiceCallActive) {
      setCallStatus('Thinking…', value);
      setOrb(null);
    }
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
      scheduleNormalListening(220);
    } else {
      const error = document.getElementById('chatError');
      if (error) error.textContent = message;
    }
  };

  const muteButton = document.getElementById('voiceMute');
  if (muteButton) {
    muteButton.onclick = event => {
      if (state.speaking || state.bargeArmed) {
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
        scheduleNormalListening(60);
      }
    };
  }

  const originalEndVoiceCall = window.endVoiceCall;
  window.endVoiceCall = () => {
    state.responseId += 1;
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

    // IMPORTANT: keep the first Puter cloud call in the original click/voice callback stack.
    // Awaiting even a synchronous readiness helper first can consume the transient user activation
    // that Android WebView needs when Puter opens its first-use authentication window.
    if (ensureConnected() === false) {
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
      const system = 'You are Interpreter AI, a fast professional coach for interpreters. Work especially well across Arabic, English and French. Help with simultaneous and consecutive interpreting, shadowing, transcription, note-taking, memory, terminology, reformulation, numbers, names, fluency and delivery. In voice conversations, answer naturally and directly in the user\'s language. Unless the user explicitly asks for detail, keep a voice reply to 1–3 short sentences so the conversation stays fast. Never invent scores, transcripts, history or app facts. The authoritative app/context information below is reliable.\n\n' + nativePracticeContext();
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];
      const streamPromise = puter.ai.chat(conversation, {
        model:'qwen/qwen3.6-27b',
        stream:true,
        max_tokens:voiceResponse ? 260 : 650,
        temperature:0.22
      });
      const stream = await streamPromise;

      if (responseId !== state.responseId) throw { __interrupted:true };
      hideTyping();
      streamRow = messageElement('assistant', '');
      streamRow.dataset.streaming = '1';
      document.getElementById('messages').appendChild(streamRow);
      const bubble = streamRow.querySelector('.bubble');

      for await (const part of stream) {
        if (responseId !== state.responseId) {
          try { await stream.return?.(); } catch (_) {}
          throw { __interrupted:true };
        }
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
        if (voiceResponse) queueReadySpeech(false);
      }

      if (responseId !== state.responseId) throw { __interrupted:true };
      answer = answer.trim();
      if (!answer) throw new Error('The AI returned an empty response.');
      state.streamAnswer = answer;
      state.streamComplete = true;
      if (voiceResponse) queueReadySpeech(true);

      streamRow?.remove();
      streamRow = null;
      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-16);
      saveHistory();
      addMessage('assistant', answer);
      setStatus('Online AI · ready · AIV4', 'ok');

      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
      pumpSpeech();
    } catch (error) {
      hideTyping();
      streamRow?.remove();
      if (error?.__interrupted) return;

      state.queue = [];
      state.speaking = false;
      state.streamComplete = true;
      const message = error?.msg || error?.message || String(error);
      if (errorNode) errorNode.textContent = 'Interpreter AI could not answer: ' + message;
      setStatus('Request failed · AIV4', 'bad');
      if (window.__voiceCallActive) {
        setCallStatus('Something went wrong', message);
        scheduleNormalListening(220);
      }
      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
    } finally {
      if (responseId === state.responseId) {
        busy = false;
        updateSendState();
        if (!window.__voiceCallActive) setTimeout(() => input?.focus(), 35);
        else if (state.streamComplete && !state.speaking && !state.queue.length) scheduleNormalListening(70);
      }
    }
  };

  return 'ready';
})()
