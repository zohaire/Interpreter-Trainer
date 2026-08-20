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

async function testConversationalBargeIn() {
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

  let stopSpeakingCalls = 0;
  let stopNaturalCalls = 0;
  let startVoiceCalls = 0;
  let stopVoiceCalls = 0;
  let sendCalls = 0;
  let sentText = '';

  const state = {
    queue: [],
    speaking: true,
    streamComplete: false,
    streamAnswer: 'The economy is growing strongly and employment continues to improve.',
    queuedThrough: 0,
    speechReference: 'The economy is growing strongly and employment continues to improve.',
    bargeArmed: true,
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
        stopSpeaking() { stopSpeakingCalls += 1; },
        startVoiceInput() { startVoiceCalls += 1; },
        stopVoiceInput() { stopVoiceCalls += 1; },
        setVoiceLanguage() {}
      },
      stopNaturalInterpreterVoice() { stopNaturalCalls += 1; },
      resizeComposer() {},
      updateSendState() {},
      startVoiceCall() {},
      endVoiceCall() {},
      __nativeSpeechFinished() {},
      sendChat() {
        sendCalls += 1;
        sentText = nodes.chatInput.value;
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

  // AI speaker echo/noise must never stop the response.
  context.window.__voiceInputPartial('the economy is growing');
  context.window.__voiceInputPartial('the economy is growing strongly');
  assert.strictEqual(stopSpeakingCalls, 0, 'Speaker echo incorrectly interrupted the AI.');
  assert.strictEqual(sendCalls, 0, 'Echo incorrectly created a new user turn.');

  // First real partial is evidence, but must not yet interrupt on a single recognizer update.
  context.window.__voiceInputPartial('actually I mean');
  assert.strictEqual(stopSpeakingCalls, 0, 'One partial result interrupted too early.');
  assert.strictEqual(sendCalls, 0, 'A partial phrase was sent to the AI.');

  // A consistent follow-up partial should stop AI output quickly, but MUST keep listening and not
  // create an AI request yet.
  await sleep(85);
  context.window.__voiceInputPartial('actually I mean the other point');
  assert.strictEqual(stopSpeakingCalls, 1, 'A genuine interruption did not stop AI speech.');
  assert.strictEqual(stopNaturalCalls, 1, 'Natural voice output was not stopped on interruption.');
  assert.strictEqual(sendCalls, 0, 'Interpreter Live answered before the user finished interrupting.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.phase, 'barge-listening');

  // More words while interrupting only update the live transcript. Still no request.
  context.window.__voiceInputPartial('actually I mean the other point about terminology');
  assert.strictEqual(sendCalls, 0, 'A later partial phrase was sent before the final utterance.');

  // Only the final recognition becomes the next turn.
  const finalText = 'actually I mean the other point about terminology and memory';
  context.window.__voiceInputResult(finalText);
  assert.strictEqual(sendCalls, 1, 'Final interrupted utterance did not create exactly one AI request.');
  assert.strictEqual(sentText, finalText, 'The complete interrupted utterance was not sent intact.');
  assert.strictEqual(context.window.__interpreterLiveTurnState.phase, 'thinking');
  assert.ok(state.responseId > 7, 'Old AI response was not cancelled when the user interrupted.');

  // A manual mic tap while AI is talking must be immediate and deterministic.
  state.speaking = true;
  state.bargeArmed = true;
  state.userBarging = false;
  context.window.__interpreterLiveTurnState.phase = 'speaking';
  const stopsBeforeManual = stopSpeakingCalls;
  const startsBeforeManual = startVoiceCalls;
  nodes.voiceMute.onclick({ currentTarget: nodes.voiceMute });
  assert.strictEqual(stopSpeakingCalls, stopsBeforeManual + 1, 'Manual interruption did not stop AI immediately.');
  assert.ok(startVoiceCalls > startsBeforeManual, 'Manual interruption did not open/keep the microphone.');

  assert.ok(stopVoiceCalls >= 0);
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

  // Simulate the exact history hole produced when an older AI answer was interrupted: the request
  // contains only the new addition, while the previous question still exists in the visible chat.
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
    'The previous question was not preserved before the completed interruption/addition.'
  );
}

(async () => {
  await testConversationalBargeIn();
  await testLowLatencyPolicyAndInterruptedContext();
  console.log('Interpreter Live conversational interruption, context and latency tests passed.');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
