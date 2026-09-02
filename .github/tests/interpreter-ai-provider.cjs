const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync('app/src/main/assets/interpreter_ai_provider.js', 'utf8');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function runtime({ signedIn = false, signIn, chat, sdkInitiallyReady = true } = {}) {
  const calls = { signIn: 0, chat: 0, load: 0 };
  let signed = signedIn;
  const listeners = {};
  const puter = {
    auth: {
      isSignedIn: () => signed,
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

(async () => {
  await testExplicitAuthentication();
  await testBlockedPopupIsRecoverable();
  await testSdkLoadingRequiresFreshTap();
  await testRequestAndStreamTimeouts();
  console.log('Interpreter AI provider authentication, recovery and timeout tests passed.');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
