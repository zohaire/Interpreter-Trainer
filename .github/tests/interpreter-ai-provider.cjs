const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync(process.env.INTERPRETER_PROVIDER_SOURCE || 'app/src/main/assets/interpreter_ai_provider.js', 'utf8');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function runtime({ signedIn = false, signIn, chat, sdkInitiallyReady = true } = {}) {
  const calls = { signIn: 0, chat: 0, load: 0 };
  let signed = signedIn;
  const listeners = {};
  const puter = {
    auth: {
      isSignedIn: () => signed,
      signOut() { signed = false; calls.signOut = (calls.signOut || 0) + 1; },
      signIn(options) {
        calls.signIn += 1;
        calls.signInOptions = options;
        const result = signIn ? signIn({ setSigned: value => { signed = value; } }) : Promise.resolve().then(() => { signed = true; });
        return result;
      }
    },
    ai: {
      chat(messages, options) {
        calls.chat += 1;
        calls.messages = messages;
        calls.options = options;
        return chat ? chat() : Promise.resolve({ message: { content: 'OK' } });
      }
    }
  };
  const window = {
    __INTERPRETER_AI_TIMEOUTS: { auth: 25, request: 25, streamIdle: 25 },
    puter: sdkInitiallyReady ? puter : undefined,
    addEventListener(name, listener) { listeners[name] = listener; },
    __loadInterpreterAiSdk() {
      calls.load += 1;
      if (sdkInitiallyReady) return Promise.resolve(puter);
      return Promise.resolve().then(() => {
        window.puter = puter;
        return puter;
      });
    }
  };
  const context = {
    window,
    navigator: { onLine: true },
    setTimeout,
    clearTimeout,
    Promise,
    Error,
    String,
    Number,
    Set,
    Symbol
  };
  window.window = window;
  window.navigator = context.navigator;
  window.setTimeout = setTimeout;
  window.clearTimeout = clearTimeout;
  vm.createContext(context);
  assert.strictEqual(vm.runInContext(source, context), 'ready');
  return { context, window, puter, calls, listeners };
}

async function testExplicitAuthentication() {
  const { window, calls } = runtime();
  const connection = window.InterpreterAiProvider.connectFromUserGesture();
  assert.strictEqual(calls.signIn, 1, 'signIn must run synchronously inside the original tap.');
  assert.strictEqual(calls.signInOptions?.attempt_temp_user_creation, true);
  await connection;
  assert.strictEqual(window.InterpreterAiProvider.getState().state, 'ready');

  const response = await window.InterpreterAiProvider.request(
    [{ role: 'user', content: 'test' }],
    { model: 'qwen/qwen3.8-27b:free', normalize: true }
  );
  assert.strictEqual(response.message.content, 'OK');
  assert.strictEqual(calls.chat, 1);
  assert.strictEqual(calls.options.normalize, true);
}

async function testBlockedPopupIsRecoverable() {
  const { window, calls } = runtime({
    signIn: () => Promise.reject({ error: 'popup_blocked', msg: 'blocked' })
  });
  await assert.rejects(window.InterpreterAiProvider.connectFromUserGesture());
  assert.strictEqual(calls.signIn, 1);
  assert.strictEqual(window.InterpreterAiProvider.getState().state, 'error');
  assert.match(window.InterpreterAiProvider.getState().detail, /Tap Connect AI/i);
  await assert.rejects(window.InterpreterAiProvider.connectFromUserGesture());
  assert.strictEqual(calls.signIn, 2, 'A failed sign-in must not leave a permanent in-flight lock.');
}

async function testSdkLoadingRequiresFreshTap() {
  const { window, calls } = runtime({ sdkInitiallyReady: false });
  await assert.rejects(
    window.InterpreterAiProvider.connectFromUserGesture(),
    error => error.code === 'SDK_NOT_READY'
  );
  assert.ok(calls.load >= 1, 'The SDK loader was not started.');
  await sleep(0);
  assert.strictEqual(window.InterpreterAiProvider.getState().state, 'needs_auth');
  const connection = window.InterpreterAiProvider.connectFromUserGesture();
  assert.strictEqual(calls.signIn, 1, 'A fresh tap must synchronously launch sign-in after SDK load.');
  await connection;
}

