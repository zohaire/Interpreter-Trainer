const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/interpreter_professional_voice.js', 'utf8');

const runVoice = async ({ rejectNeural = false } = {}) => {
  const calls = [];
  let nativeFallback = 0;
  let finished = 0;
  const audio = {
    pause() {},
    play() {
      this.onplay?.();
      queueMicrotask(() => this.onended?.());
      return Promise.resolve();
    }
  };
  const selector = { value: '', setAttribute() {} };
  const language = {};
  const callTop = { appendChild() {} };
  const document = {
    head: { appendChild() {} },
    createElement(tag) {
      return tag === 'select' ? selector : { id: '', textContent: '', appendChild() {} };
    },
    getElementById(id) {
      if (id === 'callVoiceLang') return language;
      if (id === 'voiceProfile') return selector.value ? selector : null;
      return null;
    },
    querySelector(query) {
      if (query === '.voice-call-top') return callTop;
      return null;
    }
  };
  const window = {
    InterpreterNative: {
      speakText() {
        nativeFallback += 1;
        return true;
      }
    },
    puter: { ai: { txt2speech: async (_text, options) => {
      calls.push(options);
      if (rejectNeural) throw new Error('offline');
      return audio;
    } } },
    __nativeSpeechFinished() { finished += 1; }
  };
  const context = {
    window,
    document,
    navigator: { onLine: true },
    localStorage: { getItem: () => 'studio', setItem() {} },
    setInterval(callback) { queueMicrotask(callback); return 1; },
    clearInterval() {},
    setTimeout,
    clearTimeout,
    queueMicrotask
  };
  vm.runInNewContext(source, context);
  assert.equal(window.__professionalVoiceSpeak('A natural test response.', 'en-US'), true);
  await new Promise(resolve => setImmediate(resolve));
  return { calls, nativeFallback, finished };
};

(async () => {
  const neural = await runVoice();
  assert.equal(neural.calls[0].model, 'gpt-4o-mini-tts');
  assert.equal(neural.calls[0].voice, 'marin');
  assert.equal(neural.calls[0].response_format, 'wav');
  assert.match(neural.calls[0].instructions, /natural contemporary English/i);
  assert.equal(neural.nativeFallback, 0);
  assert.equal(neural.finished, 1);

  const fallback = await runVoice({ rejectNeural: true });
  assert.equal(fallback.nativeFallback, 1);
  console.log('Professional voice profiles, low-latency format, playback and native fallback passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
