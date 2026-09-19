(() => {
  if (window.__professionalInterpreterVoiceV2) return 'ready';
  if (!window.InterpreterNative) return 'pending';

  const STORAGE_KEY = 'interpreterProfessionalVoiceV2';
  const native = window.InterpreterNative;
  const live = window.InterpreterLiveNative || null;
  const profiles = {
    natural: {
      label: 'Natural',
      voice: 'nova',
      direction: 'Speak like a present-day human interpretation coach in a relaxed one-to-one conversation. Use fluid phrasing, subtle expression, crisp diction and a medium pace. Avoid robotic timing, theatrical emphasis and announcer delivery.'
    },
    warm: {
      label: 'Warm',
      voice: 'sage',
      direction: 'Sound warm, attentive and genuinely conversational while remaining professional. Use natural pauses, gentle expression and an unhurried pace. Never sound like an automated announcement.'
    },
    grounded: {
      label: 'Grounded',
      voice: 'onyx',
      direction: 'Use a grounded, confident and clear professional voice. Keep it contemporary and conversational, with controlled energy and no vintage radio cadence.'
    }
  };

  const languageDirection = languageTag => {
    const value = String(languageTag || '').toLowerCase();
    if (value.startsWith('fr')) {
      return 'Speak the supplied text in natural contemporary French with idiomatic rhythm and clear liaison.';
    }
    if (value.startsWith('ar')) {
      return 'Speak the supplied text in clear Modern Standard Arabic with natural phrasing. Avoid theatrical declamation and do not convert it to a regional dialect.';
    }
    return 'Speak the supplied text in natural contemporary English with international, easy-to-follow pronunciation.';
  };

  const readProfile = () => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY) || 'natural';
      return profiles[stored] ? stored : 'natural';
    } catch (_) {
      return 'natural';
    }
  };

  let selectedProfile = readProfile();
  let activeAudio = null;
  let requestGeneration = 0;

  const finish = () => {
    try { window.__nativeSpeechFinished?.(); } catch (_) {}
  };

  const stopAudio = () => {
    requestGeneration += 1;
    if (!activeAudio) return;
    try {
      activeAudio.pause();
      activeAudio.currentTime = 0;
    } catch (_) {}
    activeAudio = null;
  };

  const fallbackSpeak = (text, languageTag) => {
    try {
      if (live?.speakText?.(text, languageTag) === true) return;
    } catch (_) {}
    try {
      if (native?.speakText?.(text, languageTag) === true) return;
    } catch (_) {}
    finish();
  };

  window.__professionalVoiceSpeak = (text, languageTag) => {
    const clean = String(text || '').replace(/\s+/g, ' ').trim().slice(0, 2850);
    if (!clean || navigator.onLine === false || !window.puter?.ai?.txt2speech) return false;

    stopAudio();
    const generation = requestGeneration;
    const profile = profiles[selectedProfile] || profiles.natural;

    (async () => {
      try {
        const audio = await window.puter.ai.txt2speech(clean, {
          provider: 'openai',
          model: 'gpt-4o-mini-tts',
          voice: profile.voice,
          response_format: 'mp3',
          instructions: `${profile.direction} ${languageDirection(languageTag)}`
        });
        if (generation !== requestGeneration) return;

        activeAudio = audio;
        audio.onplay = () => {
          if (generation === requestGeneration) window.__nativeSpeechStarted?.();
        };
        audio.onended = () => {
          if (generation !== requestGeneration) return;
          activeAudio = null;
          finish();
        };
        audio.onerror = () => {
          if (generation !== requestGeneration) return;
          activeAudio = null;
          fallbackSpeak(clean, languageTag);
        };
        await audio.play();
      } catch (_) {
        if (generation !== requestGeneration) return;
        activeAudio = null;
        fallbackSpeak(clean, languageTag);
      }
    })();
    return true;
  };

  window.__stopProfessionalVoice = stopAudio;
  window.__professionalVoiceEnabled = true;

  const installSelector = () => {
    const callTop = document.querySelector('.voice-call-top');
    const language = document.getElementById('callVoiceLang');
    if (!callTop || !language) return false;

    if (!document.getElementById('professional-voice-style')) {
      const style = document.createElement('style');
      style.id = 'professional-voice-style';
      style.textContent = `
        .voice-call-selectors{display:flex;align-items:center;gap:8px}
        .voice-profile-select{min-width:92px}
        .voice-call-badge[data-neural="true"]::before{content:"";display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--ok);margin-right:6px;box-shadow:0 0 0 4px color-mix(in srgb,var(--ok) 10%,transparent)}
        @media(max-width:520px){.voice-call-top{align-items:flex-start}.voice-call-selectors{flex-direction:column;align-items:stretch}.voice-language{max-width:132px}}
      `;
      document.head.appendChild(style);
    }

    let controls = document.querySelector('.voice-call-selectors');
    if (!controls) {
      controls = document.createElement('div');
      controls.className = 'voice-call-selectors';
      callTop.appendChild(controls);
      controls.appendChild(language);
    }

    let selector = document.getElementById('voiceProfile');
    if (!selector) {
      selector = document.createElement('select');
      selector.id = 'voiceProfile';
      selector.className = 'voice-language voice-profile-select';
      selector.setAttribute('aria-label', 'AI voice style');
      selector.innerHTML = Object.entries(profiles)
        .map(([value, profile]) => `<option value="${value}">${profile.label} voice</option>`)
        .join('');
      selector.value = selectedProfile;
      selector.onchange = event => {
        selectedProfile = profiles[event.target.value] ? event.target.value : 'natural';
        try { localStorage.setItem(STORAGE_KEY, selectedProfile); } catch (_) {}
      };
      controls.appendChild(selector);
    }

    const badge = document.querySelector('.voice-call-badge');
    if (badge) {
      badge.dataset.neural = 'true';
      badge.textContent = 'Neural multilingual conversation';
    }
    return true;
  };

  let attempts = 0;
  const selectorTimer = setInterval(() => {
    attempts += 1;
    if (installSelector() || attempts >= 40) clearInterval(selectorTimer);
  }, 150);
  installSelector();

  window.__professionalInterpreterVoiceV2 = true;
  return 'ready';
})();