async function testRequestAndStreamTimeouts() {
  const hangingRequest = runtime({ signedIn: true, chat: () => new Promise(() => {}) });
  await assert.rejects(
    hangingRequest.window.InterpreterAiProvider.request([], {}),
    error => error.code === 'REQUEST_TIMEOUT'
  );

  const hangingStream = runtime({ signedIn: true });
  const stream = {
    [Symbol.asyncIterator]() {
      return { next: () => new Promise(() => {}), return: async () => ({ done: true }) };
    }
  };
  await assert.rejects(async () => {
    for await (const _ of hangingStream.window.InterpreterAiProvider.streamParts(stream)) {}
  }, error => error.code === 'STREAM_TIMEOUT');
}

const failedXhr = () => ({ status: 0, readyState: 4, responseText: '',
  getResponseHeader() { return null; }, toString() { return '[object XMLHttpRequest]'; } });

function addNative(r, respond) {
  const requests = [], cancelled = [];
  r.puter.authToken = 'test-session-token';
  r.window.InterpreterAiNetwork = {
    startChat(id, body) {
      requests.push(JSON.parse(body));
      setTimeout(() => respond((kind, data = '', status = 200) =>
        r.window.InterpreterAiProvider.onNativeEvent(id, { kind, data, status })), 0);
      return true;
    },
    cancelChat(id) { cancelled.push(id); }
  };
  return { requests, cancelled };
}

async function testNativeFirstAndNextTurn() {
  const r = runtime({ signedIn: true, chat: () => Promise.reject(failedXhr()) });
  const native = addNative(r, send => {
    send('started');
    send('part', JSON.stringify({ text: 'Hello ' }));
    send('part', JSON.stringify({ text: 'العالم' }));
    send('done');
  });
  const provider = r.window.InterpreterAiProvider;
  for (let turn = 0; turn < 2; turn++) {
    const response = await provider.request([{ role: 'user', content: 'Hello' }], { stream: true, model: 'qwen/qwen3.8-27b:free' });
    let answer = '';
    for await (const part of provider.streamParts(response)) answer += part.text;
    assert.strictEqual(answer, 'Hello العالم');
  }
  assert.strictEqual(r.calls.chat, 0, 'Android must never enter SDK chat before the native request.');
  assert.strictEqual(native.requests.length, 2);
  assert.strictEqual(native.requests[0].auth_token, 'test-session-token');
  assert.strictEqual(native.requests[0].driver, 'ai-chat');
  assert.strictEqual(native.requests[0].interface, 'puter-chat-completion');
  assert.strictEqual(native.requests[0].test_mode, false);
  assert.strictEqual(native.requests[0].args.model, 'qwen/qwen3.8-27b:free');
  assert.strictEqual(native.requests[0].args.stream, true);
}

async function testNativeEvaluationAndCancellation() {
  const evaluation = runtime({ signedIn: true, chat: () => Promise.reject(failedXhr()) });
  addNative(evaluation, send => send('result', JSON.stringify({ success: true, result: { message: { content: 'Feedback' } } })));
  const result = await evaluation.window.InterpreterAiProvider.request([{ role: 'user', content: 'Evaluate' }], { stream: false });
  assert.strictEqual(result.message.content, 'Feedback');

  const cancelled = runtime({ signedIn: true, chat: () => Promise.reject(failedXhr()) });
  const native = addNative(cancelled, send => send('started'));
  const stream = await cancelled.window.InterpreterAiProvider.request([{ role: 'user', content: 'Hello' }], { stream: true });
  await assert.rejects(async () => {
    for await (const _ of cancelled.window.InterpreterAiProvider.streamParts(stream)) {}
  }, error => error.code === 'STREAM_TIMEOUT');
  assert.ok(native.cancelled.length > 0, 'A stalled native stream must be disconnected.');
}

