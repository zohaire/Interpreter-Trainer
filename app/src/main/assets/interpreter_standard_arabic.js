(() => {
  if (window.__interpreterStandardArabicV3) return 'ready';
  window.__interpreterStandardArabicV3 = true;
  window.__interpreterStandardArabicV2 = true;

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
  observer.observe(document.documentElement, { childList:true, subtree:true });

  const originalPracticeContext = window.nativePracticeContext;
  if (typeof originalPracticeContext === 'function' && !originalPracticeContext.__msaWrapped) {
    const wrappedContext = () => {
      const base = String(originalPracticeContext() || '');
      return `${base}\n\nARABIC LANGUAGE POLICY:\n${MSA_INSTRUCTION}`;
    };
    wrappedContext.__msaWrapped = true;
    window.nativePracticeContext = wrappedContext;
  }

  // Guard every provider request, including voice paths that may not rebuild practice context.
  const installProviderGuard = () => {
    const current = window.__interpreterAiRequest;
    if (typeof current !== 'function' || current.__msaWrapped) return typeof current === 'function';

    const wrapped = async (...args) => {
      const request = args[0];
      if (Array.isArray(request)) {
        const cloned = request.map(item => item && typeof item === 'object' ? { ...item } : item);
        const system = cloned.find(item => item && item.role === 'system' && typeof item.content === 'string');
        if (system && !system.content.includes('Modern Standard Arabic (العربية الفصحى) only')) {
          system.content += `\n\nARABIC LANGUAGE POLICY:\n${MSA_INSTRUCTION}`;
        } else if (!system) {
          cloned.unshift({ role:'system', content:MSA_INSTRUCTION });
        }
        args[0] = cloned;
      }
      return current(...args);
    };
    wrapped.__msaWrapped = true;
    window.__interpreterAiRequest = wrapped;
    return true;
  };

  if (!installProviderGuard()) {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (installProviderGuard() || attempts >= 80) clearInterval(timer);
    }, 150);
  }

  return 'ready';
})();
