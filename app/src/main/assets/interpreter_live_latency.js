(() => {
  if (window.__interpreterLiveLatencyV4) return 'ready';
  if (!(window.__fastInterpreterVoiceV4 || window.__fastInterpreterVoiceV3)) return 'pending';

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
    request.splice(Math.max(1, insertAt), 0, { role:'user', content:previousUserText });
  };

  // Preserve the low-latency/live policy by wrapping the app's provider-agnostic request function.
  const installLatencyGuard = () => {
    const current = window.__interpreterAiRequest;
    if (typeof current !== 'function' || current.__interpreterLiveLatencyWrapped) return typeof current === 'function';

    const wrapped = async (...args) => {
      if (window.__voiceCallActive) {
        const request = args[0];
        if (Array.isArray(request)) {
          const cloned = request.map(item => item && typeof item === 'object' ? { ...item } : item);
          preserveInterruptedContext(cloned);
          const system = cloned.find(item => item && item.role === 'system' && typeof item.content === 'string');
          if (system && !system.content.includes(LIVE_MARKER)) {
            system.content += `\n\n${LIVE_MARKER}: Respond immediately and conversationally. Put the direct answer in the first sentence. Unless the user explicitly asks for detail, use only 1–2 short spoken sentences. No preamble, headings, recap, or filler. If the user interrupts or adds information, treat that complete new utterance as the newest turn, retain the immediately preceding user request as context, and answer the new combined intent directly.`;
          }
          args[0] = cloned;
        }
        const currentOptions = args[1] && typeof args[1] === 'object' ? args[1] : {};
        args[1] = { ...currentOptions, max_tokens:Math.min(Number(currentOptions.max_tokens) || 160, 160) };
      }
      return current(...args);
    };
    wrapped.__interpreterLiveLatencyWrapped = true;
    window.__interpreterAiRequest = wrapped;
    return true;
  };

  if (!installLatencyGuard()) {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (installLatencyGuard() || attempts >= 80) clearInterval(timer);
    }, 150);
  }

  // Android SpeechRecognizer and native TTS are the only speech fallbacks. No paid cloud STT/TTS.
  window.__stopOnlineVoice = () => {};
  window.__onlineVoiceSpeak = async () => false;

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
      requestedSmooth = requestedSmooth || smoothArg === true;
      if (scrollFrame) return;
      scrollFrame = requestAnimationFrame(() => {
        scrollFrame = 0;
        const scroll = document.getElementById('chatScroll');
        if (!scroll) return;
        const smooth = requestedSmooth && !window.__voiceCallActive;
        requestedSmooth = false;
        if (smooth) scroll.scrollTo({ top:scroll.scrollHeight, behavior:'smooth' });
        else scroll.scrollTop = scroll.scrollHeight;
      });
    };
  }

  window.__interpreterLiveLatencyV2 = true;
  window.__interpreterLiveLatencyV3 = true;
  window.__interpreterLiveLatencyV4 = true;
  return 'ready';
})();
