(() => {
  if (window.__interpreterAiBootstrapV2) return 'ready';
  window.__interpreterAiBootstrapV2 = true;

  const byId = id => document.getElementById(id);
  let connectionPromise = null;

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

  const sdkReady = () => Boolean(
    window.puter?.auth?.isSignedIn &&
    window.puter?.auth?.signIn &&
    window.puter?.ai?.chat
  );

  const isSignedIn = () => {
    try { return sdkReady() && window.puter.auth.isSignedIn() === true; }
    catch (_) { return false; }
  };

  const connect = async () => {
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

    if (isSignedIn()) {
      setConnectionStatus('Online · ready', 'ok');
      setError('');
      return true;
    }

    if (connectionPromise) return connectionPromise;

    // Puter requires signIn() to be initiated by a real user action because it opens a popup.
    // This function is intentionally called only from Send/Evaluate/suggestion actions. Do not
    // move it to a timer, page-load callback, coroutine retry, or background connection attempt.
    connectionPromise = (async () => {
      try {
        setConnectionStatus('Connecting…');
        setError('');
        await window.puter.auth.signIn({ attempt_temp_user_creation: true });
        if (!isSignedIn()) throw new Error('Authentication did not complete.');
        setConnectionStatus('Online · ready', 'ok');
        setError('');
        return true;
      } catch (error) {
        const message = error?.msg || error?.message || String(error || 'Connection failed');
        const cancelled = /cancel|closed|auth_window_closed/i.test(message);
        setConnectionStatus(cancelled ? 'AI ready · send to start' : 'Connection needed', cancelled ? 'idle' : 'bad');
        if (!cancelled) setError('Could not connect Interpreter AI: ' + message);
        return false;
      } finally {
        connectionPromise = null;
      }
    })();

    return connectionPromise;
  };

  // Replace the bundled page helpers. sendChat() and evaluatePerformance() call ensureConnected()
  // directly from their click handlers, preserving the browser user activation Puter needs.
  window.connectAi = connect;
  window.ensureConnected = connect;
  window.__connectInterpreterAi = connect;

  const refresh = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection', 'bad');
      return;
    }
    if (!sdkReady()) {
      setConnectionStatus('Loading AI service…');
      return;
    }
    if (isSignedIn()) setConnectionStatus('Online · ready', 'ok');
    else setConnectionStatus('AI ready · send to start');
  };

  // The SDK is loaded by a blocking script tag before the coach's own script, but WebView can be
  // unusually slow after process startup. Poll status only; never authenticate from this timer.
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