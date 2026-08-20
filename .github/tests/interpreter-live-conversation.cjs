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
  return {
    value: '',
    textContent: '',
    title: '',
    classList: classList(),
    ...initial
  };
}

async function sleep(ms) {
  await new Promise(resolve => setTimeout(resolve, ms));
}

function buildConversationContext({ nativeVadAvailable = true } = {}) {
  const nodes = {
    chatInput: element(),
    voiceOrb: element(),
    voiceCallStatus: element(),
    voiceCallLive: element(),
    chatError: element(),
    voiceBtn: element(),
    callVoiceLang: element({ value: 'en-US' }),
    voiceMute: element(),
    voiceEnd: element()
  };

  const calls = {
    stopSpeaking: 0,
    stopNatural: 0,
    startVoice: 0,
    stopVoice: 0,
    startVad: 0,
    stopVad: 0,
    send: 0,
    sentText: ''
  };

  const state = {
    queue: [],
    speaking: true,
    streamComplete: false,
    streamAnswer: 'The economy is growing strongly and employment continues to improve.',
    queuedThrough: 0,
    speechReference: 'The economy is growing strongly and employment continues to improve.',
    bargeArmed: false,
    userBarging: false,
    responseId: 7,
    language: 'en-US'
  };

  const context = {
    console,
    setTimeout,
    clearTimeout,
    Date,
    Intl,
    String,
    Number,
    Math,
    Set,
    RegExp,
    Promise,
    busy: false,
    document: {
      getElementById(id) { return nodes[id] || null; }
    },
    window: {
      __fastInterpreterVoiceV3: true,
      __fastInterpreterVoiceState: state,
      __voiceCallActive: true,
      __voiceCallMuted: false,
      __voiceAutoSpeak: false,
      __voiceOneShot: false,
      InterpreterNative: {
        stopSpeaking() { calls.stopSpeaking += 1; },
        startVoiceInput() { calls.startVoice += 1; },
        stopVoiceInput() { calls.stopVoice += 1; },
        startBargeInDetection() { calls.startVad += 1; return nativeVadAvailable; },
        stopBargeInDetection() { calls.stopVad += 1; },
        setVoiceLanguage() {}
      },
      stopNaturalInterpreterVoice() { calls.stopNatural += 1; },
      resizeComposer() {},
      updateSendState() {},
      startVoiceCall() {},
      endVoiceCall() {},
      __nativeSpeechFinished() {},
      sendChat() {
        calls.send += 1;
        calls.sentText = nodes.chatInput.value;
      }
    },
    hideTyping() {},
    updateSendState() {}
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

  // Speech start must arm the native VAD almost immediately, not SpeechRecognizer under playback.
  context.window.__nativeSpeechStarted();
  await sleep(35);
  assert.ok(calls.startVad >= 1, 'Native barge-in detector was not armed while AI speech was playing.');
  assert.strictEqual(calls.startVoice, 0, 'SpeechRecognizer was incorrectly opened under AI playback.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.monitorMode, 'vad');

  // Native VAD says the user started talking: stop output immediately and transfer the mic to
  // SpeechRecognizer, but do NOT send an AI request before the user finishes.
  context.window.__nativeBargeInDetected();
  assert.strictEqual(calls.stopSpeaking, 1, 'Native voice onset did not stop AI output immediately.');
  assert.strictEqual(calls.stopNatural, 1, 'Any legacy natural voice output was not stopped.');
  assert.strictEqual(calls.send, 0, 'AI request was sent before the interrupted utterance finished.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.phase, 'barge-listening');
  await sleep(45);
  assert.ok(calls.startVoice >= 1, 'SpeechRecognizer did not start after AI output stopped.');

  // Partials update the live transcript only.
  context.window.__voiceInputPartial('actually I mean the other point');
  context.window.__voiceInputPartial('actually I mean the other point about terminology');
  assert.strictEqual(calls.send, 0, 'A partial interruption was sent to the AI.');

  // Exactly one complete final utterance becomes the next turn.
  const finalText = 'actually I mean the other point about terminology and memory';
  context.window.__voiceInputResult(finalText);
  assert.strictEqual(calls.send, 1, 'Final interrupted utterance did not create exactly one AI request.');
  assert.strictEqual(calls.sentText, finalText, 'Complete interrupted utterance was not sent intact.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.phase, 'thinking');
  assert.ok(state.responseId > 7, 'Old AI response was not cancelled during interruption.');

  // Manual mic tap while AI speaks must remain deterministic and immediate.
  state.speaking = true;
  state.bargeArmed = true;
  state.userBarging = false;
  context.window.__interpreterLiveTurnState.phase = 'speaking';
  const stopsBefore = calls.stopSpeaking;
  const startsBefore = calls.startVoice;
  nodes.voiceMute.onclick({ currentTarget: nodes.voiceMute });
  assert.strictEqual(calls.stopSpeaking, stopsBefore + 1, 'Manual interruption did not stop AI immediately.');
  await sleep(45);
  assert.ok(calls.startVoice > startsBefore, 'Manual interruption did not open the recognizer.');
}

async function testRecognizerFallbackEchoGuard() {
  const { context, calls, state } = buildConversationContext({ nativeVadAvailable: false });

  context.window.__nativeSpeechStarted();
  await sleep(35);
  assert.ok(calls.startVad >= 1, 'Native VAD availability was not checked.');
  assert.ok(calls.startVoice >= 1, 'Recognizer fallback was not started when native VAD was unavailable.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.monitorMode, 'recognizer');

  // Obvious speaker echo must not interrupt the AI in fallback mode.
  context.window.__voiceInputPartial('the economy is growing');
  context.window.__voiceInputPartial('the economy is growing strongly');
  assert.strictEqual(calls.stopSpeaking, 0, 'Speaker echo incorrectly interrupted the AI.');
  assert.strictEqual(calls.send, 0, 'Speaker echo created a user turn.');

  // Stable novel speech interrupts quickly and then waits for the final user utterance.
  context.window.__voiceInputPartial('actually I mean');
  await sleep(70);
  context.window.__voiceInputPartial('actually I mean something else');
  assert.strictEqual(calls.stopSpeaking, 1, 'Recognizer fallback failed to detect a real interruption.');
  assert.strictEqual(calls.send, 0, 'Fallback sent a partial interruption too early.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.phase, 'barge-listening');

  context.window.__voiceInputResult('actually I mean something else about terminology');
  assert.strictEqual(calls.send, 1, 'Fallback final interruption did not create one request.');
  assert.ok(state.responseId > 7);
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
      querySelectorAll(selector) {
        return selector === '.message.user .bubble' ? visibleBubbles : [];
      }
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
    Number,
    Math,
    Array,
    String
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

  assert.ok(capturedOptions, 'Wrapped chat request was not forwarded.');
  assert.ok(capturedOptions.max_tokens <= 160, 'Interpreter Live response budget was not shortened.');
  assert.ok(capturedOptions.temperature <= 0.20, 'Interpreter Live low-latency temperature was not applied.');
  assert.strictEqual(capturedOptions.stream, true, 'Streaming was disabled by latency policy.');
  assert.ok(
    capturedRequest[0].content.includes('INTERPRETER LIVE LOW-LATENCY POLICY'),
    'Direct short-answer policy was not added to voice requests.'
  );

  const userTurns = capturedRequest.filter(item => item.role === 'user').map(item => item.content);
  assert.deepStrictEqual(
    userTurns,
    [oldQuestion, newAddition],
    'Previous question was not preserved before the completed interruption/addition.'
  );
}

function testNativeDuplexProxy() {
  let nativeSpeak = 0;
  let legacySpeak = 0;
  let nativeVad = 0;

  const context = {
    window: {
      InterpreterNative: {
        speakText() { legacySpeak += 1; return true; },
        setVoiceLanguage() {}
      },
      InterpreterLiveNative: {
        speakText() { nativeSpeak += 1; return true; },
        stopSpeaking() {},
        startBargeInDetection() { nativeVad += 1; return true; },
        stopBargeInDetection() {}
      }
    },
    Proxy,
    String
  };
  context.window.window = context.window;
  vm.createContext(context);
  const source = fs.readFileSync('app/src/main/assets/interpreter_live_native_duplex.js', 'utf8');
  const result = vm.runInContext(source, context);
  assert.strictEqual(result, 'ready', 'Native duplex proxy did not initialize.');
  assert.strictEqual(context.window.InterpreterNative.speakText('hello', 'en-US'), true);
  assert.strictEqual(nativeSpeak, 1, 'Interpreter Live did not route speech to native low-latency TTS.');
  assert.strictEqual(legacySpeak, 0, 'Legacy remote TTS was still used by Interpreter Live.');
  assert.strictEqual(context.window.InterpreterNative.startBargeInDetection(), true);
  assert.strictEqual(nativeVad, 1, 'Native VAD route was not exposed through InterpreterNative.');
}

(async () => {
  testNativeDuplexProxy();
  await testNativeFullDuplexBargeIn();
  await testRecognizerFallbackEchoGuard();
  await testLowLatencyPolicyAndInterruptedContext();
  console.log('Interpreter Live native duplex interruption, fallback, context and latency tests passed.');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
