(() => {
  if (window.__interpreterAiProviderV1) return 'ready';
  window.__interpreterAiProviderV1 = true;

  const configuredTimeouts = window.__INTERPRETER_AI_TIMEOUTS || {};
  const AUTH_TIMEOUT_MS = Number(configuredTimeouts.auth) || 120000;
  const REQUEST_TIMEOUT_MS = Number(configuredTimeouts.request) || 60000;
  const STREAM_IDLE_TIMEOUT_MS = Number(configuredTimeouts.streamIdle) || 30000;
  const listeners = new Set();
  let state = 'loading';
  let detail = '';
  let authPromise = null;

  const errorWithCode = (code, message, cause) => {
    const error = new Error(message);
    error.code = code;
    if (cause !== undefined) error.cause = cause;
    return error;
  };

  const providerMessage = error => {
    const code = error?.code || error?.error || error?.status;
    if (code === 'popup_blocked') {
      return 'The secure AI sign-in window was blocked. Tap Connect AI and try again.';
    }
    if (code === 'auth_window_closed') {
      return 'AI connection was cancelled. Tap Connect AI when you are ready.';
    }
    if (code === 'AUTH_TIMEOUT') {
      return 'AI sign-in took too long. Close the sign-in window, then tap Connect AI again.';
    }
    if (code === 'SDK_NOT_READY' || code === 'SDK_LOAD_FAILED') {
      return 'The AI service is still loading. Check your connection, then tap Try again.';
    }
    if (code === 'REQUEST_TIMEOUT') {
      return 'Interpreter AI did not respond in time. Your message is still available to retry.';
    }
    if (code === 'STREAM_TIMEOUT') {
      return 'Interpreter AI stopped responding. Please retry the message.';
    }
    return error?.msg || error?.message || String(error || 'Interpreter AI could not connect.');
  };

  const snapshot = () => ({
    state,
    detail,
    sdkReady: Boolean(window.puter?.ai?.chat),
    authenticated: window.puter?.auth?.isSignedIn?.() === true
  });

  const publish = (nextState, nextDetail = '') => {
    state = nextState;
    detail = nextDetail;
    const value = snapshot();
    for (const listener of [...listeners]) {
      try { listener(value); } catch (_) {}
    }
    return value;
  };

  const withTimeout = (promise, timeoutMs, code, message) => new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(errorWithCode(code, message));
    }, timeoutMs);
    Promise.resolve(promise).then(value => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    }, error => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
  });

  const syncState = () => {
    if (navigator.onLine === false) return publish('offline', 'Internet connection required');
    if (!window.puter?.ai?.chat) return publish('loading', 'Loading secure AI service');
    if (window.puter?.auth?.isSignedIn?.() === true) return publish('ready', 'Connected');
    if (authPromise) return publish('connecting', 'Complete the secure sign-in window');
    if (state === 'error') return snapshot();
    return publish('needs_auth', 'Tap Connect AI once');
  };

  const preload = () => {
    if (navigator.onLine === false) {
      syncState();
      return Promise.reject(errorWithCode('OFFLINE', 'Interpreter AI needs an internet connection.'));
    }
    if (window.puter?.ai?.chat) {
      syncState();
      return Promise.resolve(window.puter);
    }
    publish('loading', 'Loading secure AI service');
    let load;
    try { load = window.__loadInterpreterAiSdk?.(); }
    catch (error) {
      publish('error', providerMessage(error));
      return Promise.reject(error);
    }
    if (!load?.then) {
      const error = errorWithCode('SDK_NOT_READY', 'The AI service loader is not ready.');
      publish('error', providerMessage(error));
      return Promise.reject(error);
    }
    return load.then(service => {
      syncState();
      return service;
    }).catch(cause => {
      const error = errorWithCode('SDK_LOAD_FAILED', providerMessage(cause), cause);
      publish('error', error.message);
      throw error;
    });
  };

  // This function must be invoked directly by a click/tap handler. signIn() is deliberately called
  // before any await so Android WebView preserves the user activation required to open Puter's
  // secure authentication window.
  const connectFromUserGesture = () => {
    if (navigator.onLine === false) {
      const error = errorWithCode('OFFLINE', 'Interpreter AI needs an internet connection.');
      publish('offline', error.message);
      return Promise.reject(error);
    }
    if (!window.puter?.ai?.chat || !window.puter?.auth?.signIn) {
      preload().catch(() => {});
      const error = errorWithCode('SDK_NOT_READY', 'The AI service is still loading.');
      publish('loading', providerMessage(error));
      return Promise.reject(error);
    }
    if (window.puter.auth.isSignedIn?.() === true) {
      publish('ready', 'Connected');
      return Promise.resolve(true);
    }
    if (authPromise) return authPromise;

    publish('connecting', 'Complete the secure sign-in window');
    let signInRequest;
    try {
      signInRequest = window.puter.auth.signIn({ attempt_temp_user_creation: true });
    } catch (cause) {
      const error = errorWithCode(cause?.code || cause?.error || 'AUTH_FAILED', providerMessage(cause), cause);
      publish('error', error.message);
      return Promise.reject(error);
    }

    authPromise = withTimeout(
      signInRequest,
      AUTH_TIMEOUT_MS,
      'AUTH_TIMEOUT',
      'AI sign-in took too long.'
    ).then(() => {
      if (window.puter?.auth?.isSignedIn?.() !== true) {
        throw errorWithCode('AUTH_FAILED', 'AI sign-in finished without a valid session.');
      }
      publish('ready', 'Connected');
      return true;
    }).catch(cause => {
      const error = cause instanceof Error
        ? cause
        : errorWithCode(cause?.code || cause?.error || 'AUTH_FAILED', providerMessage(cause), cause);
      publish('error', providerMessage(error));
      throw error;
    }).finally(() => {
      authPromise = null;
    });
    return authPromise;
  };

  const request = (messages, options = {}) => {
    if (navigator.onLine === false) {
      throw errorWithCode('OFFLINE', 'Interpreter AI needs an internet connection.');
    }
    if (!window.puter?.ai?.chat) {
      throw errorWithCode('SDK_NOT_READY', 'The AI service is not loaded.');
    }
    if (window.puter?.auth?.isSignedIn?.() !== true) {
      throw errorWithCode('AUTH_REQUIRED', 'Tap Connect AI before sending a message.');
    }
    const raw = window.puter.ai.chat(messages, options);
    return withTimeout(
      raw,
      Number(options.request_timeout_ms) || REQUEST_TIMEOUT_MS,
      'REQUEST_TIMEOUT',
      'Interpreter AI did not start responding in time.'
    );
  };

  const streamParts = async function* (stream, idleTimeoutMs = STREAM_IDLE_TIMEOUT_MS) {
    if (!stream || typeof stream[Symbol.asyncIterator] !== 'function') {
      yield stream;
      return;
    }
    const iterator = stream[Symbol.asyncIterator]();
    try {
      while (true) {
        const item = await withTimeout(
          iterator.next(),
          idleTimeoutMs,
          'STREAM_TIMEOUT',
          'Interpreter AI stopped streaming.'
        );
        if (item.done) break;
        yield item.value;
      }
    } catch (error) {
      try { Promise.resolve(iterator.return?.()).catch(() => {}); } catch (_) {}
      throw error;
    }
  };

  const subscribe = listener => {
    if (typeof listener !== 'function') return () => {};
    listeners.add(listener);
    listener(snapshot());
    return () => listeners.delete(listener);
  };

  window.InterpreterAiProvider = {
    preload,
    connectFromUserGesture,
    request,
    streamParts,
    subscribe,
    syncState,
    getState: snapshot,
    messageForError: providerMessage,
    constants: { AUTH_TIMEOUT_MS, REQUEST_TIMEOUT_MS, STREAM_IDLE_TIMEOUT_MS }
  };

  window.addEventListener('online', () => preload().catch(() => {}));
  window.addEventListener('offline', syncState);
  preload().catch(() => {});
  return 'ready';
})();
