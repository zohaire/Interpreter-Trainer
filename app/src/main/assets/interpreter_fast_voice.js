(() => {
  if (window.__fastInterpreterVoiceV5) return 'ready';
  if (!window.__interpreterEnhancementsV3 || !window.InterpreterNative) return 'pending';
  window.__fastInterpreterVoiceV5 = true;
  window.__fastInterpreterVoiceV4 = true;
  window.__fastInterpreterVoiceV3 = true;

  const native = window.InterpreterNative;
  const live = window.InterpreterLiveNative || null;
  const state = {
    queue: [], speaking: false, streamComplete: false, streamAnswer: '', queuedThrough: 0,
    speechReference: '', bargeArmed: false, userBarging: false, responseId: 0, language: 'en-US'
  };
  window.__fastInterpreterVoiceState = state;

  const inputNode = () => document.getElementById('chatInput');
  const callLanguage = () => document.getElementById('callVoiceLang')?.value || 'en-US';
  const oneShotLanguage = () => document.getElementById('voiceLang')?.value || 'en-US';
  const cleanForSpeech = value => String(value || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[`*_#>]/g, ' ')
    .replace(/\[(.*?)\]\([^)]*\)/g, (_, label) => label)
    .replace(/\s+/g, ' ')
    .trim();

  // WebView can receive many tiny stream chunks inside one display frame. Painting every chunk
  // forces repeated layout/scroll work and makes the answer look jerky. Keep consuming the model
  // stream immediately, but update the visible bubble at most once per animation frame.
  let streamPaintFrame = 0;
  let pendingStreamBubble = null;
  let pendingStreamText = '';
  const paintStreamNow = () => {
    if (streamPaintFrame) cancelAnimationFrame(streamPaintFrame);
    streamPaintFrame = 0;
    if (pendingStreamBubble?.isConnected) pendingStreamBubble.textContent = pendingStreamText;
    pendingStreamBubble = null;
    pendingStreamText = '';
    window.scrollToBottom?.(false);
  };
  const scheduleStreamPaint = (bubble, text) => {
    pendingStreamBubble = bubble;
    pendingStreamText = text;
    if (streamPaintFrame) return;
    streamPaintFrame = requestAnimationFrame(() => {
      streamPaintFrame = 0;
      if (pendingStreamBubble?.isConnected) pendingStreamBubble.textContent = pendingStreamText;
      pendingStreamBubble = null;
      pendingStreamText = '';
      window.scrollToBottom?.(false);
    });
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

  const speakOutput = (text, language) => {
    const clean = cleanForSpeech(text);
    if (!clean) return false;

    if (window.__voiceCallActive && live) {
      try {
        if (live.speakText?.(clean, language) === true) return true;
      } catch (_) {}
    }

    try { return native.speakText?.(clean, language) === true; } catch (_) { return false; }
  };

  const stopOutput = () => {
    try { live?.stopSpeaking?.(); } catch (_) {}
    try { native.stopSpeaking?.(); } catch (_) {}
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
  };

  const scheduleNormalListening = (delay = 55) => {
    if (!window.__voiceCallActive || window.__voiceCallMuted || busy) return;
    setTimeout(() => {
      if (!window.__voiceCallActive || window.__voiceCallMuted || busy || state.speaking) return;
      native.setVoiceLanguage?.(callLanguage());
      setCallStatus('Listening…', 'Speak now');
      setOrb('listening');
      native.startVoiceInput?.();
    }, delay);
  };

  const pumpSpeech = () => {
    if (state.speaking) return;
    if (!state.queue.length) {
      if (state.streamComplete && window.__voiceCallActive && !state.userBarging) {
        setOrb(null);
        setCallStatus('Your turn', 'Listening…');
        scheduleNormalListening(45);
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

    if (!speakOutput(next.text, next.language)) {
      state.speaking = false;
      pumpSpeech();
    }
  };

  const enqueueSpeech = (text, language, responseId) => {
    const clean = cleanForSpeech(text);
    if (!clean) return;
    state.queue.push({ text: clean, language, responseId });
    pumpSpeech();
  };

  const findCut = (pending, force) => {
    if (!pending) return 0;
    if (force) return pending.length;
    if (pending.length < 22) return 0;

    const preferred = Math.min(pending.length, 72);
    for (let i = 18; i < preferred; i++) {
      if (/[.!?؟؛;:]/.test(pending[i])) return i + 1;
    }
    if (pending.length < 54) return 0;
    const hard = Math.min(58, pending.length);
    const space = pending.lastIndexOf(' ', hard);
    return space >= 36 ? space : hard;
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
      try {
        if (busy) {
          busy = false;
          hideTyping?.();
          updateSendState?.();
        }
      } catch (_) {}
    }
  };

  const beginUserBargeIn = text => {
    cancelCurrentResponseForBargeIn();
    state.userBarging = true;
    state.bargeArmed = false;
    state.queue = [];
    state.streamComplete = true;
    stopOutput();
    state.speaking = false;
    setCallStatus('Listening…', text || 'Go ahead — I stopped the AI.');
    setOrb('listening');
  };

  window.__nativeSpeechStarted = () => {
    if (!state.speaking || !window.__voiceCallActive) return;
    setCallStatus('Interpreter AI is speaking', state.streamAnswer || 'You can interrupt me.');
    setOrb('speaking');
  };

  window.__nativeSpeechFinished = () => {
    if (!state.speaking) return;
    state.speaking = false;
    state.bargeArmed = false;
    pumpSpeech();
  };

  window.__voiceInputStarted = () => {
    const mic = document.getElementById('voiceBtn');
    mic?.classList.add('listening');
    if (window.__voiceCallActive && !state.speaking) {
      setCallStatus('Listening…', 'Speak now');
      setOrb('listening');
    }
  };

  window.__voiceInputStopped = () => {
    document.getElementById('voiceBtn')?.classList.remove('listening');
  };

  window.__voiceInputPartial = text => {
    const value = String(text || '').trim();
    if (!value) return;
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
    if (window.__voiceCallActive && state.speaking) beginUserBargeIn(value);
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
    if (window.__voiceCallActive) {
      setCallStatus('Listening…', String(message || '') + ' Trying again…');
      scheduleNormalListening(100);
    } else {
      const error = document.getElementById('chatError');
      if (error) error.textContent = message;
    }
  };

  const muteButton = document.getElementById('voiceMute');
  if (muteButton) {
    muteButton.onclick = event => {
      if (state.speaking || state.bargeArmed) {
        beginUserBargeIn('');
        native.setVoiceLanguage?.(callLanguage());
        native.startVoiceInput?.();
        return;
      }
      window.__voiceCallMuted = !window.__voiceCallMuted;
      event.currentTarget.classList.toggle('muted', window.__voiceCallMuted);
      if (window.__voiceCallMuted) native.stopVoiceInput?.();
      else scheduleNormalListening(45);
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
    stopOutput();
    try { live?.stopBargeInDetection?.(); } catch (_) {}
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
      const system = 'You are Interpreter AI, a fast professional coach for interpreters. Work especially well across Arabic, English and French. Help with simultaneous and consecutive interpreting, shadowing, transcription, note-taking, memory, terminology, reformulation, numbers, names, fluency and delivery. In voice conversations, answer naturally and directly in the user\'s language. Unless the user explicitly asks for detail, keep a voice reply to 1–2 short sentences and put the direct answer first. Never invent scores, transcripts, history or app facts. The authoritative app/context information below is reliable.\n\n' + nativePracticeContext();
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];
      const stream = await puter.ai.chat(conversation, {
        model:'qwen/qwen3.8-max', stream:true, max_tokens:voiceResponse ? 180 : 650, temperature:0.20
      });

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
        const chunk = typeof part === 'string' ? part :
          (typeof part?.text === 'string' ? part.text :
            (typeof part?.delta?.content === 'string' ? part.delta.content : ''));
        if (!chunk) continue;
        answer += chunk;
        state.streamAnswer = answer;
        scheduleStreamPaint(bubble, answer);
        if (voiceResponse) queueReadySpeech(false);
      }

      if (responseId !== state.responseId) throw { __interrupted:true };
      answer = answer.trim();
      if (!answer) throw new Error('The AI returned an empty response.');
      state.streamAnswer = answer;
      state.streamComplete = true;
      if (voiceResponse) queueReadySpeech(true);
      paintStreamNow();

      streamRow?.remove();
      streamRow = null;
      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-16);
      saveHistory();
      addMessage('assistant', answer);
      setStatus('Online AI · ready · LIVE5', 'ok');
      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
      pumpSpeech();
    } catch (error) {
      hideTyping();
      if (streamPaintFrame) cancelAnimationFrame(streamPaintFrame);
      streamPaintFrame = 0;
      pendingStreamBubble = null;
      pendingStreamText = '';
      streamRow?.remove();
      if (error?.__interrupted) return;
      state.queue = [];
      state.speaking = false;
      state.streamComplete = true;
      const message = error?.msg || error?.message || String(error);
      if (errorNode) errorNode.textContent = 'Interpreter AI could not answer: ' + message;
      setStatus('Request failed · LIVE5', 'bad');
      if (window.__voiceCallActive) {
        setCallStatus('Something went wrong', message);
        scheduleNormalListening(120);
      }
      window.__voiceAutoSpeak = false;
      window.__voiceOneShot = false;
    } finally {
      if (responseId === state.responseId) {
        busy = false;
        updateSendState();
        if (!window.__voiceCallActive) setTimeout(() => input?.focus(), 35);
        else if (state.streamComplete && !state.speaking && !state.queue.length) scheduleNormalListening(45);
      }
    }
  };

  return 'ready';
})()