async function testNativeStreamFailureIsNotReplayed() {
  const r = runtime({ signedIn: true });
  const native = addNative(r, send => {
    send('started');
    send('part', JSON.stringify({ text: 'Partial answer' }));
    setTimeout(() => send('transport_error', 'NETWORK_ERROR'), 5);
  });
  const provider = r.window.InterpreterAiProvider;
  const response = await provider.request([{ role: 'user', content: 'Hello' }], { stream: true });
  const parts = [];
  await assert.rejects(async () => {
    for await (const part of provider.streamParts(response)) parts.push(part.text);
  }, /could not be reached/);
  assert.deepStrictEqual(parts, ['Partial answer']);
  assert.strictEqual(native.requests.length, 1, 'Do not regenerate after partial output.');
  assert.strictEqual(r.calls.chat, 0, 'A native error must not trigger a second SDK request.');
}

async function testStalledSdkCannotBlockAndroid() {
  const r = runtime({ signedIn: true, chat: () => new Promise(() => {}) });
  addNative(r, send => send('result', '{"result":{"message":{"content":"Actual transport result"}}}'));
  const provider = r.window.InterpreterAiProvider;
  assert.strictEqual(provider.getState().responseVerified, false, 'A saved token is not a successful reply.');
  const pending = provider.request([{ role: 'user', content: 'Hello' }]);
  assert.strictEqual(provider.syncState().state, 'requesting');
  const result = await pending;
  assert.strictEqual(result.message.content, 'Actual transport result');
  assert.strictEqual(r.calls.chat, 0);
  assert.strictEqual(provider.confirmResponse(''), false);
  assert.strictEqual(provider.getState().responseVerified, false);
  provider.confirmResponse(result.message.content);
  assert.strictEqual(provider.getState().responseVerified, true);
  provider.reportFailure(new Error('The next response was empty.'));
  assert.strictEqual(provider.syncState().state, 'error');
  assert.strictEqual(provider.getState().responseVerified, false);
}

async function testAccountFailuresAreNotRetried() {
  const expired = runtime({ signedIn: true, chat: () => Promise.reject({ status: 401, responseText: '{"error":{"code":"token_auth_failed"}}' }) });
  await assert.rejects(expired.window.InterpreterAiProvider.request([], {}), /session has expired/);
  assert.strictEqual(expired.calls.signOut, 1);
  assert.strictEqual(expired.window.InterpreterAiProvider.getState().state, 'needs_auth');
  await expired.window.InterpreterAiProvider.connectFromUserGesture();
  assert.strictEqual(expired.calls.signIn, 1);

  const nativeExpired = runtime({ signedIn: true, chat: () => Promise.reject(failedXhr()) });
  addNative(nativeExpired, send => send('http_error', '{"error":{"code":"token_auth_failed"}}', 401));
  await assert.rejects(nativeExpired.window.InterpreterAiProvider.request([{ role: 'user', content: 'Hello' }], {}), /session has expired/);
  assert.strictEqual(nativeExpired.calls.signOut, 1);

  const noBridge = runtime({ signedIn: true, chat: () => Promise.reject(failedXhr()) });
  await assert.rejects(noBridge.window.InterpreterAiProvider.request([], {}), error =>
    /could not be reached/.test(error.message) && !error.message.includes('[object'));
  assert.strictEqual(noBridge.window.InterpreterAiProvider.syncState().state, 'error', 'A token must not overwrite a failed request with green connected status.');
}

async function testStreamErrorsKeepTheirMeaning() {
  const r = runtime({ signedIn: true });
  const stream = (async function* () { yield { error: { code: 'insufficient_funds', status: 402 } }; })();
  await assert.rejects(async () => {
    for await (const _ of r.window.InterpreterAiProvider.streamParts(stream)) {}
  }, /usage allowance/);
  assert.strictEqual(r.window.InterpreterAiProvider.getState().state, 'error');
}

(async () => {
  await testExplicitAuthentication();
  await testBlockedPopupIsRecoverable();
  await testSdkLoadingRequiresFreshTap();
  await testRequestAndStreamTimeouts();
  await testNativeFirstAndNextTurn();
  await testNativeEvaluationAndCancellation();
  await testNativeStreamFailureIsNotReplayed();
  await testStalledSdkCannotBlockAndroid();
  await testAccountFailuresAreNotRetried();
  await testStreamErrorsKeepTheirMeaning();
  console.log('Interpreter AI provider auth, direct native transport, stalled SDK isolation, verified response state, consecutive turns, evaluation, stream errors and cancellation passed.');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
