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
  let preferNativeNetwork = false;
  let nativeSequence = 0;
  const nativeCalls = new Map();

  const errorWithCode = (code, message, cause) => {
    const error = new Error(message);
    error.code = code;
    if (cause !== undefined) error.cause = cause;
    return error;
  };

  const errorDetails = error => {
    let value = error;
    try {
      if (error?.responseJSON) value = error.responseJSON;
      else if (typeof error?.responseText === 'string' && error.responseText.trim().startsWith('{')) {
        value = JSON.parse(error.responseText);
      }
    } catch (_) {}
    const nested = value?.error && typeof value.error === 'object' ? value.error : value;
    return {
      code: nested?.code || (typeof nested?.error === 'string' ? nested.error : undefined) || value?.code,
      status: Number(error?.status || nested?.status || value?.status) || 0,
      message: nested?.msg || nested?.message || (typeof value === 'string' ? value : '')
    };
  };

  const isNetworkFailure = error => {
    const value = errorDetails(error);
    return value.code === 'NETWORK_ERROR' ||
      (value.status === 0 && error && typeof error.getResponseHeader === 'function') ||
      /^(NetworkError|Failed to fetch|Network request .* failed|Network error occurred)/i.test(value.message);
  };

  const providerMessage = error => {
    const value = errorDetails(error);
    const code = value.code || value.status;
    if (code === 'NETWORK_ERROR' || isNetworkFailure(error)) {
      return 'The AI service could not be reached. Check your connection and try sending again.';
    }
    if (code === 'TLS_ERROR') return 'A secure connection to the AI service could not be established. Check the device date and network.';
    if (value.status === 401 || ['AUTH_REQUIRED', 'token_auth_failed', 'auth_token_expired', 'invalid_token'].includes(code)) {
      return 'Your AI session has expired. Tap Connect AI to sign in again; your message is saved.';
    }
    if (value.status === 429 || code === 'rate_limit_exceeded') return 'The AI service is busy or rate-limited. Wait a moment and retry.';
    if (value.status === 402 || code === 'insufficient_funds') return 'Your Puter account has reached its AI usage allowance. Check your account before retrying.';
    if (code === 'email_must_be_confirmed') return 'Confirm your email in Puter, then retry this message.';
    if (value.status === 403) return 'Puter denied this request. Check your account permissions, then retry.';
    if (value.status >= 500) return 'The AI provider is temporarily unavailable. Please retry shortly.';
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
    return typeof value.message === 'string' && value.message ? value.message.slice(0, 400) :
      'Interpreter AI could not complete the request. Please try again.';
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
    if (authPromise) return publish('connecting', 'Complete the secure sign-in window');
    if (state === 'error') return snapshot();
    if (window.puter?.auth?.isSignedIn?.() === true) return publish('ready', 'Connected');
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

  const requestFailure = cause => {
    const value = errorDetails(cause);
    const error = errorWithCode(value.code || (isNetworkFailure(cause) ? 'NETWORK_ERROR' : 'REQUEST_FAILED'), providerMessage(cause));
    error.status = value.status;
    if (value.status === 401 || ['AUTH_REQUIRED', 'token_auth_failed', 'auth_token_expired', 'invalid_token'].includes(value.code)) {
      try { window.puter?.auth?.signOut?.(); } catch (_) {}
      publish('needs_auth', error.message);
    } else publish('error', error.message);
    return error;
  };

  // The APK fallback talks only to Puter's fixed HTTPS chat endpoint using the current SDK
  // session token. It preserves NDJSON streaming and never retries after partial output.
  const nativeRequest = (messages, options) => {
    [messages, options] = window.__prepareInterpreterLiveRequest?.([messages, options]) || [messages, options];
    window.__applyInterpreterArabicPolicy?.(messages);
    const bridge = window.InterpreterAiNetwork;
    const id = `chat_${Date.now()}_${++nativeSequence}`;
    let resolveStart, rejectStart;
    let done = false, failure = null, started = false;
    const queue = [], readers = [];
    const start = new Promise((resolve, reject) => { resolveStart = resolve; rejectStart = reject; });
    const cancel = () => { try { bridge.cancelChat(id); } catch (_) {} };
    const finish = () => {
      done = true;
      nativeCalls.delete(id);
      while (readers.length) readers.shift().resolve({ done: true });
    };
    const fail = cause => {
      if (done) return;
      failure = requestFailure(cause);
      rejectStart(failure);
      while (readers.length) readers.shift().reject(failure);
      finish();
      cancel();
    };
    const iterator = {
      [Symbol.asyncIterator]() { return this; },
      next() {
        if (failure) return Promise.reject(failure);
        if (queue.length) return Promise.resolve({ value: queue.shift(), done: false });
        if (done) return Promise.resolve({ done: true });
        return new Promise((resolve, reject) => readers.push({ resolve, reject }));
      },
      return() { finish(); cancel(); return Promise.resolve({ done: true }); }
    };
    nativeCalls.set(id, event => {
      if (done) return;
      try {
        if (event.kind === 'transport_error') throw { code: event.data };
        if (event.kind === 'http_error') {
          let error = {};
          try { error = JSON.parse(event.data); } catch (_) {}
          throw { ...error, status: event.status };
        }
        if (event.kind === 'started') { started = true; resolveStart(iterator); return; }
        if (event.kind === 'done') { if (!started) resolveStart(iterator); finish(); return; }
        const value = JSON.parse(event.data);
        if (value?.error || value?.success === false) throw value;
        if (event.kind === 'result') {
          resolveStart(value?.result ?? value);
          finish();
        } else if (event.kind === 'part') {
          if (readers.length) readers.shift().resolve({ value, done: false });
          else queue.push(value);
        }
      } catch (error) { fail(error); }
    });
    const { request_timeout_ms, ...args } = options;
    const body = JSON.stringify({
      interface: 'puter-chat-completion', driver: 'ai-chat', method: 'complete',
      test_mode: false, args: { ...args, messages }, auth_token: window.puter.authToken
    });
    try {
      if (bridge.startChat(id, body) !== true) fail({ code: 'NETWORK_ERROR' });
    } catch (error) { fail(error); }
    return withTimeout(start, Number(request_timeout_ms) || REQUEST_TIMEOUT_MS,
      'REQUEST_TIMEOUT', 'Interpreter AI did not start responding in time.').catch(error => {
      fail(error);
      throw error;
    });
  };

  const request = async (messages, options = {}) => {
    if (navigator.onLine === false) {
      throw errorWithCode('OFFLINE', 'Interpreter AI needs an internet connection.');
    }
    if (!window.puter?.ai?.chat) {
      throw errorWithCode('SDK_NOT_READY', 'The AI service is not loaded.');
    }
    if (window.puter?.auth?.isSignedIn?.() !== true) {
      throw errorWithCode('AUTH_REQUIRED', 'Tap Connect AI before sending a message.');
    }
    const nativeAvailable = Boolean(window.InterpreterAiNetwork?.startChat && window.puter.authToken);
    if (preferNativeNetwork && nativeAvailable) return nativeRequest(messages, options);
    try {
      const response = await withTimeout(
        window.puter.ai.chat(messages, options),
        Number(options.request_timeout_ms) || REQUEST_TIMEOUT_MS,
        'REQUEST_TIMEOUT',
        'Interpreter AI did not start responding in time.'
      );
      if (!nativeAvailable || !response?.[Symbol.asyncIterator]) return response;
      return (async function* () {
        let receivedPart = false;
        try {
          for await (const part of response) { receivedPart = true; yield part; }
        } catch (error) {
          if (receivedPart || !isNetworkFailure(error)) throw error;
          preferNativeNetwork = true;
          const recovered = await nativeRequest(messages, options);
          for await (const part of streamParts(recovered)) yield part;
        }
      })();
    } catch (error) {
      if (nativeAvailable && isNetworkFailure(error)) {
        preferNativeNetwork = true;
        return nativeRequest(messages, options);
      }
      throw requestFailure(error);
    }
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
        if (item.value?.error || item.value?.success === false) throw item.value;
        yield item.value;
      }
    } catch (error) {
      throw requestFailure(error);
    } finally {
      try { Promise.resolve(iterator.return?.()).catch(() => {}); } catch (_) {}
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
    onNativeEvent: (id, event) => nativeCalls.get(id)?.(event),
    constants: { AUTH_TIMEOUT_MS, REQUEST_TIMEOUT_MS, STREAM_IDLE_TIMEOUT_MS }
  };

  window.addEventListener('online', () => preload().catch(() => {}));
  window.addEventListener('offline', syncState);
  preload().catch(() => {});
  return 'ready';
})();
