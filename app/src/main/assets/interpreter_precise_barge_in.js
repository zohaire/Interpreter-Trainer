(() => {
  if (window.__interpreterConversationalBargeInV4) return 'ready';
  const native = window.InterpreterNative;
  const state = window.__fastInterpreterVoiceState;
  if (!native || !state || !window.__fastInterpreterVoiceV3) return 'pending';
  window.__interpreterConversationalBargeInV4 = true;

  const baseSpeechFinished = window.__nativeSpeechFinished;
  const baseStartVoiceCall = window.startVoiceCall;
  const baseEndVoiceCall = window.endVoiceCall;

  const turn = {
    phase: 'idle', // idle | listening | thinking | speaking | barge-listening
    candidate: '',
    candidateHits: 0,
    candidateStartedAt: 0,
    candidateLastAt: 0,
    lastBargeText: '',
    armGeneration: 0,
    monitorMode: 'none' // none | vad | recognizer
  };
  window.__interpreterLiveTurnState = turn;

  const clean = value => String(value || '')
    .toLocaleLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  const words = value => clean(value).split(' ').filter(Boolean);

  const tokenOverlap = (a, b) => {
    const left = words(a);
    const right = words(b);
    if (!left.length || !right.length) return 0;
    const rightSet = new Set(right);
    return left.filter(word => rightSet.has(word)).length / left.length;
  };

  const spokenReference = () => clean((state.speechReference || '') + ' ' + (state.streamAnswer || ''));

  const likelySpeakerEcho = value => {
    const heard = clean(value);
    const spoken = spokenReference();
    if (!heard || !spoken) return false;
    if (heard.length < 5) return true;
    if (spoken.includes(heard)) return true;
    const heardWords = words(heard);
    const spokenWords = new Set(words(spoken));
    const overlap = heardWords.filter(word => spokenWords.has(word)).length / Math.max(1, heardWords.length);
    return overlap >= 0.60;
  };

  const similarCandidate = (a, b) => {
    const left = clean(a);
    const right = clean(b);
    if (!left || !right) return false;
    if (left === right || left.startsWith(right) || right.startsWith(left)) return true;
    return tokenOverlap(left, right) >= 0.62;
  };

  const resetCandidate = () => {
    turn.candidate = '';
    turn.candidateHits = 0;
    turn.candidateStartedAt = 0;
    turn.candidateLastAt = 0;
  };

  const observeFallbackInterruption = (value, isFinal = false) => {
    const text = clean(value);
    if (!text || likelySpeakerEcho(text)) {
      resetCandidate();
      return false;
    }

    const now = Date.now();
    const count = words(text).length;
    if (count < 2 && !['stop','wait','attends','توقف','انتظر','لحظة'].includes(text)) return false;

    if (turn.candidate && similarCandidate(turn.candidate, text) && now - turn.candidateLastAt <= 800) {
      turn.candidateHits += 1;
    } else {
      turn.candidate = text;
      turn.candidateHits = 1;
      turn.candidateStartedAt = now;
    }
    turn.candidateLastAt = now;
    turn.candidate = text;

    if (turn.candidateHits >= 2 && now - turn.candidateStartedAt >= 55) return true;
    return isFinal && count >= 4 && text.length >= 14 && tokenOverlap(text, spokenReference()) < 0.20;
  };

  const setOrb = mode => {
    const orb = document.getElementById('voiceOrb');
    if (!orb) return;
    orb.classList.remove('listening', 'speaking');
    if (mode) orb.classList.add(mode);
  };

  const setStatus = (status, text) => {
    const statusNode = document.getElementById('voiceCallStatus');
    const liveNode = document.getElementById('voiceCallLive');
    if (statusNode) statusNode.textContent = status;
    if (liveNode && text !== undefined) liveNode.textContent = text;
  };

  const callLanguage = () => document.getElementById('callVoiceLang')?.value || 'en-US';

  const writeInput = value => {
    const input = document.getElementById('chatInput');
    if (!input) return false;
    input.value = value;
    window.resizeComposer?.();
    window.updateSendState?.();
    return true;
  };

  const cancelOldAiResponse = () => {
    state.responseId += 1;
    try {
      if (typeof busy !== 'undefined' && busy) {
        busy = false;
        hideTyping?.();
        updateSendState?.();
      }
    } catch (_) {}
  };

  const stopMonitoring = () => {
    turn.armGeneration += 1;
    try { native.stopBargeInDetection?.(); } catch (_) {}
    if (turn.monitorMode === 'recognizer' && turn.phase !== 'barge-listening') {
      try { native.stopVoiceInput?.(); } catch (_) {}
    }
    turn.monitorMode = 'none';
    state.bargeArmed = false;
  };

  const beginBargeListening = value => {
    const heard = String(value || '').trim();
    if (turn.phase === 'barge-listening') {
      if (heard) turn.lastBargeText = heard;
      setStatus('Listening…', turn.lastBargeText || 'Keep speaking');
      setOrb('listening');
      return;
    }

    turn.armGeneration += 1;
    try { native.stopBargeInDetection?.(); } catch (_) {}
    cancelOldAiResponse();
    state.userBarging = true;
    state.bargeArmed = false;
    state.queue = [];
    state.streamComplete = true;
    state.speaking = false;
    turn.phase = 'barge-listening';
    turn.monitorMode = 'none';
    turn.lastBargeText = heard;
    resetCandidate();

    // Stop only output. The full utterance is captured after this handoff and submitted once final.
    try { native.stopSpeaking?.(); } catch (_) {}
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}

    setStatus('Listening…', turn.lastBargeText || 'Go ahead — I am listening.');
    setOrb('listening');
  };

  const startRecognizerForInterruptedTurn = () => {
    native.setVoiceLanguage?.(callLanguage());
    setTimeout(() => {
      if (!window.__voiceCallActive || turn.phase !== 'barge-listening') return;
      native.startVoiceInput?.();
    }, 28);
  };

  const submitCompletedTurn = value => {
    const text = String(value || turn.lastBargeText || '').trim();
    if (!text || !writeInput(text)) return;

    turn.phase = 'thinking';
    turn.monitorMode = 'none';
    turn.lastBargeText = '';
    state.userBarging = false;
    state.bargeArmed = false;
    resetCandidate();

    window.__voiceAutoSpeak = true;
    window.__voiceOneShot = !window.__voiceCallActive;
    if (window.__voiceCallActive) {
      setStatus('Thinking…', text);
      setOrb(null);
    }
    window.sendChat?.(true);
  };

  const armInterruptionMonitoring = (delay = 15) => {
    const generation = ++turn.armGeneration;
    resetCandidate();
    state.bargeArmed = false;

    setTimeout(() => {
      if (generation !== turn.armGeneration) return;
      if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;

      turn.phase = 'speaking';
      state.bargeArmed = true;

      // Preferred path: native VAD with VOICE_COMMUNICATION + AEC. It detects the user's onset
      // without asking SpeechRecognizer to transcribe under loudspeaker playback.
      let nativeVadStarted = false;
      try { nativeVadStarted = native.startBargeInDetection?.() === true; } catch (_) {}
      if (nativeVadStarted) {
        turn.monitorMode = 'vad';
        return;
      }

      // Fallback for devices where AudioRecord/AEC cannot start.
      turn.monitorMode = 'recognizer';
      native.setVoiceLanguage?.(callLanguage());
      native.startVoiceInput?.();
    }, delay);
  };

  const rearmWhileSpeaking = (delay = 70) => {
    if (!state.speaking || state.userBarging) return;
    stopMonitoring();
    armInterruptionMonitoring(delay);
  };

  window.__nativeSpeechStarted = () => {
    if (!state.speaking) return;
    turn.phase = 'speaking';
    if (!window.__voiceCallActive) return;
    setStatus('Interpreter AI is speaking', state.streamAnswer || 'You can interrupt me while I speak.');
    setOrb('speaking');
    armInterruptionMonitoring(15);
  };

  // Called directly by the native VAD as soon as real near-end speech is detected.
  window.__nativeBargeInDetected = () => {
    if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
    beginBargeListening('');
    startRecognizerForInterruptedTurn();
  };

  window.__nativeSpeechFinished = () => {
    try { native.stopBargeInDetection?.(); } catch (_) {}
    turn.monitorMode = 'none';
    if (turn.phase === 'barge-listening' || state.userBarging) return;
    turn.armGeneration += 1;
    baseSpeechFinished?.();
  };

  window.__voiceInputStarted = () => {
    const mic = document.getElementById('voiceBtn');
    mic?.classList.add('listening');
    if (mic) mic.title = 'Listening…';
    const error = document.getElementById('chatError');
    if (error) error.textContent = '';

    if (!window.__voiceCallActive) return;
    if (turn.phase === 'barge-listening') {
      setStatus('Listening…', turn.lastBargeText || 'Keep speaking');
      setOrb('listening');
    } else if (!state.speaking) {
      turn.phase = 'listening';
      setStatus('Listening…', 'Speak now');
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

    if (window.__voiceCallActive && turn.phase === 'barge-listening') {
      turn.lastBargeText = value;
      setStatus('Listening…', value);
      setOrb('listening');
      return;
    }

    // Only the fallback recognizer-monitor path receives partials while AI is speaking.
    if (window.__voiceCallActive && turn.monitorMode === 'recognizer' && state.bargeArmed && state.speaking) {
      if (observeFallbackInterruption(value, false)) beginBargeListening(value);
      return;
    }

    if (window.__voiceCallActive) {
      turn.phase = 'listening';
      setStatus('Listening…', value);
      setOrb('listening');
      return;
    }

    writeInput(value);
  };

  window.__voiceInputResult = text => {
    window.__voiceInputStopped?.();
    const value = String(text || '').trim();
    if (!value) return;

    if (window.__voiceCallActive && turn.phase === 'barge-listening') {
      submitCompletedTurn(value);
      return;
    }

    if (window.__voiceCallActive && turn.monitorMode === 'recognizer' && state.bargeArmed && state.speaking) {
      if (observeFallbackInterruption(value, true)) {
        beginBargeListening(value);
        submitCompletedTurn(value);
      } else {
        rearmWhileSpeaking(70);
      }
      return;
    }

    if (window.__voiceCallActive && state.speaking) {
      rearmWhileSpeaking(70);
      return;
    }

    submitCompletedTurn(value);
  };

  window.__voiceInputError = message => {
    window.__voiceInputStopped?.();
    resetCandidate();

    if (window.__voiceCallActive && turn.phase === 'barge-listening') {
      const fallback = String(turn.lastBargeText || '').trim();
      if (words(fallback).length >= 2) {
        submitCompletedTurn(fallback);
      } else {
        native.setVoiceLanguage?.(callLanguage());
        setTimeout(() => {
          if (window.__voiceCallActive && turn.phase === 'barge-listening') native.startVoiceInput?.();
        }, 55);
      }
      return;
    }

    if (window.__voiceCallActive && state.speaking) {
      rearmWhileSpeaking(90);
      return;
    }

    if (window.__voiceCallActive) {
      turn.phase = 'listening';
      setStatus('Listening…', String(message || '') + ' Trying again…');
      setOrb(null);
      setTimeout(() => {
        if (!window.__voiceCallActive || window.__voiceCallMuted || state.speaking) return;
        native.setVoiceLanguage?.(callLanguage());
        native.startVoiceInput?.();
      }, 70);
    } else {
      const error = document.getElementById('chatError');
      if (error) error.textContent = message;
    }
  };

  const muteButton = document.getElementById('voiceMute');
  if (muteButton) {
    muteButton.onclick = event => {
      if (state.speaking || state.bargeArmed || turn.phase === 'speaking') {
        beginBargeListening('');
        startRecognizerForInterruptedTurn();
        return;
      }

      window.__voiceCallMuted = !window.__voiceCallMuted;
      event.currentTarget.classList.toggle('muted', window.__voiceCallMuted);
      event.currentTarget.textContent = window.__voiceCallMuted ? '🔇' : '🎙';
      if (window.__voiceCallMuted) {
        turn.phase = 'idle';
        stopMonitoring();
        native.stopVoiceInput?.();
        setStatus('Muted', 'Tap the microphone button to continue.');
        setOrb(null);
      } else {
        turn.phase = 'listening';
        native.setVoiceLanguage?.(callLanguage());
        setTimeout(() => native.startVoiceInput?.(), 35);
      }
    };
  }

  window.startVoiceCall = async () => {
    turn.phase = 'listening';
    turn.monitorMode = 'none';
    turn.lastBargeText = '';
    turn.armGeneration += 1;
    resetCandidate();
    return baseStartVoiceCall?.();
  };

  window.endVoiceCall = () => {
    turn.phase = 'idle';
    turn.monitorMode = 'none';
    turn.lastBargeText = '';
    turn.armGeneration += 1;
    resetCandidate();
    state.userBarging = false;
    state.bargeArmed = false;
    try { native.stopBargeInDetection?.(); } catch (_) {}
    return baseEndVoiceCall?.();
  };
  const endButton = document.getElementById('voiceEnd');
  if (endButton) endButton.onclick = window.endVoiceCall;

  return 'ready';
})();
