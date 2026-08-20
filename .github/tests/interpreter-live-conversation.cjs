const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

function classList() {
  const values = new Set();
  return {
    add: (...items) => items.forEach(item => values.add(item)),
    remove: (...items) => items.forEach(item => values.delete(item)),
    toggle: (item, force) => {
      if (force === true) values.add(item);
      else if (force === false) values.delete(item);
      else if (values.has(item)) values.delete(item);
      else values.add(item);
    },
    contains: item => values.has(item)
  };
}

function element(initial = {}) {
  return { value: '', textContent: '', title: '', classList: classList(), ...initial };
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function testDirectBridgeArchitecture() {
  const fast = fs.readFileSync('app/src/main/assets/interpreter_fast_voice.js', 'utf8');
  const precise = fs.readFileSync('app/src/main/assets/interpreter_precise_barge_in.js', 'utf8');
  const duplex = fs.readFileSync('app/src/main/assets/interpreter_live_native_duplex.js', 'utf8');

  assert.ok(fast.includes('window.InterpreterLiveNative'), 'Fast voice does not reference InterpreterLiveNative directly.');
  assert.ok(fast.includes('live.speakText'), 'Live speech is not called directly from the dedicated native bridge.');
  assert.ok(precise.includes('window.InterpreterLiveNative'), 'Interruption layer does not reference the dedicated bridge.');
  assert.ok(precise.includes('live?.startBargeInDetection'), 'Native barge detector is not called directly.');
  assert.ok(!duplex.includes('new Proxy'), 'Unreliable WebView JavascriptInterface Proxy routing returned.');
  assert.ok(!duplex.includes('window.InterpreterNative ='), 'InterpreterNative is still being replaced.');
}

function buildConversationContext({ nativeVadAvailable = true } = {}) {
  const nodes = {
    chatInput: element(), voiceOrb: element(), voiceCallStatus: element(), voiceCallLive: element(),
    chatError: element(), voiceBtn: element(), callVoiceLang: element({ value: 'en-US' }),
    voiceMute: element(), voiceEnd: element()
  };

  const calls = {
    liveStopSpeaking: 0, legacyStopSpeaking: 0, stopNatural: 0,
    startVoice: 0, stopVoice: 0, startVad: 0, stopVad: 0,
    send: 0, sentText: ''
  };

  const state = {
    queue: [], speaking: true, streamComplete: false,
    streamAnswer: 'The economy is growing strongly and employment continues to improve.',
    queuedThrough: 0,
    speechReference: 'The economy is growing strongly and employment continues to improve.',
    bargeArmed: false, userBarging: false, responseId: 7, language: 'en-US'
  };

  const context = {
    console, setTimeout, clearTimeout, Date, Intl, String, Number, Math, Set, RegExp, Promise,
    busy: false,
    document: { getElementById(id) { return nodes[id] || null; } },
    window: {
      __fastInterpreterVoiceV5: true,
      __fastInterpreterVoiceV4: true,
      __fastInterpreterVoiceV3: true,
      __fastInterpreterVoiceState: state,
      __voiceCallActive: true,
      __voiceCallMuted: false,
      __voiceAutoSpeak: false,
      __voiceOneShot: false,
      InterpreterNative: {
        stopSpeaking() { calls.legacyStopSpeaking += 1; },
        startVoiceInput() { calls.startVoice += 1; },
        stopVoiceInput() { calls.stopVoice += 1; },
        setVoiceLanguage() {}
      },
      InterpreterLiveNative: {
        stopSpeaking() { calls.liveStopSpeaking += 1; },
        startBargeInDetection() { calls.startVad += 1; return nativeVadAvailable; },
        stopBargeInDetection() { calls.stopVad += 1; }
      },
      stopNaturalInterpreterVoice() { calls.stopNatural += 1; },
      resizeComposer() {}, updateSendState() {}, startVoiceCall() {}, endVoiceCall() {},
      __nativeSpeechFinished() {},
      sendChat() { calls.send += 1; calls.sentText = nodes.chatInput.value; }
    },
    hideTyping() {}, updateSendState() {}
  };
  context.window.window = context.window;
  context.window.document = context.document;
  context.window.setTimeout = setTimeout;
  context.window.clearTimeout = clearTimeout;
  context.window.Date = Date;
  context.window.Set = Set;

  vm.createContext(context);
  const source = fs.readFileSync('app/src/main/assets/interpreter_precise_barge_in.js', 'utf8');
  const result = vm.runInContext(source, context);
  assert.strictEqual(result, 'ready', 'Conversational interruption patch did not initialize.');
  return { context, nodes, calls, state };
}

async function testNativeFullDuplexBargeIn() {
  const { context, nodes, calls, state } = buildConversationContext({ nativeVadAvailable: true });

  context.window.__nativeSpeechStarted();
  await sleep(25);
  assert.ok(calls.startVad >= 1, 'Dedicated native barge detector was not armed during AI speech.');
  assert.strictEqual(calls.startVoice, 0, 'SpeechRecognizer was opened under playback instead of native VAD.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.monitorMode, 'vad');

  context.window.__nativeBargeInDetected();
  assert.strictEqual(calls.liveStopSpeaking, 1, 'Native live TTS was not stopped immediately on voice onset.');
  assert.strictEqual(calls.send, 0, 'A request was sent before the interrupted utterance finished.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.phase, 'barge-listening');
  await sleep(35);
  assert.ok(calls.startVoice >= 1, 'SpeechRecognizer did not open after AI output stopped.');

  context.window.__voiceInputPartial('actually I mean something else');
  context.window.__voiceInputPartial('actually I mean something else about terminology');
  assert.strictEqual(calls.send, 0, 'A partial interruption was sent prematurely.');

  const finalText = 'actually I mean something else about terminology and memory';
  context.window.__voiceInputResult(finalText);
  assert.strictEqual(calls.send, 1, 'Completed interruption did not create exactly one request.');
  assert.strictEqual(calls.sentText, finalText, 'Completed interruption was not sent intact.');
  assert.ok(state.responseId > 7, 'Interrupted AI response was not cancelled.');

  state.speaking = true;
  state.bargeArmed = true;
  state.userBarging = false;
  context.window.__interpreterLiveTurnState.phase = 'speaking';
  const beforeStops = calls.liveStopSpeaking;
  const beforeStarts = calls.startVoice;
  nodes.voiceMute.onclick({ currentTarget: nodes.voiceMute });
  assert.strictEqual(calls.liveStopSpeaking, beforeStops + 1, 'Manual interruption did not stop native TTS immediately.');
  await sleep(35);
  assert.ok(calls.startVoice > beforeStarts, 'Manual interruption did not reopen recognition.');
}

async function testRecognizerFallback() {
  const { context, calls } = buildConversationContext({ nativeVadAvailable: false });
  context.window.__nativeSpeechStarted();
  await sleep(25);
  assert.ok(calls.startVad >= 1, 'Native VAD availability was not checked.');
  assert.ok(calls.startVoice >= 1, 'Recognizer fallback did not start when native VAD was unavailable.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.monitorMode, 'recognizer');

  context.window.__voiceInputPartial('the economy is growing');
  context.window.__voiceInputPartial('the economy is growing strongly');
  assert.strictEqual(calls.liveStopSpeaking, 0, 'Speaker echo incorrectly stopped live speech.');

  context.window.__voiceInputPartial('actually I mean');
  await sleep(70);
  context.window.__voiceInputPartial('actually I mean something else');
  assert.strictEqual(calls.liveStopSpeaking, 1, 'Fallback recognizer failed to detect genuine interruption.');
  assert.strictEqual(calls.send, 0, 'Fallback sent a partial interruption prematurely.');
  context.window.__voiceInputResult('actually I mean something else about terminology');
  assert.strictEqual(calls.send, 1, 'Fallback final interruption did not create exactly one request.');
}

async function testLowLatencyPolicyAndInterruptedContext() {
  let capturedOptions = null;
  let capturedRequest = null;
  const oldQuestion = 'What is the difference between consecutive and simultaneous interpreting?';
  const newAddition = 'Actually compare their note-taking requirements instead.';
  const visibleBubbles = [
    { innerText: oldQuestion, textContent: oldQuestion },
    { innerText: newAddition, textContent: newAddition }
  ];

  const context = {
    console,
    document: {
      querySelectorAll(selector) { return selector === '.message.user .bubble' ? visibleBubbles : []; }
    },
    window: {
      __fastInterpreterVoiceV3: true,
      __voiceCallActive: true,
      puter: {
        ai: {
          async chat(request, options) {
            capturedRequest = request;
            capturedOptions = options;
            return { ok: true };
          }
        }
      }
    },
    Number, Math, Array, String
  };
  context.window.window = context.window;
  context.window.document = context.document;
  vm.createContext(context);

  const source = fs.readFileSync('app/src/main/assets/interpreter_live_latency.js', 'utf8');
  const result = vm.runInContext(source, context);
  assert.strictEqual(result, 'ready', 'Low-latency policy did not initialize.');

  const messages = [
    { role: 'system', content: 'You are Interpreter AI.' },
    { role: 'user', content: newAddition }
  ];
  await context.window.puter.ai.chat(messages, { stream: true, max_tokens: 260, temperature: 0.22 });

  assert.ok(capturedOptions.max_tokens <= 160, 'Interpreter Live response budget was not shortened.');
  assert.ok(capturedOptions.temperature <= 0.20, 'Low-latency temperature was not applied.');
  assert.strictEqual(capturedOptions.stream, true, 'Streaming was disabled.');
  assert.ok(capturedRequest[0].content.includes('INTERPRETER LIVE LOW-LATENCY POLICY'));

  const userTurns = capturedRequest.filter(item => item.role === 'user').map(item => item.content);
  assert.deepStrictEqual(userTurns, [oldQuestion, newAddition], 'Interrupted question context was not preserved.');
}

(async () => {
  testDirectBridgeArchitecture();
  await testNativeFullDuplexBargeIn();
  await testRecognizerFallback();
  await testLowLatencyPolicyAndInterruptedContext();
  console.log('Interpreter Live direct-native routing, interruption, fallback, context and latency tests passed.');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
