(() => {
  if (window.__interpreterConversationalBargeInV3) return 'ready';
  const native = window.InterpreterNative;
  const state = window.__fastInterpreterVoiceState;
  if (!native || !state || !window.__fastInterpreterVoiceV3) return 'pending';
  window.__interpreterConversationalBargeInV3 = true;

  // Keep the fast layer's chunk-pumping callback. We wrap it instead of replacing the speech queue.
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
    armGeneration: 0
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

  const spokenReference = () => clean((state.speechReference || '') + ' ' + (state.streamAnswer || ''));

  const novelty = value => {
    const heard = words(value);
    if (!heard.length) return { count: 0, ratio: 0 };
    const spoken = new Set(words(spokenReference()));
    const novelCount = heard.filter(word => !spoken.has(word)).length;
    return { count: novelCount, ratio: novelCount / heard.length };
  };

  const likelySpeakerEcho = value => {
    const heard = clean(value);
    const spoken = spokenReference();
    if (!heard || !spoken) return false;
    if (heard.length < 5) return true;
    if (spoken.includes(heard)) return true;

    const heardWords = words(heard);
    const coverage = tokenOverlap(heard, spoken);
    const pairCoverage = bigramOverlap(heard, spoken);
    const novel = novelty(heard);

    // Real phone-speaker echo is often re-transcribed imperfectly, so use semantic/token overlap
    // rather than requiring an exact copy of the AI sentence.
    if (heardWords.length <= 2) return coverage >= 0.34 || novel.count < 2;
    if (heardWords.length <= 4 && coverage >= 0.52) return true;
    if (pairCoverage >= 0.40) return true;
    return coverage >= 0.62;
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

  const explicitShortCommand = value => new Set([
    'stop', 'wait', 'hold on', 'one second',
    'attends', 'attendez', 'stoppe', 'une seconde',
    'توقف', 'توقفي', 'انتظر', 'انتظري', 'لحظة', 'ثانية'
  ]).has(clean(value));

  const observeInterruption = (value, isFinal = false) => {
    const text = clean(value);
    if (!text || likelySpeakerEcho(text)) {
      resetCandidate();
      return false;
    }

    const now = Date.now();
    const count = words(text).length;
    const shortCommand = explicitShortCommand(text);
    const novel = novelty(text);

    if (!shortCommand && (count < 2 || text.length < 6)) {
      resetCandidate();
      return false;
    }
    if (!shortCommand && count <= 3 && novel.count < 2) {
      resetCandidate();
      return false;
    }

    if (turn.candidate && similarCandidate(turn.candidate, text) && now - turn.candidateLastAt <= 900) {
      turn.candidateHits += 1;
    } else {
      turn.candidate = text;
      turn.candidateHits = 1;
      turn.candidateStartedAt = now;
    }
    turn.candidateLastAt = now;
    turn.candidate = text;

    const age = now - turn.candidateStartedAt;
    if (shortCommand) return turn.candidateHits >= 2 && age >= 70;

    // Two consistent recognizer updates make interruption fast without reacting to one noise hit.
    if (turn.candidateHits >= 2 && age >= 65) return true;

    // Some Android recognizers emit almost no partials. A highly novel, substantial final result
    // is therefore allowed to interrupt immediately.
    if (isFinal && count >= 4 && text.length >= 14 && novel.ratio >= 0.68 && tokenOverlap(text, spokenReference()) < 0.22) {
      return true;
    }
    return false;
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
    // The streaming loop in interpreter_fast_voice.js checks responseId on every chunk.
    state.responseId += 1;
    try {
      if (typeof busy !== 'undefined' && busy) {
        busy = false;
        hideTyping?.();
        updateSendState?.();
      }
    } catch (_) {}
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
    cancelOldAiResponse();
    state.userBarging = true;
    state.bargeArmed = false;
    state.queue = [];
    state.streamComplete = true;
    state.speaking = false;
    turn.phase = 'barge-listening';
    turn.lastBargeText = heard;
    resetCandidate();

    // This is the crucial ChatGPT-like handoff: stop only the AI OUTPUT. Do not cancel speech
    // recognition. The microphone stays on until Android produces the user's final utterance.
    try { native.stopSpeaking?.(); } catch (_) {}
    try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}

    setStatus('Listening…', turn.lastBargeText || 'Go ahead — I am listening.');
    setOrb('listening');
  };

  const submitCompletedTurn = value => {
    const text = String(value || turn.lastBargeText || '').trim();
    if (!text || !writeInput(text)) return;

    turn.phase = 'thinking';
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

  const armInterruptionListening = (delay = 200) => {
    const generation = ++turn.armGeneration;
    resetCandidate();
    state.bargeArmed = false;
    setTimeout(() => {
      if (generation !== turn.armGeneration) return;
      if (!window.__voiceCallActive || window.__voiceCallMuted || !state.speaking || state.userBarging) return;
      turn.phase = 'speaking';
      state.bargeArmed = true;
      native.setVoiceLanguage?.(callLanguage());
      native.startVoiceInput?.();
    }, delay);
  };

  const rearmWhileSpeaking = (delay = 190) => {
    if (!state.speaking || state.userBarging) return;
    armInterruptionListening(delay);
  };

  // The fast speech layer used a slower/less explicit interruption policy. Replace only its
  // callbacks; keep its stream and speech queue intact.
  window.__nativeSpeechStarted = () => {
    if (!state.speaking) return;
    turn.phase = 'speaking';
    if (!window.__voiceCallActive) return;
    setStatus('Interpreter AI is speaking', state.streamAnswer || 'You can interrupt me while I speak.');
    setOrb('speaking');
    armInterruptionListening(200);
  };

  window.__nativeSpeechFinished = () => {
    // If the user has interrupted, a late TTS completion callback must never cancel their mic.
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

    // Once interruption begins, partials only update the live transcript. They NEVER trigger an
    // AI request. The request waits for the final utterance so the user can finish adding context.
    if (window.__voiceCallActive && turn.phase === 'barge-listening') {
      turn.lastBargeText = value;
      setStatus('Listening…', value);
      setOrb('listening');
      return;
    }

    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      if (observeInterruption(value, false)) beginBargeListening(value);
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

    if (window.__voiceCallActive && state.bargeArmed && state.speaking) {
      if (observeInterruption(value, true)) {
        // This recognizer has already delivered the complete utterance, so stop output and submit
        // it immediately. When partials trigger first, the path above waits for this final result.
        beginBargeListening(value);
        submitCompletedTurn(value);
      } else {
        rearmWhileSpeaking(190);
      }
      return;
    }

    if (window.__voiceCallActive && state.userBarging) {
      submitCompletedTurn(value);
      return;
    }

    if (window.__voiceCallActive && state.speaking) {
      // Monitoring result not proven to be the user: it is speaker echo/noise. Keep talking.
      rearmWhileSpeaking(190);
      return;
    }

    submitCompletedTurn(value);
  };

  window.__voiceInputError = message => {
    window.__voiceInputStopped?.();
    resetCandidate();

    if (window.__voiceCallActive && turn.phase === 'barge-listening') {
      const fallback = String(turn.lastBargeText || '').trim();
      // Recognition timeout normally means the user has stopped speaking. Preserve the stable
      // partial instead of losing the whole interruption.
      if (words(fallback).length >= 2) {
        submitCompletedTurn(fallback);
      } else {
        native.setVoiceLanguage?.(callLanguage());
        setTimeout(() => {
          if (window.__voiceCallActive && turn.phase === 'barge-listening') native.startVoiceInput?.();
        }, 90);
      }
      return;
    }

    if (window.__voiceCallActive && state.speaking) {
      rearmWhileSpeaking(220);
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
      }, 100);
    } else {
      const error = document.getElementById('chatError');
      if (error) error.textContent = message;
    }
  };

  // Deterministic interruption: tapping the live-call mic while AI speaks stops the output
  // immediately and starts/keeps recognition. It does not toggle mute until the AI is not speaking.
  const muteButton = document.getElementById('voiceMute');
  if (muteButton) {
    muteButton.onclick = event => {
      if (state.speaking || state.bargeArmed || turn.phase === 'speaking') {
        beginBargeListening('');
        native.setVoiceLanguage?.(callLanguage());
        native.startVoiceInput?.();
        return;
      }

      window.__voiceCallMuted = !window.__voiceCallMuted;
      event.currentTarget.classList.toggle('muted', window.__voiceCallMuted);
      event.currentTarget.textContent = window.__voiceCallMuted ? '🔇' : '🎙';
      if (window.__voiceCallMuted) {
        turn.phase = 'idle';
        native.stopVoiceInput?.();
        setStatus('Muted', 'Tap the microphone button to continue.');
        setOrb(null);
      } else {
        turn.phase = 'listening';
        native.setVoiceLanguage?.(callLanguage());
        setTimeout(() => native.startVoiceInput?.(), 45);
      }
    };
  }

  window.startVoiceCall = async () => {
    turn.phase = 'listening';
    turn.lastBargeText = '';
    turn.armGeneration += 1;
    resetCandidate();
    return baseStartVoiceCall?.();
  };

  window.endVoiceCall = () => {
    turn.phase = 'idle';
    turn.lastBargeText = '';
    turn.armGeneration += 1;
    resetCandidate();
    state.userBarging = false;
    state.bargeArmed = false;
    return baseEndVoiceCall?.();
  };
  const endButton = document.getElementById('voiceEnd');
  if (endButton) endButton.onclick = window.endVoiceCall;

  return 'ready';
})();
