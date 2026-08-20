(() => {
  if (window.__interpreterLiveNativeDuplexV1) return 'ready';
  const existing = window.InterpreterNative;
  const live = window.InterpreterLiveNative;
  if (!existing || !live) return 'pending';

  const wrapped = new Proxy(existing, {
    get(target, prop) {
      if (prop === 'speakText') {
        return (text, languageTag) => live.speakText?.(String(text || ''), String(languageTag || 'en-US')) === true;
      }
      if (prop === 'stopSpeaking') {
        return () => { try { live.stopSpeaking?.(); } catch (_) {} };
      }
      if (prop === 'startBargeInDetection') {
        return () => {
          try { return live.startBargeInDetection?.() === true; } catch (_) { return false; }
        };
      }
      if (prop === 'stopBargeInDetection') {
        return () => { try { live.stopBargeInDetection?.(); } catch (_) {} };
      }
      const value = target[prop];
      return typeof value === 'function' ? (...args) => value.apply(target, args) : value;
    }
  });

  try {
    window.InterpreterNative = wrapped;
  } catch (_) {
    return 'pending';
  }

  window.__interpreterLiveNativeDuplexV1 = true;
  return 'ready';
})();
