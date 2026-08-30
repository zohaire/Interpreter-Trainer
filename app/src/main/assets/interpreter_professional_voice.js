(() => {
  if (window.__professionalInterpreterVoiceV1) return 'ready';
  if (!window.InterpreterNative) return 'pending';

  const STORAGE_KEY = 'interpreterProfessionalVoiceV1';
  const native = window.InterpreterNative;
  const live = window.InterpreterLiveNative || null;
  const profiles = {
    studio: {
      label: 'Studio',
      voice: 'coral',
      direction: 'Sound like a polished present-day conference interpreter coach: natural, composed, warm and precise. Use crisp diction and a conversational medium pace. Avoid theatrical, synthetic or old-fashioned announcer delivery.'
    },
    warm: {
      label: 'Warm',
      voice: 'ballad',
      direction: 'Sound warm, attentive and human while remaining professional. Use natural phrasing, subtle expression and an unhurried conversational pace. Never sound theatrical or like an automated announcement.'
    },
    broadcast: {
      label: 'Broadcast',
      voice: 'onyx',
      direction: 'Sound like a contemporary international news and conference professional: confident, clear and neutral. Keep the delivery lively but controlled, without a vintage radio cadence.'
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
      const stored = localStorage.getItem(STORAGE_KEY) || 'studio';
      return profiles[stored] ? stored : 'studio';
    } catch (_) {
      return 'studio';
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
    const profile = profiles[selectedProfile] || profiles.studio;

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
        selectedProfile = profiles[event.target.value] ? event.target.value : 'studio';
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

  window.__professionalInterpreterVoiceV1 = true;
  return 'ready';
})();
