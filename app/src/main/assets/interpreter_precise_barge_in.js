(() => {
  if (window.__interpreterPreciseBargeInV2) return 'ready';
  const native = window.InterpreterNative;
  const state = window.__fastInterpreterVoiceState;
  if (!native || !state || !window.__fastInterpreterVoiceV3) return 'pending';
  window.__interpreterPreciseBargeInV2 = true;

  const candidate = {
    text: '',
    hits: 0,
    startedAt: 0,
    lastAt: 0
  };

  let speechArmGeneration = 0;

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

  const novelWordCount = value => {
    const heard = words(value);
    const spoken = new Set(words((state.speechReference || '') + ' ' + (state.streamAnswer || '')));
    return heard.filter(word => !spoken.has(word)).length;
  };

  const likelySpeakerEcho = value => {
    const heard = clean(value);
    const spoken = clean((state.speechReference || '') + ' ' + (state.streamAnswer || ''));
    if (!heard || !spoken) return false;

    if (heard.length < 5) return true;
    if (spoken.includes(heard)) return true;

    const heardWords = words(heard);
    if (!heardWords.length) return true;

    const coverage = tokenOverlap(heard, spoken);
    const pairCoverage = bigramOverlap(heard, spoken);
    const novel = novelWordCount(heard);

    // Phone-speaker leakage is often re-transcribed imperfectly rather than copied verbatim.
    // Treat even moderate overlap as echo when the recognizer produced only a short phrase.
    if (heardWords.length <= 2) return coverage >= 0.34 || novel < 2;
    if (heardWords.length <= 4 && coverage >= 0.50) return true;
    if (pairCoverage >= 0.34) return true;
    return coverage >= 0.58;
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
    const novel = novelWordCount(text);

    if (!shortCommand && (count < 2 || text.length < 6)) {
      resetCandidate();
      return false;
    }

    // A real interruption should introduce something that is not simply the AI's own sentence.
    if (!shortCommand && ((count <= 3 && novel < 2) || (count >= 4 && novel < 1))) {
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
      return candidate.hits >= 3 && age >= 180;
    }

    // Crucial real-device guard: a single final recognition is never enough to stop the AI.
    // Phone-speaker echo can arrive as a polished final phrase even when partials looked harmless.
    if (isFinal) return candidate.hits >= 2 && age >= 100;

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

  const armAfterSpeechStart = (delay = 680) => {
    const generation = ++speechArmGeneration;
    resetCandidate();
    state.bargeArmed = false;
    setTimeout(() => {
      if (generation !== speechArmGeneration) return;
      if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
      state.bargeArmed = true;
      native.setVoiceLanguage?.(document.getElementById('callVoiceLang')?.value || 'en-US');
      native.startVoiceInput?.();
    }, delay);
  };

  // Replace the aggressive 320 ms arming installed by the low-latency layer. On a real phone,
  // the first few hundred milliseconds contain the strongest loudspeaker leakage. We still keep
  // automatic barge-in, but only after the TTS attack has settled. Manual mic interruption remains
  // immediate because the base voice layer handles that button independently.
  window.__nativeSpeechStarted = () => {
    if (!state.speaking) return;
    if (window.__voiceCallActive) {
      setStatus('Interpreter AI is speaking', state.streamAnswer || 'You can interrupt at any time.');
      setOrb('speaking');
      armAfterSpeechStart(680);
    }
  };

  const rearmWhileSpeaking = (delay = 520) => {
    const generation = ++speechArmGeneration;
    resetCandidate();
    state.bargeArmed = false;
    setTimeout(() => {
      if (generation !== speechArmGeneration) return;
      if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
      state.bargeArmed = true;
      native.setVoiceLanguage?.(document.getElementById('callVoiceLang')?.value || 'en-US');
      native.startVoiceInput?.();
    }, delay);
  };

  const interruptForRealSpeech = value => {
    speechArmGeneration += 1;
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
        rearmWhileSpeaking(520);
      }
      return;
    }

    if (window.__voiceCallActive && state.userBarging) {
      continueWithUserTurn(value);
      return;
    }

    if (window.__voiceCallActive && state.speaking) {
      rearmWhileSpeaking(520);
      return;
    }

    continueWithUserTurn(value);
  };

  window.__voiceInputError = message => {
    window.__voiceInputStopped?.();
    resetCandidate();
    if (window.__voiceCallActive && state.speaking) {
      rearmWhileSpeaking(620);
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
