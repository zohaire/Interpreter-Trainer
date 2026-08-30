const { chromium } = require('playwright');

const coachUrl = process.env.INTERPRETER_COACH_URL ||
  'http://127.0.0.1:8765/interpreter_coach.html';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Emulate the reported device condition: the remote AI SDK request remains unresolved while
  // the bundled coach interface is opening. The local document must still become interactive.
  await page.route('https://js.puter.com/v2/**', async route => {
    await new Promise(resolve => setTimeout(resolve, 4000));
    await route.abort('timedout').catch(() => {});
  });

  const startedAt = Date.now();
  await page.goto(coachUrl, {
    waitUntil: 'domcontentloaded',
    timeout: 2500
  });
  const startupMillis = Date.now() - startedAt;

  const result = await page.evaluate(() => {
    const sdkScript = Array.from(document.scripts)
      .find(script => script.src === 'https://js.puter.com/v2/');
    return {
      title: document.querySelector('.welcome h1')?.textContent || '',
      chatPane: Boolean(document.getElementById('chatPane')),
      composer: Boolean(document.getElementById('chatInput')),
      loader: typeof window.__loadInterpreterAiSdk === 'function',
      sdkScriptAsync: sdkScript?.async === true,
      status: document.getElementById('statusText')?.textContent || ''
    };
  });

  if (!result.chatPane || !result.composer || !/Train like a professional interpreter/i.test(result.title)) {
    throw new Error('The local Interpreter AI interface did not render before the network SDK.');
  }
  if (!result.loader || !result.sdkScriptAsync) {
    throw new Error('The Puter SDK is not using the non-blocking startup loader.');
  }
  if (startupMillis >= 2500) {
    throw new Error(`Local coach startup took too long (${startupMillis}ms).`);
  }

  await page.close();
  await browser.close();
  console.log(`Non-blocking coach startup passed in ${startupMillis}ms (${result.status}).`);
})().catch(error => {
  console.error(error);
  process.exit(1);
});
