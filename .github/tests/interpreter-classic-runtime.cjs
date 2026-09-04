const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { chromium } = require('playwright');

// Exercise the restored Android document and its actual injected script together.
// The SDK boundary is simulated here; passing this test does not prove live provider access.
const watchdog = setTimeout(() => {
  console.error('Classic coach runtime froze or exceeded 45 seconds.');
  process.exit(1);
}, 45000);

(async () => {
  const html = fs.readFileSync('app/src/main/assets/interpreter_coach.html', 'utf8');
  const kotlin = fs.readFileSync('app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt', 'utf8');
  const match = kotlin.match(/private fun coachEnhancementScript\(\): String = """\n([\s\S]*?)\n"""\.trimIndent\(\)/);
  assert.ok(match, 'Android must inject the classic coach script.');
  const enhancement = match[1].replaceAll("${'$'}", '$');
  new vm.Script(enhancement);
  for (const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
    new vm.Script(script[1]);
  }

  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      window.__requests = [];
      window.__practice = [];
      window.__voiceStarts = 0;
      window.__voiceStops = 0;
      window.InterpreterNative = {
        getPracticeContext: () => 'Saved practice context.',
        sendToPractice(mode, text) { window.__practice.push({ mode, text }); return true; },
        setVoiceLanguage() {},
        startVoiceInput() { window.__voiceStarts++; },
        stopVoiceInput() { window.__voiceStops++; },
        speakText() { return false; },
        stopSpeaking() {}
      };
    });
    await page.route('https://interpreter-trainer.app/**', route => route.fulfill({ contentType: 'text/html', body: html }));
    await page.route('https://js.puter.com/v2/**', route => route.fulfill({
      contentType: 'application/javascript',
      body: `window.puter = {
        auth: {
          isSignedIn(){ return Boolean(window.__signed); },
          signIn(){ window.__signed = true; return Promise.resolve({success:true}); }
        },
        ai: {
          chat(messages, options){
            window.__requests.push({messages:JSON.parse(JSON.stringify(messages)),options});
            if(window.__failNext){window.__failNext=false;return Promise.reject(new Error('Provider quota exhausted'));}
            const text = 'Classic coach reply ' + window.__requests.length;
            if(options.stream) return Promise.resolve((async function*(){yield {text:text.slice(0,8)};yield {text:text.slice(8)};})());
            return Promise.resolve({message:{content:text}});
          },
          txt2speech(){return Promise.reject(new Error('Use Android voice fallback'));}
        }
      };`
    }));
    await page.goto('https://interpreter-trainer.app/', { waitUntil: 'load' });
    await page.addScriptTag({ content: enhancement });
    await page.addScriptTag({ path: 'app/src/main/assets/interpreter_standard_arabic.js' });
    await page.waitForFunction(() => Boolean(document.getElementById('voiceCallLaunch')));

    for (const [index, message] of ['Give me a diplomacy exercise.', 'Explain the key terminology.'].entries()) {
      await page.fill('#chatInput', message);
      await page.click('#sendBtn');
      await page.waitForFunction(n => document.querySelectorAll('.message.assistant').length === n, index + 1);
      await page.waitForFunction(() => !busy);
      assert.match(await page.locator('.message.assistant').last().innerText(), /Classic coach reply/);
    }
    let requests = await page.evaluate(() => window.__requests);
    assert.equal(requests.length, 2);
    assert.equal(requests[1].messages.filter(m => m.role === 'assistant').length, 1, 'Second turn retains the first answer.');
    assert.ok(requests.every(r => r.options.model === 'qwen/qwen3.6-27b' && r.options.stream));
    assert.match(requests[0].messages[0].content, /Modern Standard Arabic/);
    assert.equal(await page.locator('#chatError').innerText(), '');

    await page.locator('.message.assistant').last().getByRole('button', { name: 'Use in Shadowing', exact: true }).click();
    assert.equal((await page.evaluate(() => window.__practice))[0].mode, 'SHADOWING');

    await page.click('#evalTab');
    await page.fill('#sourceText', 'Trade increased by ten percent.');
    await page.fill('#traineeText', 'ارتفعت التجارة بنسبة عشرة في المائة.');
    await page.click('#evaluateBtn');
    await page.waitForFunction(() => document.getElementById('evaluationResult').textContent.includes('Classic coach reply'));
    requests = await page.evaluate(() => window.__requests);
    assert.equal(requests[2].options.model, 'qwen/qwen3.6-27b');
    assert.match(requests[2].messages[0].content, /Modern Standard Arabic/);

    await page.click('#chatTab');
    await page.evaluate(() => { window.__failNext = true; });
    await page.fill('#chatInput', 'Show provider failure.');
    await page.click('#sendBtn');
    await page.waitForFunction(() => document.getElementById('chatError').textContent.includes('Provider quota exhausted'));
    await page.waitForFunction(() => !busy);
    assert.match(await page.locator('#statusText').innerText(), /Request failed/);
    await page.fill('#chatInput', 'Retry after provider recovery.');
    await page.click('#sendBtn');
    await page.waitForFunction(() => !busy && document.querySelectorAll('.message.assistant').length === 3);
    assert.equal(await page.locator('#chatError').innerText(), '');

    await page.click('#voiceCallLaunch');
    await page.waitForFunction(() => window.__voiceStarts > 0 && document.getElementById('voiceCallOverlay').classList.contains('active'));
    await page.click('#voiceEnd');
    assert.ok(await page.evaluate(() => window.__voiceStops > 0));
    assert.equal(await page.locator('#voiceCallOverlay').evaluate(n => n.classList.contains('active')), false);
    await page.waitForTimeout(100);
    assert.deepEqual(errors, []);
    console.log('Classic coach: two-turn streaming, context, evaluation, visible provider errors/recovery, practice transfer, voice controls and MSA passed (simulated SDK).');
  } finally {
    await browser.close();
  }
})().then(() => clearTimeout(watchdog)).catch(error => {
  clearTimeout(watchdog);
  console.error(error);
  process.exit(1);
});
