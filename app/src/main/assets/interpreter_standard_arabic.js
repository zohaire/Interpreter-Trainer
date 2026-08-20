(() => {
  if (window.__interpreterStandardArabicV1) return 'ready';
  window.__interpreterStandardArabicV1 = true;

  const MSA_INSTRUCTION = `When Arabic is selected or when you answer in Arabic, use Modern Standard Arabic (العربية الفصحى) only. Do not use Moroccan Darija, Maghrebi dialect, Egyptian Arabic, Levantine Arabic, Gulf dialect, or any other colloquial variety unless the user explicitly asks for a dialect. Keep terminology, grammar, pronunciation-oriented text, exercises, model answers, translations, and interpreter-training material in clear professional Modern Standard Arabic.`;

  const relabelArabic = () => {
    document.querySelectorAll('option').forEach(option => {
      const value = String(option.value || '').toLowerCase();
      const text = String(option.textContent || '').trim().toLowerCase();
      if (value === 'ar-ma' || value === 'ar-sa' || value === 'ar' || text === 'arabic' || text === 'العربية') {
        option.textContent = option.closest('#callVoiceLang') ? 'العربية الفصحى' : 'AR · MSA';
      }
    });
  };

  relabelArabic();
  const observer = new MutationObserver(relabelArabic);
  observer.observe(document.documentElement, { childList: true, subtree: true });

  const installChatGuard = () => {
    if (!window.puter?.ai?.chat || window.puter.ai.chat.__msaWrapped) return false;

    const originalChat = window.puter.ai.chat.bind(window.puter.ai);
    const wrapped = async (...args) => {
      const request = args[0];
      if (Array.isArray(request)) {
        const system = request.find(item => item && item.role === 'system' && typeof item.content === 'string');
        if (system && !system.content.includes('Modern Standard Arabic (العربية الفصحى) only')) {
          system.content += `\n\nARABIC LANGUAGE POLICY:\n${MSA_INSTRUCTION}`;
        } else if (!system) {
          request.unshift({ role: 'system', content: MSA_INSTRUCTION });
        }
      }
      return originalChat(...args);
    };
    wrapped.__msaWrapped = true;
    window.puter.ai.chat = wrapped;
    return true;
  };

  if (!installChatGuard()) {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (installChatGuard() || attempts >= 40) clearInterval(timer);
    }, 150);
  }

  return 'ready';
})();
