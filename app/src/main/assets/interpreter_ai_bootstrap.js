(() => {
  if (window.__interpreterAiBootstrapV5) return 'ready';
  window.__interpreterAiBootstrapV5 = true;
  window.__interpreterAiCoreVersion = 'AIV5-LIVE';

  const byId = id => document.getElementById(id);

  const setConnectionStatus = (text, state = 'idle') => {
    const statusText = byId('statusText');
    const statusDot = byId('statusDot');
    if (statusText) statusText.textContent = text;
    if (statusDot) {
      statusDot.className = 'status-dot' + (state === 'ok' ? ' ok' : state === 'bad' ? ' bad' : '');
    }
  };

  const setError = message => {
    const node = byId('chatError');
    if (node) node.textContent = message || '';
  };

  const freeProviderReady = () => {
    try { return String(window.InterpreterNative?.getFreeAiApiKey?.() || '').trim().length >= 20; }
    catch (_) { return false; }
  };

  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV5-LIVE', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!freeProviderReady()) {
      setConnectionStatus('Free AI setup needed · AIV5-LIVE', 'bad');
      setError('Add a free Google AI Studio key once to use Interpreter AI without Puter payments.');
      try { window.openFreeAiSetup?.(); } catch (_) {}
      return false;
    }

    setConnectionStatus('Free AI · ready · AIV5-LIVE', 'ok');
    setError('');
    return true;
  };

  window.connectAi = providerReady;
  window.ensureConnected = providerReady;
  window.__connectInterpreterAi = providerReady;

  // The native Android page normally installs these controls from AiCoachScreen.onPageFinished().
  // Some WebView reload races can skip that callback. Rebuild the complete visible voice shell here
  // so Interpreter Live and one-shot voice never disappear even when that race occurs.
  const ensureVoiceUi = () => {
    const composer = document.querySelector('.composer');
    const composerShell = document.querySelector('.composer-shell');
    const sendButton = byId('sendBtn');
    if (!composer || !composerShell || !sendButton) return false;

    const native = window.InterpreterNative;
    const stopVoice = () => {
      try { window.stopNaturalInterpreterVoice?.(); } catch (_) {}
      try { native?.stopSpeaking?.(); } catch (_) {}
    };

    if (!byId('interpreter-live-bootstrap-style')) {
      const style = document.createElement('style');
      style.id = 'interpreter-live-bootstrap-style';
      style.textContent = `
        .voice-language{height:38px;border:1px solid var(--border);border-radius:12px;background:var(--surface-soft);color:var(--text);padding:0 7px;font-size:11px;flex:0 0 auto}
        .voice-mic-btn{color:var(--accent)!important}.voice-mic-btn.listening{background:color-mix(in srgb,var(--danger) 12%,transparent)!important;color:var(--danger)!important}
        .voice-call-strip{width:min(760px,100%);margin:0 auto 7px;display:flex;justify-content:flex-end;align-items:center}
        .voice-call-launch{display:flex;align-items:center;gap:7px;border:1px solid color-mix(in srgb,var(--accent) 36%,var(--border));border-radius:999px;padding:8px 12px;background:var(--accent-soft);color:var(--accent-ink);font-size:12px;font-weight:750;cursor:pointer}
        .voice-call-launch:active{transform:scale(.98)}
        .voice-call-overlay{position:fixed;inset:0;z-index:9999;display:none;flex-direction:column;align-items:center;background:radial-gradient(circle at 50% 32%,color-mix(in srgb,var(--accent) 20%,var(--bg)) 0%,var(--bg) 48%);color:var(--text);padding:max(22px,env(safe-area-inset-top)) 22px max(26px,env(safe-area-inset-bottom))}
        .voice-call-overlay.active{display:flex}.voice-call-top{width:100%;display:flex;align-items:center;justify-content:space-between}.voice-call-title{font-size:15px;font-weight:780}.voice-call-badge{font-size:11px;color:var(--muted)}
        .voice-orb-wrap{flex:1;width:100%;display:flex;flex-direction:column;justify-content:center;align-items:center;min-height:0}.voice-orb{width:154px;height:154px;border-radius:50%;display:grid;place-items:center;color:white;background:linear-gradient(145deg,var(--accent),color-mix(in srgb,var(--accent) 52%,#8d6cff));box-shadow:0 24px 70px color-mix(in srgb,var(--accent) 30%,transparent);transition:transform .22s ease,box-shadow .22s ease}.voice-orb.listening{transform:scale(1.07);box-shadow:0 0 0 12px color-mix(in srgb,var(--accent) 8%,transparent),0 26px 80px color-mix(in srgb,var(--accent) 34%,transparent);animation:voicePulse 1.25s infinite ease-in-out}.voice-orb.speaking{transform:scale(1.04);animation:voiceSpeak 1.05s infinite ease-in-out}
        .voice-call-status{margin-top:30px;font-size:18px;font-weight:760;text-align:center}.voice-call-live{margin-top:10px;width:min(560px,92vw);min-height:52px;color:var(--muted);text-align:center;font-size:14px;line-height:1.5}.voice-call-controls{display:flex;align-items:center;gap:18px}.voice-round-control{width:58px;height:58px;border-radius:50%;border:1px solid var(--border);background:var(--surface-soft);color:var(--text);display:grid;place-items:center;font-size:21px;cursor:pointer}.voice-round-control.end{width:68px;height:68px;border:0;background:#d93025;color:white}.voice-round-control.muted{background:var(--surface-strong);color:var(--muted)}
        @keyframes voicePulse{0%,100%{transform:scale(1.04)}50%{transform:scale(1.10)}}@keyframes voiceSpeak{0%,100%{transform:scale(1.02)}50%{transform:scale(1.07)}}
      `;
      document.head.appendChild(style);
    }

    let language = byId('voiceLang');
    if (!language) {
      language = document.createElement('select');
      language.id = 'voiceLang';
      language.className = 'voice-language';
      language.setAttribute('aria-label', 'Voice language');
      language.innerHTML = '<option value="en-US">EN</option><option value="fr-FR">FR</option><option value="ar-MA">AR</option>';
      language.onchange = () => native?.setVoiceLanguage?.(language.value);
      composer.insertBefore(language, sendButton);
    }

    if (!byId('voiceBtn')) {
      const mic = document.createElement('button');
      mic.id = 'voiceBtn';
      mic.type = 'button';
      mic.className = 'icon-btn voice-mic-btn';
      mic.setAttribute('aria-label', 'Ask Interpreter AI by voice');
      mic.title = 'Ask by voice';
      mic.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0"/><path d="M12 17v5"/></svg>';
      mic.onclick = () => {
        window.__voiceCallActive = false;
        window.__voiceOneShot = true;
        native?.setVoiceLanguage?.(byId('voiceLang')?.value || 'en-US');
        stopVoice();
        native?.startVoiceInput?.();
      };
      composer.insertBefore(mic, sendButton);
    }

    if (!byId('voiceCallLaunch')) {
      const strip = document.createElement('div');
      strip.className = 'voice-call-strip';
      const call = document.createElement('button');
      call.id = 'voiceCallLaunch';
      call.type = 'button';
      call.className = 'voice-call-launch';
      call.innerHTML = '<span>◉</span><span>Interpreter Live</span>';
      call.onclick = () => window.startVoiceCall?.();
      strip.appendChild(call);
      composerShell.insertBefore(strip, composerShell.firstChild);
    }

    let overlay = byId('voiceCallOverlay');
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.id = 'voiceCallOverlay';
      overlay.className = 'voice-call-overlay';
      overlay.innerHTML = `
        <div class="voice-call-top">
          <div><div class="voice-call-title">Interpreter Live</div><div class="voice-call-badge">Natural multilingual conversation</div></div>
          <select id="callVoiceLang" class="voice-language" aria-label="Call language">
            <option value="en-US">English</option><option value="fr-FR">Français</option><option value="ar-MA">العربية الفصحى</option>
          </select>
        </div>
        <div class="voice-orb-wrap">
          <div id="voiceOrb" class="voice-orb"><span style="font-size:36px">≋</span></div>
          <div id="voiceCallStatus" class="voice-call-status">Ready</div>
          <div id="voiceCallLive" class="voice-call-live">Start speaking naturally. Interpreter AI will answer aloud and keep the conversation going.</div>
        </div>
        <div class="voice-call-controls">
          <button id="voiceMute" class="voice-round-control" type="button" aria-label="Mute microphone">🎙</button>
          <button id="voiceEnd" class="voice-round-control end" type="button" aria-label="End voice call">✕</button>
        </div>
      `;
      document.body.appendChild(overlay);
    }

    window.__voiceCallActive ??= false;
    window.__voiceCallMuted ??= false;
    window.__voiceOneShot ??= false;
    window.__voiceAutoSpeak ??= false;

    const callStatus = (status, liveText) => {
      if (byId('voiceCallStatus')) byId('voiceCallStatus').textContent = status;
      if (liveText !== undefined && byId('voiceCallLive')) byId('voiceCallLive').textContent = liveText;
    };
    const setOrbState = state => {
      const orb = byId('voiceOrb');
      if (!orb) return;
      orb.classList.remove('listening', 'speaking');
      if (state) orb.classList.add(state);
    };

    window.startVoiceCall = async () => {
      if (window.__voiceCallActive) return;
      overlay.classList.add('active');
      window.__voiceCallActive = true;
      window.__voiceCallMuted = false;
      window.__voiceOneShot = false;
      window.__voiceAutoSpeak = true;
      callStatus('Connecting…', 'Preparing Interpreter AI and microphone');
      setOrbState(null);

      if (providerReady() === false) {
        callStatus('Connection failed', 'Check your internet connection and try again.');
        return;
      }

      if (typeof window.ensureConnected === 'function' && !(await window.ensureConnected())) {
        callStatus('Connection failed', 'Complete Free AI setup and try again.');
        window.__voiceCallActive = false;
        return;
      }

      native?.setVoiceLanguage?.(byId('callVoiceLang')?.value || 'en-US');
      callStatus('Listening…', 'Speak now');
      setOrbState('listening');
      native?.startVoiceInput?.();
    };

    window.endVoiceCall = () => {
      window.__voiceCallActive = false;
      window.__voiceCallMuted = false;
      window.__voiceOneShot = false;
      window.__voiceAutoSpeak = false;
      try { native?.stopVoiceInput?.(); } catch (_) {}
      stopVoice();
      setOrbState(null);
      overlay.classList.remove('active');
      callStatus('Ready', 'Start speaking naturally. Interpreter AI will answer aloud and keep the conversation going.');
    };

    const endButton = byId('voiceEnd');
    if (endButton) endButton.onclick = window.endVoiceCall;
    const callLanguage = byId('callVoiceLang');
    if (callLanguage) callLanguage.onchange = event => {
      const value = event.target.value;
      if (byId('voiceLang')) byId('voiceLang').value = value;
      native?.setVoiceLanguage?.(value);
    };
    const muteButton = byId('voiceMute');
    if (muteButton) muteButton.onclick = event => {
      window.__voiceCallMuted = !window.__voiceCallMuted;
      event.currentTarget.classList.toggle('muted', window.__voiceCallMuted);
      if (window.__voiceCallMuted) {
        native?.stopVoiceInput?.();
        callStatus('Muted', 'Tap the microphone button to continue.');
        setOrbState(null);
      } else {
        native?.setVoiceLanguage?.(byId('callVoiceLang')?.value || 'en-US');
        callStatus('Listening…', 'Speak now');
        setOrbState('listening');
        native?.startVoiceInput?.();
      }
    };

    // Manual tap-to-interrupt fallback. The precise barge-in layer replaces the core
    // interruption function when it is ready, while this keeps the central orb usable immediately.
    if (!window.__manualLiveInterruptUiV1) {
      window.__manualLiveInterruptUiV1 = true;
      const orbButton = byId('voiceOrb');
      if (orbButton) {
        orbButton.setAttribute('role', 'button');
        orbButton.setAttribute('tabindex', '0');
        orbButton.setAttribute('aria-label', 'Interrupt Interpreter Live and speak');
        orbButton.title = 'Tap to interrupt Interpreter Live';
        orbButton.style.cursor = 'pointer';

        const interruptFromOrb = () => {
          if (!window.__voiceCallActive || window.__voiceCallMuted) return false;
          if (typeof window.__interpreterManualBargeIn === 'function') {
            return window.__interpreterManualBargeIn() === true;
          }
          stopVoice();
          try { native?.stopVoiceInput?.(); } catch (_) {}
          native?.setVoiceLanguage?.(byId('callVoiceLang')?.value || 'en-US');
          callStatus('Listening…', 'Go ahead — I am listening.');
          setOrbState('listening');
          setTimeout(() => {
            if (window.__voiceCallActive && !window.__voiceCallMuted) native?.startVoiceInput?.();
          }, 30);
          return true;
        };

        window.interruptInterpreterLive = interruptFromOrb;
        orbButton.onclick = interruptFromOrb;
        orbButton.onkeydown = event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            interruptFromOrb();
          }
        };
      }
    }

    window.__voiceInputStarted = () => {
      byId('voiceBtn')?.classList.add('listening');
      setError('');
      if (window.__voiceCallActive) {
        callStatus('Listening…', 'Speak now');
        setOrbState('listening');
      }
    };
    window.__voiceInputStopped = () => byId('voiceBtn')?.classList.remove('listening');
    window.__voiceInputPartial = text => {
      if (window.__voiceCallActive) callStatus('Listening…', String(text || ''));
      else if (byId('chatInput')) {
        byId('chatInput').value = String(text || '');
        window.resizeComposer?.();
        window.updateSendState?.();
      }
    };
    window.__voiceInputResult = text => {
      window.__voiceInputStopped?.();
      const value = String(text || '').trim();
      if (!value || !byId('chatInput')) return;
      byId('chatInput').value = value;
      window.resizeComposer?.();
      window.updateSendState?.();
      window.__voiceAutoSpeak = true;
      window.__voiceOneShot = !window.__voiceCallActive;
      if (window.__voiceCallActive) {
        callStatus('Thinking…', value);
        setOrbState(null);
      }
      window.sendChat?.(true);
    };
    window.__voiceInputError = message => {
      window.__voiceInputStopped?.();
      if (window.__voiceCallActive) {
        callStatus('Listening…', String(message || 'Voice recognition error.'));
        setOrbState(null);
      } else {
        setError(message);
      }
    };
    window.__nativeSpeechStarted = () => {
      if (!window.__voiceCallActive) return;
      callStatus('Interpreter AI is speaking', 'You can interrupt at any time.');
      setOrbState('speaking');
    };
    window.__nativeSpeechFinished = () => {
      if (!window.__voiceCallActive || window.__voiceCallMuted) return;
      native?.setVoiceLanguage?.(byId('callVoiceLang')?.value || 'en-US');
      callStatus('Your turn', 'Listening…');
      setOrbState('listening');
      native?.startVoiceInput?.();
    };

    // Fast voice and full-duplex patches intentionally key off this long-standing marker.
    window.__interpreterEnhancementsV3 = true;
    window.__voiceUiRestoredByBootstrapV5 = true;
    return true;
  };

  ensureVoiceUi();

  // Activation-safe fallback chat. The faster voice layer replaces this later when available, but
  // keeping the base path safe means chat still works even if an optional voice patch fails.
  window.sendChat = async function(fromVoice = false) {
    if (typeof busy !== 'undefined' && busy) return;
    const input = byId('chatInput');
    const text = input?.value?.trim() || '';
    if (!text) return;

    setError('');
    addMessage('user', text);
    input.value = '';
    resizeComposer();
    updateSendState();

    if (providerReady() === false) return;

    busy = true;
    updateSendState();
    showTyping();

    try {
      const system = `You are Interpreter AI, a fast professional coach for interpreters working especially in Arabic, English and French. Respond naturally and directly.\n\n${nativePracticeContext()}`;
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];

      const request = window.__interpreterAiRequest(conversation, {
        model:'gemini-3.7-flash',
        max_tokens: fromVoice ? 320 : 650,
        temperature:0.24
      });
      const response = await request;
      const answer = responseText(response);
      if (!answer) throw new Error('The AI returned an empty response.');

      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-20);
      saveHistory();
      hideTyping();
      addMessage('assistant', answer);
      setConnectionStatus('Free AI · ready · AIV5-LIVE', 'ok');
    } catch (error) {
      hideTyping();
      const message = error?.msg || error?.message || String(error);
      setError('Interpreter AI could not answer: ' + message);
      setConnectionStatus('Request failed · AIV5-LIVE', 'bad');
    } finally {
      busy = false;
      updateSendState();
    }
  };

  window.evaluatePerformance = async function() {
    if (typeof busy !== 'undefined' && busy) return;
    const source = byId('sourceText')?.value?.trim() || '';
    const trainee = byId('traineeText')?.value?.trim() || '';
    const result = byId('evaluationResult');
    if (!source || !trainee) {
      if (result) result.innerHTML = '<div class="result-card error-box">Add both the source and your interpretation first.</div>';
      return;
    }
    if (providerReady() === false) return;

    busy = true;
    const evaluateButton = byId('evaluateBtn');
    if (evaluateButton) evaluateButton.disabled = true;
    if (result) result.innerHTML = '<div class="result-card"><div class="typing"><span></span><span></span><span></span></div></div>';

    try {
      const prompt = `Mode: ${byId('mode')?.value || 'not specified'}\nDirection: ${byId('languages')?.value || 'not specified'}\nSource duration: ${byId('sourceSeconds')?.value || 'not provided'}\nTrainee duration: ${byId('traineeSeconds')?.value || 'not provided'}\n\nSOURCE:\n${source}\n\nTRAINEE:\n${trainee}\n\nEvaluate meaning transfer, omissions, additions, numbers, names, terminology, register, fluency and give three concrete drills.`;
      const request = window.__interpreterAiRequest([
        { role:'system', content:'You are a rigorous professional interpreter-performance evaluator. Do not invent evidence.' },
        { role:'user', content:prompt }
      ], {
        model:'gemini-3.7-flash',
        max_tokens:1400,
        temperature:0.15
      });
      const response = await request;
      const answer = responseText(response);
      if (!answer) throw new Error('The AI returned an empty evaluation.');

      if (result) {
        result.innerHTML = '';
        const card = document.createElement('div');
        card.className = 'result-card';
        const head = document.createElement('div');
        head.className = 'result-head';
        head.innerHTML = '<div class="mini-mark">IT</div><span>Performance feedback</span>';
        const body = document.createElement('div');
        body.className = 'bubble';
        renderRichText(body, answer);
        card.append(head, body);
        result.appendChild(card);
      }
      setConnectionStatus('Free AI · ready · AIV5-LIVE', 'ok');
    } catch (error) {
      const message = error?.msg || error?.message || String(error);
      if (result) result.innerHTML = '<div class="result-card error-box">Evaluation failed: ' + escapeHtml(message) + '</div>';
      setConnectionStatus('Request failed · AIV5-LIVE', 'bad');
    } finally {
      busy = false;
      if (evaluateButton) evaluateButton.disabled = false;
    }
  };

  window.__baseAiActivationSafeV5 = true;

  const refresh = () => {
    ensureVoiceUi();
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV5-LIVE', 'bad');
      return;
    }
    if (!sdkReady()) {
      setConnectionStatus('Loading AI service · AIV5-LIVE');
      return;
    }
    setConnectionStatus('Free AI · ready · AIV5-LIVE', 'ok');
    setError('');
  };

  let refreshAttempts = 0;
  const refreshTimer = setInterval(() => {
    refresh();
    refreshAttempts += 1;
    if ((sdkReady() && byId('voiceCallLaunch') && byId('voiceBtn')) || refreshAttempts >= 40) clearInterval(refreshTimer);
  }, 250);

  refresh();
  window.addEventListener('online', refresh);
  window.addEventListener('offline', () => setConnectionStatus('No internet connection · AIV5-LIVE', 'bad'));

  return 'ready';
})();