const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { chromium } = require('playwright');

const watchdog = setTimeout(() => {
  console.error('Inline generator runtime froze or exceeded 30 seconds.');
  process.exit(1);
}, 30000);

(async () => {
  const html = fs.readFileSync('app/src/main/assets/interpreter_inline_generator.html', 'utf8');
  for (const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
    if (script[1].trim()) new vm.Script(script[1]);
  }

  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      window.__requests = [];
      window.__generated = [];
      window.__generatorErrors = [];
      window.__ready = 0;
      window.InlineAiNative = {
        onReady() { window.__ready++; },
        onGenerated(value) { window.__generated.push(value); },
        onError(value) { window.__generatorErrors.push(value); }
      };
    });
    await page.route('https://interpreter-trainer.app/**', route => route.fulfill({ contentType: 'text/html', body: html }));
    await page.route('https://js.puter.com/v2/**', route => route.fulfill({
      contentType: 'application/javascript',
      body: `window.puter = {
        auth: { isSignedIn(){ return Boolean(window.__signed); }, signIn(){ window.__signed = true; return Promise.resolve({success:true}); } },
        ai: { chat(messages, options){
          window.__requests.push({messages:JSON.parse(JSON.stringify(messages)),options});
          if(window.__failNext){window.__failNext=false;return Promise.reject(new Error('Generator quota exhausted'));}
          return Promise.resolve((async function*(){yield {text:'  Generated first paragraph.  \\n\\n\\n'};yield {text:'Second paragraph.  '};})());
        }}
      };`
    }));

    await page.goto('https://interpreter-trainer.app/', { waitUntil: 'load' });
    await page.waitForFunction(() => window.__ready === 1);
    const prompt = 'Create source material for consecutive interpretation practice. Write in French. The learner will interpret it into Arabic (MSA). Topic: diplomacy. Return only the finished source passage.';
    await page.evaluate(p => window.generatePracticeText(p, 600), prompt);
    await page.waitForFunction(() => window.__generated.length === 1);

    const state = await page.evaluate(() => ({ requests: window.__requests, generated: window.__generated, failures: window.__generatorErrors }));
    assert.equal(state.requests.length, 1);
    assert.equal(state.requests[0].options.model, 'gemini-3.1-flash-lite');
    assert.equal(state.requests[0].options.stream, true);
    assert.equal(state.requests[0].options.max_tokens, 600);
    assert.match(state.requests[0].messages[1].content, /consecutive interpretation/);
    assert.match(state.requests[0].messages[1].content, /French/);
    assert.match(state.requests[0].messages[1].content, /Arabic \(MSA\)/);
    assert.match(state.generated[0], /Generated first paragraph/);
    assert.deepEqual(state.failures, []);

    await page.evaluate(() => { window.__failNext = true; window.generatePracticeText('Try another passage.', 360); });
    await page.waitForFunction(() => window.__generated.length === 2);
    const fallbackState = await page.evaluate(() => ({ requests: window.__requests, failures: window.__generatorErrors }));
    assert.equal(fallbackState.requests.length, 3);
    assert.equal(fallbackState.requests[1].options.model, 'gemini-3.1-flash-lite');
    assert.equal(fallbackState.requests[2].options.model, 'qwen/qwen3.6-27b');
    assert.deepEqual(fallbackState.failures, []);
    assert.deepEqual(errors, []);
    console.log('Inline AI generator: auth, fast constrained streaming, fallback and native delivery passed (simulated SDK).');
  } finally {
    await browser.close();
  }
})().then(() => clearTimeout(watchdog)).catch(error => {
  clearTimeout(watchdog);
  console.error(error);
  process.exit(1);
});
