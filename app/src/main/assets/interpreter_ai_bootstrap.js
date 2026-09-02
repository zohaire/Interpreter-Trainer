(() => {
  if (window.__interpreterAiBootstrapV5) return 'ready';
  window.__interpreterAiBootstrapV5 = true;
  window.__interpreterAiBootstrapV6 = true;
  window.__interpreterAiCoreVersion = 'AIV6-PRO';

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

  const sdkReady = () => Boolean(window.puter?.ai?.chat);
  const provider = () => window.InterpreterAiProvider;
  const providerError = error => provider()?.messageForError?.(error) ||
    error?.msg || error?.message || String(error || 'Interpreter AI could not connect.');

  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('Offline · reconnect to use AI', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!sdkReady()) {
      try { window.__loadInterpreterAiSdk?.()?.catch?.(() => {}); } catch (_) {}
      setConnectionStatus('Connecting to professional AI…');
      setError('Interpreter AI is still connecting. Please try again in a moment.');
      return false;
    }
    if (window.puter?.auth?.isSignedIn?.() !== true) {
      setConnectionStatus('AI ready · tap Connect AI');
      return false;
    }

    setConnectionStatus('Professional AI · connected', 'ok');
    setError('');
    return true;
  };

  // Call this directly from the original button tap. The provider invokes Puter signIn() before
  // its first await, which is required for Android WebView to permit the secure popup.
  const connectFromTap = () => {
    const runtime = provider();
    if (!runtime) {
      const error = new Error('The AI connection layer is still loading.');
      error.code = 'SDK_NOT_READY';
      return Promise.reject(error);
    }
    return runtime.connectFromUserGesture();
  };
  window.connectAi = connectFromTap;
  window.ensureConnected = connectFromTap;
  window.__connectInterpreterAi = connectFromTap;

  const installConnectionUi = () => {
    const header = document.querySelector('.topline');
    if (!header || byId('connectAiBtn')) return;
    const style = document.createElement('style');
    style.id = 'interpreter-ai-connection-style';
    style.textContent = `
      .connect-ai-btn{border:1px solid color-mix(in srgb,var(--accent) 40%,var(--border));border-radius:999px;padding:7px 11px;background:var(--accent-soft);color:var(--accent-ink);font-size:11px;font-weight:780;white-space:nowrap}
      .connect-ai-btn[hidden]{display:none}.connect-ai-btn:disabled{opacity:.58}
    `;
    document.head.appendChild(style);
    const button = document.createElement('button');
    button.id = 'connectAiBtn';
    button.type = 'button';
    button.className = 'connect-ai-btn';
    button.textContent = 'Connect AI';
    button.onclick = () => {
      setError('');
      const connection = connectFromTap();
      connection.catch(error => setError(providerError(error)));
    };
    header.insertBefore(button, header.lastElementChild);
  };

  const renderProviderState = value => {
    installConnectionUi();
    const button = byId('connectAiBtn');
    if (button) {
      button.hidden = value.state === 'ready';
      button.disabled = value.state === 'connecting' || value.state === 'offline';
      button.textContent = value.state === 'loading' || value.state === 'error' ? 'Try again' :
        value.state === 'connecting' ? 'Connecting…' : 'Connect AI';
    }
    if (value.state === 'ready') setConnectionStatus('Professional AI · connected', 'ok');
    else if (value.state === 'offline') setConnectionStatus('Offline · reconnect to use AI', 'bad');
    else if (value.state === 'error') setConnectionStatus('AI connection needs attention', 'bad');
    else if (value.state === 'connecting') setConnectionStatus('Complete secure AI sign-in…');
    else if (value.state === 'needs_auth') setConnectionStatus('AI ready · tap Connect AI');
    else setConnectionStatus('Loading professional AI…');
    window.updateSendState?.();
  };

  installConnectionUi();
  provider()?.subscribe?.(renderProviderState);

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

      // Start authentication immediately inside this tap; do not put an await before this call.
      const connection = connectFromTap();
      try {
        await connection;
      } catch (error) {
        const message = providerError(error);
        callStatus('Connection failed', message);
        setError(message);
        window.__voiceCallActive = false;
        window.__voiceAutoSpeak = false;
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
    // Begin sign-in synchronously inside the Send tap. Keep the text in the composer until the
    // connection succeeds so a blocked/cancelled popup never discards the user's message.
    const connection = connectFromTap();
    try {
      await connection;
    } catch (error) {
      setError(providerError(error));
      return;
    }

    busy = true;
    addMessage('user', text);
    input.value = '';
    resizeComposer();
    updateSendState();
    showTyping();

    try {
      const system = window.__buildInterpreterCoachPrompt?.({ voice: fromVoice }) ||
        `You are Interpreter AI, a modern professional coach for interpreters. Respond directly in the user's language and never invent evidence.\n\n${nativePracticeContext()}`;
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];

      const request = provider().request(conversation, {
        model:window.__INTERPRETER_AI_MODEL || 'qwen/qwen3.8-27b:free',
        max_tokens: fromVoice ? 220 : 760,
        temperature:0.18,
        normalize:true
      });
      const response = await request;
      const answer = responseText(response);
      if (!answer) throw new Error('The AI returned an empty response.');

      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-20);
      saveHistory();
      hideTyping();
      addMessage('assistant', answer);
      setConnectionStatus('Professional AI · connected', 'ok');
    } catch (error) {
      hideTyping();
      const message = providerError(error);
      setError('Interpreter AI could not answer: ' + message);
      setConnectionStatus('Request failed', 'bad');
      if (input && !input.value.trim()) {
        input.value = text;
        resizeComposer();
      }
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
    const connection = connectFromTap();
    try {
      await connection;
    } catch (error) {
      if (result) result.innerHTML = '<div class="result-card error-box">' + escapeHtml(providerError(error)) + '</div>';
      return;
    }

    busy = true;
    const evaluateButton = byId('evaluateBtn');
    if (evaluateButton) evaluateButton.disabled = true;
    if (result) result.innerHTML = '<div class="result-card"><div class="typing"><span></span><span></span><span></span></div></div>';

    try {
      const evaluationData = {
        mode:byId('mode')?.value || '',
        languages:byId('languages')?.value || '',
        sourceSeconds:byId('sourceSeconds')?.value || '',
        traineeSeconds:byId('traineeSeconds')?.value || '',
        source,
        trainee
      };
      const prompt = window.__buildInterpreterEvaluationRequest?.(evaluationData) ||
        `SOURCE:\n${source}\n\nTRAINEE OUTPUT:\n${trainee}\n\nEvaluate meaning transfer, completeness, precision, terminology and register. Do not invent evidence.`;
      const request = provider().request([
        { role:'system', content:window.__INTERPRETER_EVALUATION_SYSTEM || 'You are a rigorous professional interpreter-performance evaluator. Do not invent evidence.' },
        { role:'user', content:prompt }
      ], {
        model:window.__INTERPRETER_AI_MODEL || 'qwen/qwen3.8-27b:free',
        max_tokens:1400,
        temperature:0.10,
        normalize:true
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
      setConnectionStatus('Professional AI · connected', 'ok');
    } catch (error) {
      const message = providerError(error);
      if (result) result.innerHTML = '<div class="result-card error-box">Evaluation failed: ' + escapeHtml(message) + '</div>';
      setConnectionStatus('Request failed', 'bad');
    } finally {
      busy = false;
      if (evaluateButton) evaluateButton.disabled = false;
    }
  };

  window.__baseAiActivationSafeV5 = true;

  const refresh = () => {
    ensureVoiceUi();
    renderProviderState(provider()?.syncState?.() || {
      state:navigator.onLine === false ? 'offline' : sdkReady() ? 'needs_auth' : 'loading'
    });
    if (providerReady()) setError('');
  };

  let refreshAttempts = 0;
  const refreshTimer = setInterval(() => {
    refresh();
    refreshAttempts += 1;
    if ((sdkReady() && byId('voiceCallLaunch') && byId('voiceBtn')) || refreshAttempts >= 40) clearInterval(refreshTimer);
  }, 250);

  refresh();
  window.addEventListener('online', refresh);
  window.addEventListener('offline', () => setConnectionStatus('Offline · reconnect to use AI', 'bad'));

  return 'ready';
})();
