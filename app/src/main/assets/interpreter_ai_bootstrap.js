(() => {
  if (window.__interpreterAiBootstrapV3) return 'ready';
  window.__interpreterAiBootstrapV3 = true;

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

  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!sdkReady()) {
      setConnectionStatus('AI service unavailable', 'bad');
      setError('Interpreter AI could not load its online service. Check your connection and try again.');
      return false;
    }

    // Puter cloud methods authenticate automatically when they are actually invoked. Calling
    // auth.signIn() ourselves inside Android WebView created a second popup/auth state machine and
    // was the source of repeated auth_window_closed / "send to start" failures. The real AI call
    // now owns authentication exactly as Puter documents for essential cloud methods.
    setConnectionStatus('Online AI · ready', 'ok');
    setError('');
    return true;
  };

  // Keep the legacy page API, but do not perform a separate manual login. sendChat() and
  // evaluatePerformance() proceed directly to puter.ai.chat(), which performs provider-managed
  // authentication on the first real request and then reuses the resulting session.
  window.connectAi = providerReady;
  window.ensureConnected = providerReady;
  window.__connectInterpreterAi = providerReady;

  const refresh = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection', 'bad');
      return;
    }
    if (!sdkReady()) {
      setConnectionStatus('Loading AI service…');
      return;
    }
    setConnectionStatus('Online AI · ready', 'ok');
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
  window.addEventListener('offline', () => setConnectionStatus('No internet connection', 'bad'));

  return 'ready';
})();