const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

// This watchdog runs outside Chromium: a renderer stuck in MutationObserver microtasks cannot
// run an in-page timeout. Do not let the original regression hang the entire CI job.
const watchdog = setTimeout(() => {
  console.error('Full Android AI runtime froze or did not finish within 30 seconds.');
  process.exit(1);
}, 30000);

(async () => {
  const root = path.resolve(__dirname, '../..');
  const assets = path.join(root, 'app/src/main/assets');
  const native = fs.readFileSync(path.join(root,
    'app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt'), 'utf8');
  // Derive the list and order from the APK's installer. A manually curated subset previously
  // omitted interpreter_standard_arabic.js and passed while the shipped app froze.
  const assetList = native.match(/scripts = listOf\(([\s\S]*?)\)\.map\(context::readAssetText\)/);
  assert.ok(assetList, 'Could not read Android runtime asset list.');
  const scripts = [...assetList[1].matchAll(/"([^"\n]+\.js)"/g)].map(match => match[1]);
  assert.ok(scripts.includes('interpreter_standard_arabic.js'));
  const enhancement = native.match(/private fun coachEnhancementScript\(\): String = """\n([\s\S]*?)\n"""\.trimIndent\(\)/);
  assert.ok(enhancement, 'Could not read native coach enhancement script.');

  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext();
    // Serve exactly the bundled document on its Android HTTPS origin, without external calls.
    await context.route('**/*', async route => {
      if (route.request().url() === 'https://appassets.androidplatform.net/assets/interpreter_coach.html') {
        return route.fulfill({ contentType: 'text/html', body: fs.readFileSync(path.join(assets, 'interpreter_coach.html'), 'utf8') });
      }
      return route.abort();
    });
    const page = await context.newPage();
    page.setDefaultTimeout(5000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      window.__calls = [];
      window.__signIns = 0;
      window.__signed = false;
      window.__voiceStarts = 0;
      window.InterpreterNative = {
        getPracticeContext: () => 'Interpreter Trainer was created by Zouhair Elachaqi.',
        sendToPractice: () => true,
        setVoiceLanguage() {},
        startVoiceInput() { window.__voiceStarts += 1; },
        stopVoiceInput() {}, speakText: () => false, stopSpeaking() {}
      };
      window.InterpreterLiveNative = {
        speakText: () => false, stopSpeaking() {},
        startBargeInDetection() {}, stopBargeInDetection() {}
      };
      window.puter = {
        auth: {
          isSignedIn: () => window.__signed,
          signIn() {
            window.__signIns += 1;
            window.__signInActivation = navigator.userActivation.isActive;
            window.__signed = true;
            return Promise.resolve({ success: true });
          }
        },
        ai: {
          chat(messages, options) {
            if (window.__simulateXhrFailure) {
              window.__xhrFailures = (window.__xhrFailures || 0) + 1;
              return Promise.reject({ status: 0, readyState: 4, responseText: '', getResponseHeader() { return null; },
                toString() { return '[object XMLHttpRequest]'; } });
            }
            window.__calls.push(JSON.parse(JSON.stringify({ messages, options })));
            const answer = 'ANSWER_' + window.__calls.length;
            if (!options.stream) return Promise.resolve({ message: { content: answer } });
            return (async function* () {
              yield { text: answer.slice(0, 4) };
              await new Promise(resolve => setTimeout(resolve, 15));
              yield { text: answer.slice(4) };
            })();
          },
          txt2speech: () => Promise.reject(new Error('Speech is stubbed in this UI test.'))
        }
      };
    });
    await page.goto('https://appassets.androidplatform.net/assets/interpreter_coach.html', { waitUntil: 'load' });
    await page.evaluate(source => (0, eval)(source), enhancement[1]);
    await page.evaluate(sources => {
      for (const source of sources) (0, eval)(source);
    }, scripts.map(name => fs.readFileSync(path.join(assets, name), 'utf8')));

    // Let timers and paints run after DOM changes, including dynamically added language controls.
    await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 30)));
    assert.equal(await page.locator('#voiceLang option[value="ar-MA"]').textContent(), 'AR · MSA');
    assert.equal(await page.locator('#callVoiceLang option[value="ar-MA"]').textContent(), 'العربية الفصحى');
    for (let turn = 1; turn <= 3; turn += 1) {
      await page.fill('#chatInput', `Practice turn ${turn}`);
      await page.click('#sendBtn');
      await page.waitForFunction(n => Array.from(document.querySelectorAll('.message.assistant .bubble'))
        .some(node => node.textContent.includes('ANSWER_' + n)), turn);
      await page.waitForFunction(() => !busy);
    }
    const result = await page.evaluate(() => ({
      calls: window.__calls, signIns: window.__signIns,
      activation: window.__signInActivation, error: document.getElementById('chatError').textContent
    }));
    assert.equal(result.signIns, 1);
    assert.equal(result.activation, true);
    assert.equal(result.error, '');
    assert.equal(result.calls.length, 3);
    assert.ok(result.calls[2].messages.some(message => message.content === 'ANSWER_1'));
    assert.match(result.calls[2].messages[0].content, /Modern Standard Arabic/);
    assert.equal(result.calls[2].options.model, 'qwen/qwen3.8-27b:free');

    await page.click('#evalTab');
    await page.fill('#sourceText', 'Negotiations resume tomorrow.');
    await page.fill('#traineeText', 'تُستأنف المفاوضات غدًا.');
    await page.click('#evaluateBtn');
    await page.waitForFunction(() => document.getElementById('evaluationResult').textContent.includes('ANSWER_4'));
    await page.click('#chatTab');
    await page.click('#voiceCallLaunch');
    await page.waitForFunction(() => window.__voiceStarts > 0);
    await page.click('#voiceEnd');
    assert.equal(await page.locator('#voiceCallOverlay').evaluate(node => node.classList.contains('active')), false);

    // Reproduce the screenshot: authentication works but the SDK rejects an XMLHttpRequest.
    // The actual page must recover through the Android bridge and keep streaming/context.
    await page.evaluate(() => {
      window.__simulateXhrFailure = true;
      window.__nativeRequests = [];
      window.puter.authToken = 'test-session-token';
      window.InterpreterAiNetwork = {
        startChat(id, body) {
          window.__nativeRequests.push(JSON.parse(body));
          const answer = 'NATIVE_RECOVERED_' + window.__nativeRequests.length;
          setTimeout(() => {
            const send = (kind, data = '') => window.InterpreterAiProvider.onNativeEvent(id, { kind, data, status: 200 });
            send('started');
            send('part', JSON.stringify({ text: answer.slice(0, 5) }));
            setTimeout(() => { send('part', JSON.stringify({ text: answer.slice(5) })); send('done'); }, 15);
          }, 0);
          return true;
        },
        cancelChat() {}
      };
    });
    for (let turn = 1; turn <= 2; turn++) {
      await page.fill('#chatInput', `Recover message ${turn}`);
      await page.click('#sendBtn');
      await page.waitForFunction(n => Array.from(document.querySelectorAll('.message.assistant .bubble'))
        .some(node => node.textContent.includes('NATIVE_RECOVERED_' + n)), turn);
      await page.waitForFunction(() => !busy);
    }
    const recovered = await page.evaluate(() => ({ requests: window.__nativeRequests,
      failures: window.__xhrFailures, error: document.getElementById('chatError').textContent }));
    assert.equal(recovered.failures, 1);
    assert.equal(recovered.error, '');
    assert.ok(recovered.requests[1].args.messages.some(message => message.content === 'NATIVE_RECOVERED_1'));
    assert.equal(recovered.requests[1].args.stream, true);
    assert.equal(recovered.requests[1].args.model, 'qwen/qwen3.8-27b:free');
    assert.deepEqual(errors, []);
    console.log(`Full Android runtime (${scripts.length} assets) passed: responsive UI, chat context, evaluation, voice controls and two streamed replies after XMLHttpRequest failure.`);
  } finally {
    await browser.close();
  }
})().then(() => clearTimeout(watchdog)).catch(error => {
  console.error(error);
  process.exit(1);
});
