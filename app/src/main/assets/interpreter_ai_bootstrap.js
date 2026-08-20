(() => {
  if (window.__interpreterAiBootstrapV1) return 'ready';
  window.__interpreterAiBootstrapV1 = true;

  const byId = id => document.getElementById(id);
  let connectionPromise = null;
  let automaticAttempted = false;

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

  const sdkReady = () => Boolean(window.puter?.auth?.isSignedIn && window.puter?.auth?.signIn && window.puter?.ai?.chat);
  const isSignedIn = () => {
    try { return sdkReady() && window.puter.auth.isSignedIn() === true; }
    catch (_) { return false; }
  };

  const connect = async ({ silent = false } = {}) => {
    if (!sdkReady()) {
      setConnectionStatus('AI service unavailable', 'bad');
      if (!silent) setError('Interpreter AI could not load its online service. Check your internet connection and try again.');
      return false;
    }

    if (isSignedIn()) {
      setConnectionStatus('Online · ready', 'ok');
      setError('');
      return true;
    }

    if (connectionPromise) return connectionPromise;

    connectionPromise = (async () => {
      try {
        setConnectionStatus('Connecting…');
        if (!silent) setError('');
        await window.puter.auth.signIn({ attempt_temp_user_creation: true });
        if (!isSignedIn()) throw new Error('Authentication did not complete.');
        setConnectionStatus('Online · ready', 'ok');
        setError('');
        return true;
      } catch (error) {
        const message = error?.msg || error?.message || String(error || 'Connection failed');
        const retryable = /popup|window|closed|blocked|gesture|cancel/i.test(message);
        setConnectionStatus(retryable ? 'Tap send to connect' : 'Connection needed', retryable ? 'idle' : 'bad');
        if (!silent && !retryable) setError('Could not connect Interpreter AI: ' + message);
        return false;
      } finally {
        connectionPromise = null;
      }
    })();

    return connectionPromise;
  };

  // Replace the base page helpers so every chat/evaluation path shares one connection attempt.
  window.connectAi = () => connect({ silent: false });
  window.ensureConnected = () => connect({ silent: false });
  window.__connectInterpreterAi = connect;

  // Puter authentication is most reliable when initiated by a real user gesture. Start it on
  // pointer-down, before the existing click handler reaches sendChat(), and make sendChat await
  // the same promise instead of opening a second auth window.
  const armUserGestureConnection = () => {
    const candidates = [byId('sendBtn'), byId('evaluateBtn'), ...document.querySelectorAll('.suggestion')].filter(Boolean);
    candidates.forEach(node => {
      if (node.dataset.aiConnectArmed === '1') return;
      node.dataset.aiConnectArmed = '1';
      node.addEventListener('pointerdown', () => {
        if (!isSignedIn()) void connect({ silent: true });
      }, { capture: true, passive: true });
    });
  };

  const refresh = () => {
    armUserGestureConnection();
    if (!sdkReady()) {
      setConnectionStatus(navigator.onLine === false ? 'No internet connection' : 'Loading AI service…', navigator.onLine === false ? 'bad' : 'idle');
      return;
    }
    if (isSignedIn()) setConnectionStatus('Online · ready', 'ok');
    else setConnectionStatus('Connecting…');
  };

  const observer = new MutationObserver(armUserGestureConnection);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  refresh();

  // Try temporary-user creation immediately. Android WebView is configured to permit JS child
  // windows; if the provider still requires a user activation, the pointer-down fallback above
  // retries under the user's first Send/Evaluate gesture without losing their message.
  if (!automaticAttempted && sdkReady() && !isSignedIn()) {
    automaticAttempted = true;
    setTimeout(() => void connect({ silent: true }), 180);
  }

  window.addEventListener('online', () => {
    refresh();
    if (!isSignedIn()) void connect({ silent: true });
  });
  window.addEventListener('offline', () => setConnectionStatus('No internet connection', 'bad'));

  return 'ready';
})();
