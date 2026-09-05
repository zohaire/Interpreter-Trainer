(() => {
  if (window.__interpreterStandardArabicV2) return 'ready';
  window.__interpreterStandardArabicV2 = true;

  const MSA_INSTRUCTION = `When Arabic is selected or when you answer in Arabic, use Modern Standard Arabic (العربية الفصحى) only. Do not use Moroccan Darija, Maghrebi dialect, Egyptian Arabic, Levantine Arabic, Gulf dialect, or any other colloquial variety unless the user explicitly asks for a dialect. Keep terminology, grammar, pronunciation-oriented text, exercises, model answers, translations, and interpreter-training material in clear professional Modern Standard Arabic.`;

  const relabelArabic = () => {
    document.querySelectorAll('option').forEach(option => {
      const value = String(option.value || '').toLowerCase();
      const text = String(option.textContent || '').trim().toLowerCase();
      if (value === 'ar-ma' || value === 'ar-sa' || value === 'ar' || text === 'arabic' || text === 'العربية') {
        const label = option.closest('#callVoiceLang') ? 'العربية الفصحى' : 'AR · MSA';
        // Assigning textContent queues a childList mutation even when the text is unchanged.
        // This observer watches the whole coach, so unconditional writes create an infinite
        // microtask loop that prevents sign-in, chat, rendering and timers from running.
        if (option.textContent !== label) option.textContent = label;
      }
    });
  };

  relabelArabic();
  const observer = new MutationObserver(relabelArabic);
  observer.observe(document.documentElement, { childList: true, subtree: true });

  // All active Interpreter AI chat paths read this context when building their system prompt.
  // Extending it here is safer than duplicating or replacing the existing chat implementation.
  const originalPracticeContext = window.nativePracticeContext;
  if (typeof originalPracticeContext === 'function' && !originalPracticeContext.__msaWrapped) {
    const wrappedContext = () => {
      const base = String(originalPracticeContext() || '');
      return `${base}\n\nARABIC LANGUAGE POLICY:\n${MSA_INSTRUCTION}`;
    };
    wrappedContext.__msaWrapped = true;
    window.nativePracticeContext = wrappedContext;
  }

  // The backend owns the authoritative language policy for chat and evaluation.
  return 'ready';
})();
