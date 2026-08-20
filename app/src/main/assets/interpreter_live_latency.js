(() => {
  if (window.__interpreterLiveLatencyV2) return 'ready';
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

    // sendChat() adds the newest user bubble before it calls Puter. Therefore the second-to-last
    // visible user bubble is the question whose AI answer may just have been interrupted.
    const previousUserText = visible[visible.length - 2];
    if (!previousUserText) return;

    const alreadyPresent = request.some(item =>
      item && item.role === 'user' && String(item.content || '').trim() === previousUserText
    );
    if (alreadyPresent) return;

    // Insert immediately before the newest user turn. This preserves the interrupted question
    // without fabricating an assistant response that the user did not finish hearing.
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

  window.__interpreterLiveLatencyV2 = true;
  return 'ready';
})();