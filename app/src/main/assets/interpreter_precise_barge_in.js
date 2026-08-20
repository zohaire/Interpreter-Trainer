(() => {
  if (window.__interpreterPreciseBargeInV1) return 'ready';
  const native = window.InterpreterNative;
  const state = window.__fastInterpreterVoiceState;
  if (!native || !state || !window.__fastInterpreterVoiceV3) return 'pending';
  window.__interpreterPreciseBargeInV1 = true;

  const candidate = {
    text: '',
    hits: 0,
    startedAt: 0,
    lastAt: 0
  };

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

  const bigramOverlap = (a, b) => {
    const left = words(a);
    const right = words(b);
    if (left.length < 2 || right.length < 2) return 0;
    const rightPairs = new Set();
    for (let i = 0; i < right.length - 1; i++) rightPairs.add(right[i] + ' ' + right[i + 1]);
    let matches = 0;
    for (let i = 0; i < left.length - 1; i++) {
      if (rightPairs.has(left[i] + ' ' + left[i + 1])) matches += 1;
    }
    return matches / Math.max(1, left.length - 1);
  };

  const likelySpeakerEcho = value => {
    const heard = clean(value);
    const spoken = clean((state.speechReference || '') + ' ' + (state.streamAnswer || ''));
    if (!heard || !spoken) return false;

    // Very short recognitions are especially likely to be speaker leakage or random noise.
    if (heard.length < 4) return true;
    if (spoken.includes(heard)) return true;

    const heardWords = words(heard);
    if (!heardWords.length) return true;

    const coverage = tokenOverlap(heard, spoken);
    const pairCoverage = bigramOverlap(heard, spoken);

    if (heardWords.length <= 2) return coverage >= 0.50;
    if (pairCoverage >= 0.50) return true;
    return coverage >= 0.68;
  };

  const similarCandidate = (a, b) => {
    const left = clean(a);
    const right = clean(b);
    if (!left || !right) return false;
    if (left === right || left.startsWith(right) || right.startsWith(left)) return true;
    return tokenOverlap(left, right) >= 0.65;
  };

  const resetCandidate = () => {
    candidate.text = '';
    candidate.hits = 0;
    candidate.startedAt = 0;
    candidate.lastAt = 0;
  };

  const explicitShortCommand = value => {
    const text = clean(value);
    return new Set([
      'stop', 'wait', 'hold on', 'one second',
      'attends', 'attendez', 'stoppe', 'une seconde',
      'توقف', 'توقفي', 'انتظر', 'انتظري', 'لحظة', 'ثانية'
    ]).has(text);
  };

  const observeIntentionalSpeech = (value, isFinal = false) => {
    const text = clean(value);
    if (!text || likelySpeakerEcho(text)) {
      resetCandidate();
      return false;
    }

    const now = Date.now();
    const count = words(text).length;
    const shortCommand = explicitShortCommand(text);

    // Reject tiny/noisy fragments. Normal interruptions need at least two words.
    if (!shortCommand && (count < 2 || text.length < 6)) {
      resetCandidate();
      return false;
    }

    if (candidate.text && similarCandidate(candidate.text, text) && now - candidate.lastAt <= 900) {
      candidate.hits += 1;
    } else {
      candidate.text = text;
      candidate.hits = 1;
      candidate.startedAt = now;
    }
    candidate.lastAt = now;
    candidate.text = text;

    const age = now - candidate.startedAt;
    if (shortCommand) {
      // A one-word command must be repeatedly recognized before it may stop playback.
      return candidate.hits >= 3 && age >= 180;
    }

    if (isFinal) {
      // A substantial final recognition may confirm an interruption even if Android emitted only
      // one partial result; shorter finals still require a stable prior partial.
      if (count >= 3 && text.length >= 10) return true;
      return candidate.hits >= 2 && age >= 100;
    }

    // Partial results must be stable across multiple recognizer updates. This is the main guard
    // against random words, room noise and transient speaker echo cutting the AI off.
    return candidate.hits >= 2 && age >= 120;
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

  const rearmWhileSpeaking = (delay = 260) => {
    resetCandidate();
    state.bargeArmed = false;
    setTimeout(() => {
      if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
      state.bargeArmed = true;
      native.setVoiceLanguage?.(document.getElementById('callVoiceLang')?.value || 'en-US');
      native.startVoiceInput?.();
    }, delay);
  };

  const interruptForRealSpeech = value => {
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

    state.userBarging = true;
    state.bargeArmed = false;
    state.queue = [];
    state.streamComplete = true;
    resetCandidate();
    try { native.stopSpeaking?.(); } catch (_) {}
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
    state.speaking = false;
    setStatus('Listening…', value || 'Go ahead.');
    setOrb('listening');
  };

  const writeInput = value => {
    const input = document.getElementById('chatInput');
    if (!input) return false;
    input.value = value;
    window.resizeComposer?.();
    window.updateSendState?.();
    return true;
  };

  const continueWithUserTurn = value => {
    if (!writeInput(value)) return;
    window.__voiceAutoSpeak = true;
    window.__voiceOneShot = !window.__voiceCallActive;
    if (window.__voiceCallActive) {
      setStatus('Thinking…', value);
      setOrb(null);
    }
    state.userBarging = false;
    resetCandidate();
    window.sendChat?.(true);
  };

  window.__voiceInputPartial = text => {
    const value = String(text || '').trim();
    if (!value) return;

    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      if (observeIntentionalSpeech(value, false)) {
        interruptForRealSpeech(value);
      }
      return;
    }

    if (window.__voiceCallActive) {
      setStatus('Listening…', value);
      return;
    }

    writeInput(value);
  };

  window.__voiceInputResult = text => {
    window.__voiceInputStopped?.();
    const value = String(text || '').trim();
    if (!value) return;

    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      if (observeIntentionalSpeech(value, true)) {
        interruptForRealSpeech(value);
        continueWithUserTurn(value);
      } else {
        // Treat unconfirmed speech as echo/noise. The AI keeps talking and the interruption
        // recognizer is re-armed instead of stopping playback.
        rearmWhileSpeaking(280);
      }
      return;
    }

    if (window.__voiceCallActive && state.userBarging) {
      continueWithUserTurn(value);
      return;
    }

    if (window.__voiceCallActive && state.speaking) {
      // A result that arrived outside the armed interruption window must never stop the AI.
      rearmWhileSpeaking(260);
      return;
    }

    continueWithUserTurn(value);
  };

  window.__voiceInputError = message => {
    window.__voiceInputStopped?.();
    resetCandidate();
    if (window.__voiceCallActive && state.speaking) {
      // Recognition timeouts/no-match while the AI is talking are normal and must not affect TTS.
      rearmWhileSpeaking(320);
      return;
    }
    if (window.__voiceCallActive) {
      setStatus('Listening…', String(message || '') + ' Trying again…');
      setOrb(null);
      setTimeout(() => {
        if (!window.__voiceCallActive || window.__voiceCallMuted || state.speaking) return;
        native.setVoiceLanguage?.(document.getElementById('callVoiceLang')?.value || 'en-US');
        native.startVoiceInput?.();
      }, 260);
    } else {
      const error = document.getElementById('chatError');
      if (error) error.textContent = message;
    }
  };

  return 'ready';
})();
