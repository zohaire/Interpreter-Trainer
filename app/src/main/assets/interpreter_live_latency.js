(() => {
  if (window.__interpreterLiveLatencyV3) return 'ready';
  if (!(window.__fastInterpreterVoiceV4 || window.__fastInterpreterVoiceV3) || !window.puter?.ai?.chat) return 'pending';

  const originalChat = window.puter.ai.chat.bind(window.puter.ai);
  const LIVE_MARKER = 'INTERPRETER LIVE LOW-LATENCY POLICY';

  const visibleUserTurns = () => {
    try {
      if (typeof document === 'undefined' || !document.querySelectorAll) return [];
      return Array.from(document.querySelectorAll('.message.user .bubble'))
        .map(node => String(node.innerText || node.textContent || '').trim())
        .filter(Boolean);
    } catch (_) {
      return [];
    }
  };

  const preserveInterruptedContext = request => {
    if (!Array.isArray(request) || request.length < 2) return;

    const visible = visibleUserTurns();
    if (visible.length < 2) return;

    const previousUserText = visible[visible.length - 2];
    if (!previousUserText) return;

    const alreadyPresent = request.some(item =>
      item && item.role === 'user' && String(item.content || '').trim() === previousUserText
    );
    if (alreadyPresent) return;

    let insertAt = request.length - 1;
    while (insertAt > 0 && request[insertAt]?.role !== 'user') insertAt -= 1;
    request.splice(Math.max(1, insertAt), 0, { role: 'user', content: previousUserText });
  };

  const wrappedChat = async (...args) => {
    if (window.__voiceCallActive) {
      const request = args[0];
      if (Array.isArray(request)) {
        preserveInterruptedContext(request);

        const system = request.find(item => item && item.role === 'system' && typeof item.content === 'string');
        if (system && !system.content.includes(LIVE_MARKER)) {
          system.content += `\n\n${LIVE_MARKER}: Respond immediately and conversationally. Put the direct answer in the first sentence. Unless the user explicitly asks for detail, use only 1–2 short spoken sentences. No preamble, headings, recap, or filler. If the user interrupts or adds information, treat that complete new utterance as the newest turn, retain the immediately preceding user request as context, and answer the new combined intent directly.`;
        }
      }

      const current = args[1] && typeof args[1] === 'object' ? args[1] : {};
      args[1] = {
        ...current,
        stream: true,
        max_tokens: Math.min(Number(current.max_tokens) || 160, 160),
        temperature: Math.min(Number(current.temperature) || 0.20, 0.20)
      };
    }
    return originalChat(...args);
  };

  wrappedChat.__interpreterLiveLatencyWrapped = true;
  try {
    window.puter.ai.chat = wrappedChat;
  } catch (_) {
    return 'pending';
  }

  if (window.puter.ai.chat !== wrappedChat && window.puter.ai.chat?.__interpreterLiveLatencyWrapped !== true) {
    return 'pending';
  }

  // ---------------------------------------------------------------------------
  // Device-independent speech input fallback.
  // Android SpeechRecognizer is fast when present, but it is not guaranteed to exist on every
  // phone. When the native recognizer reports an unavailable/reset/network error, capture one
  // utterance through InterpreterLiveNative and transcribe the resulting WAV with Puter STT.
  // ---------------------------------------------------------------------------
  const liveNative = window.InterpreterLiveNative || null;
  const baseVoiceError = window.__voiceInputError;
  let cloudCaptureActive = false;
  let cloudTranscribing = false;

  const voiceLanguage = () =>
    document.getElementById('callVoiceLang')?.value ||
    document.getElementById('voiceLang')?.value ||
    'en-US';

  const languageHint = tag => {
    const value = String(tag || '').toLowerCase();
    if (value.startsWith('fr')) return 'fr';
    if (value.startsWith('ar')) return 'ar';
    return 'en';
  };

  const setLiveStatus = (status, detail) => {
    const statusNode = document.getElementById('voiceCallStatus');
    const detailNode = document.getElementById('voiceCallLive');
    if (statusNode) statusNode.textContent = status;
    if (detailNode && detail !== undefined) detailNode.textContent = detail;
  };

  const startCloudCapture = () => {
    if (!liveNative?.startCloudVoiceInput || cloudCaptureActive || cloudTranscribing) return false;
    try {
      cloudCaptureActive = liveNative.startCloudVoiceInput(voiceLanguage()) === true;
      if (cloudCaptureActive && window.__voiceCallActive) {
        setLiveStatus('Listening…', 'Speak naturally');
      }
      return cloudCaptureActive;
    } catch (_) {
      return false;
    }
  };

  window.__cloudVoiceAudioReady = async (dataUrl, languageTag) => {
    cloudCaptureActive = false;
    if (!dataUrl) return;
    if (!window.puter?.ai?.speech2txt) {
      baseVoiceError?.('Online voice transcription is unavailable.');
      return;
    }

    cloudTranscribing = true;
    try {
      if (window.__voiceCallActive) setLiveStatus('Transcribing…', 'Finishing your turn');
      else if (document.getElementById('statusText')) document.getElementById('statusText').textContent = 'Transcribing voice…';

      const result = await window.puter.ai.speech2txt(dataUrl, {
        provider: 'openai',
        model: 'gpt-4o-mini-transcribe',
        response_format: 'text',
        language: languageHint(languageTag),
        temperature: 0
      });
      const text = String(typeof result === 'string' ? result : (result?.text || '')).trim();
      if (!text) throw new Error('No speech was recognized.');
      window.__voiceInputResult?.(text);
    } catch (error) {
      const message = error?.message || String(error);
      if (window.__voiceCallActive) {
        setLiveStatus('Voice retry', message);
        setTimeout(() => startCloudCapture(), 260);
      } else {
        baseVoiceError?.('Voice transcription failed: ' + message);
      }
    } finally {
      cloudTranscribing = false;
    }
  };

  window.__cloudVoiceCaptureError = message => {
    cloudCaptureActive = false;
    const text = String(message || 'Voice capture failed.');
    if (window.__voiceCallActive && /no speech/i.test(text)) {
      setTimeout(() => startCloudCapture(), 220);
      return;
    }
    baseVoiceError?.(text);
  };

  window.__voiceInputError = message => {
    const text = String(message || '');
    const needsCloud = /not available|resetting|network error|recognition error|could not start/i.test(text);
    if (needsCloud && startCloudCapture()) return;
    baseVoiceError?.(message);
  };

  const originalEndVoiceCall = window.endVoiceCall;
  window.endVoiceCall = () => {
    cloudCaptureActive = false;
    cloudTranscribing = false;
    try { liveNative?.stopCloudVoiceInput?.(); } catch (_) {}
    try { window.__stopOnlineVoice?.(); } catch (_) {}
    return originalEndVoiceCall?.();
  };
  const endButton = document.getElementById('voiceEnd');
  if (endButton) endButton.onclick = window.endVoiceCall;

  // ---------------------------------------------------------------------------
  // Online TTS fallback for phones with no usable Android TTS engine. Use Puter's documented
  // OpenAI TTS engine/voice IDs so this path does not depend on provider-specific voice aliases.
  // ---------------------------------------------------------------------------
  let onlineVoiceAudio = null;
  const onlineProfiles = {
    'en-US': { provider:'openai', model:'gpt-4o-mini-tts', voice:'alloy', response_format:'mp3', instructions:'Natural, concise interpreter-coach voice. Speak the supplied text in its original language.' },
    'fr-FR': { provider:'openai', model:'gpt-4o-mini-tts', voice:'coral', response_format:'mp3', instructions:'Natural French-speaking interpreter-coach voice. Keep pronunciation clear and conversational.' },
    'ar-MA': { provider:'openai', model:'gpt-4o-mini-tts', voice:'alloy', response_format:'mp3', instructions:'Natural Modern Standard Arabic interpreter-coach voice. Keep pronunciation clear and conversational.' }
  };

  window.__stopOnlineVoice = () => {
    if (!onlineVoiceAudio) return;
    try {
      onlineVoiceAudio.pause();
      onlineVoiceAudio.currentTime = 0;
    } catch (_) {}
    onlineVoiceAudio = null;
  };

  window.__onlineVoiceSpeak = async (text, languageTag) => {
    const clean = String(text || '').trim().slice(0, 2850);
    if (!clean) {
      window.__nativeSpeechFinished?.();
      return false;
    }
    try {
      window.__stopOnlineVoice();
      if (!window.puter?.ai?.txt2speech) throw new Error('Online voice is unavailable.');
      const profile = onlineProfiles[languageTag] || onlineProfiles['en-US'];
      const audio = await window.puter.ai.txt2speech(clean, profile);
      onlineVoiceAudio = audio;
      audio.onplay = () => window.__nativeSpeechStarted?.();
      audio.onended = () => {
        onlineVoiceAudio = null;
        window.__nativeSpeechFinished?.();
      };
      audio.onerror = () => {
        onlineVoiceAudio = null;
        window.__nativeSpeechFinished?.();
      };
      await audio.play();
      return true;
    } catch (_) {
      onlineVoiceAudio = null;
      window.__nativeSpeechFinished?.();
      return false;
    }
  };

  // ---------------------------------------------------------------------------
  // Smooth text composer + streaming output.
  // The old implementation forced layout on every keystroke and repeatedly started smooth-scroll
  // animations for every streamed token. Both are expensive in Android WebView.
  // ---------------------------------------------------------------------------
  if (!window.__interpreterSmoothComposerV1) {
    window.__interpreterSmoothComposerV1 = true;
    let resizeFrame = 0;
    window.resizeComposer = () => {
      if (resizeFrame) return;
      resizeFrame = requestAnimationFrame(() => {
        resizeFrame = 0;
        const input = document.getElementById('chatInput');
        if (!input) return;
        input.style.height = 'auto';
        const target = Math.min(Math.max(input.scrollHeight, 38), 150);
        input.style.height = target + 'px';
      });
    };

    let scrollFrame = 0;
    let requestedSmooth = false;
    window.scrollToBottom = smoothArg => {
      // requestAnimationFrame(callback) supplies a timestamp; only literal true means smooth.
      requestedSmooth = requestedSmooth || smoothArg === true;
      if (scrollFrame) return;
      scrollFrame = requestAnimationFrame(() => {
        scrollFrame = 0;
        const scroll = document.getElementById('chatScroll');
        if (!scroll) return;
        const smooth = requestedSmooth && !window.__voiceCallActive;
        requestedSmooth = false;
        if (smooth) scroll.scrollTo({ top: scroll.scrollHeight, behavior:'smooth' });
        else scroll.scrollTop = scroll.scrollHeight;
      });
    };
  }

  window.__interpreterLiveLatencyV2 = true;
  window.__interpreterLiveLatencyV3 = true;
  return 'ready';
})();