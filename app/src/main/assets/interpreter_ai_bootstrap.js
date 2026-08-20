(() => {
  if (window.__interpreterAiBootstrapV4) return 'ready';
  window.__interpreterAiBootstrapV4 = true;
  window.__interpreterAiCoreVersion = 'AIV4';

  const byId = id => document.getElementById(id);

  const setConnectionStatus = (text, state = 'idle') => {
    const statusText = byId('statusText');
    const statusDot = byId('statusDot');
    if (statusText) statusText.textContent = text;
    if (statusDot) {
      statusDot.className = 'status-dot' + (state === 'ok' ? ' ok' : state === 'bad' ? ' bad' : '');
    }
  };

  const setError = message => {
    const node = byId('chatError');
    if (node) node.textContent = message || '';
  };

  const sdkReady = () => Boolean(window.puter?.ai?.chat);

  // This is deliberately synchronous. The first puter.ai.chat() call must still run inside the
  // original Send tap so Android WebView keeps its transient user activation for Puter's first-use
  // authentication window. Never turn this helper into an async function or await it before chat().
  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV4', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!sdkReady()) {
      setConnectionStatus('AI service unavailable · AIV4', 'bad');
      setError('Interpreter AI could not load its online service. Check your connection and try again.');
      return false;
    }

    setConnectionStatus('Online AI · ready · AIV4', 'ok');
    setError('');
    return true;
  };

  window.connectAi = providerReady;
  window.ensureConnected = providerReady;
  window.__connectInterpreterAi = providerReady;

  const refresh = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV4', 'bad');
      return;
    }
    if (!sdkReady()) {
      setConnectionStatus('Loading AI service · AIV4');
      return;
    }
    setConnectionStatus('Online AI · ready · AIV4', 'ok');
    setError('');
  };

  let refreshAttempts = 0;
  const refreshTimer = setInterval(() => {
    refresh();
    refreshAttempts += 1;
    if (sdkReady() || refreshAttempts >= 40) clearInterval(refreshTimer);
  }, 250);

  refresh();
  window.addEventListener('online', refresh);
  window.addEventListener('offline', () => setConnectionStatus('No internet connection · AIV4', 'bad'));

  return 'ready';
})();