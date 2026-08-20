(() => {
  if (window.__interpreterLiveLatencyV1) return 'ready';
  if (!window.__fastInterpreterVoiceV3 || !window.puter?.ai?.chat) return 'pending';

  const originalChat = window.puter.ai.chat.bind(window.puter.ai);
  const LIVE_MARKER = 'INTERPRETER LIVE LOW-LATENCY POLICY';

  const wrappedChat = async (...args) => {
    if (window.__voiceCallActive) {
      const request = args[0];
      if (Array.isArray(request)) {
        const system = request.find(item => item && item.role === 'system' && typeof item.content === 'string');
        if (system && !system.content.includes(LIVE_MARKER)) {
          system.content += `\n\n${LIVE_MARKER}: Respond immediately and conversationally. Put the direct answer in the first sentence. Unless the user explicitly asks for detail, use only 1–2 short spoken sentences. No preamble, headings, recap, or filler. If the user interrupts or adds information, treat that complete new utterance as the newest turn and answer it directly.`;
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

  window.__interpreterLiveLatencyV1 = true;
  return 'ready';
})();
