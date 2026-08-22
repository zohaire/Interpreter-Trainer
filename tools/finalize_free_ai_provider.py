from pathlib import Path
import re

ROOT = Path('.')
HTML = ROOT / 'app/src/main/assets/interpreter_coach.html'
FAST = ROOT / 'app/src/main/assets/interpreter_fast_voice.js'
BOOT = ROOT / 'app/src/main/assets/interpreter_ai_bootstrap.js'
WORKFLOW = ROOT / '.github/workflows/android-debug-apk.yml'


def patch_html():
    text = HTML.read_text(encoding='utf-8')
    text = text.replace('  <script src="https://js.puter.com/v2/"></script>\n', '')
    text = text.replace(
        'Free AI · Gemini 3.7 Flash · Qwen3.8 Max optional · verify critical source facts',
        'Free AI · Gemini 3.7 Flash · local documents + multimodal media'
    )
    text = text.replace(
        "  const AI_MODEL='qwen/qwen3.8-max',CHAT_MODEL=AI_MODEL,EVALUATION_MODEL=AI_MODEL,HISTORY_KEY='interpreterCoachHistoryV4';let history=loadHistory(),busy=false;const $=id=>document.getElementById(id);",
        "  const HISTORY_KEY='interpreterCoachHistoryV4';let history=loadHistory(),busy=false;const $=id=>document.getElementById(id);"
    )
    HTML.write_text(text, encoding='utf-8')


def patch_fast_voice():
    text = FAST.read_text(encoding='utf-8')
    text = text.replace(
        "    if (ensureConnected() === false) {",
        "    if (!(await ensureConnected())) {",
        1
    )

    pattern = re.compile(
        r"      const stream = await puter\.ai\.chat\(conversation, \{\n"
        r"        model:'qwen/qwen3\.8-max', stream:true, max_tokens:voiceResponse \? 180 : 650, temperature:0\.20\n"
        r"      \}\);\n\n"
        r"      if \(responseId !== state\.responseId\) throw \{ __interrupted:true \};\n"
        r"      hideTyping\(\);\n"
        r"      streamRow = messageElement\('assistant', ''\);\n"
        r"      streamRow\.dataset\.streaming = '1';\n"
        r"      document\.getElementById\('messages'\)\.appendChild\(streamRow\);\n"
        r"      const bubble = streamRow\.querySelector\('\.bubble'\);\n\n"
        r"      for await \(const part of stream\) \{.*?\n      \}\n",
        re.S
    )
    replacement = """      const result = await window.__interpreterAiRequest(conversation, {
        model:'gemini-3.7-flash', max_tokens:voiceResponse ? 180 : 650
      });

      if (responseId !== state.responseId) throw { __interrupted:true };
      hideTyping();
      answer = responseText(result);
      state.streamAnswer = answer;
"""
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1 and "model:'gemini-3.7-flash'" not in text:
        raise RuntimeError('Could not replace paid fast-voice request block')

    text = text.replace("setStatus('Online AI · ready · LIVE5', 'ok');", "setStatus('Free AI · ready · LIVE5', 'ok');")
    FAST.write_text(text, encoding='utf-8')


def workflow_text():
    return r'''name: Build Android Debug APK

on:
  workflow_dispatch:
  push:
    branches: ["main", "master"]
  pull_request:
    branches: ["main", "master"]

jobs:
  free-ai-browser-test:
    name: Verify Free AI and Interpreter Live
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Node 22
        uses: actions/setup-node@v4
        with:
          node-version: '22'

      - name: Validate free AI scripts and routing
        shell: bash
        run: |
          set -euo pipefail
          node --check app/src/main/assets/interpreter_ai_bootstrap.js
          node --check app/src/main/assets/interpreter_fast_voice.js
          node --check app/src/main/assets/interpreter_precise_barge_in.js
          node --check app/src/main/assets/interpreter_live_latency.js
          node --check app/src/main/assets/interpreter_standard_arabic.js
          grep -q '__interpreterAiBootstrapV5' app/src/main/assets/interpreter_ai_bootstrap.js
          grep -q '__fastInterpreterVoiceV4' app/src/main/assets/interpreter_fast_voice.js
          grep -q "FREE_AI_MODEL='gemini-3.7-flash'" app/src/main/assets/interpreter_coach.html
          grep -q 'generativelanguage.googleapis.com' app/src/main/assets/interpreter_coach.html
          grep -q 'getFreeAiApiKey' app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt
          grep -q 'interpreterAttachmentInput' app/src/main/assets/interpreter_coach.html
          grep -q "type:'interpreter_media'" app/src/main/assets/interpreter_coach.html
          grep -q 'LocalDocumentExtractor' app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt
          if grep -R -qE 'puter\.ai\.(chat|speech2txt|txt2speech)' app/src/main; then
            echo 'A paid Puter AI call remains in app/src/main.'
            grep -R -nE 'puter\.ai\.(chat|speech2txt|txt2speech)' app/src/main || true
            exit 1
          fi
          if grep -q 'js.puter.com' app/src/main/assets/interpreter_coach.html; then
            echo 'Puter SDK is still loaded by the Interpreter AI page.'
            exit 1
          fi
          if grep -q 'qwen/qwen3.8-max' app/src/main/assets/interpreter_fast_voice.js; then
            echo 'Interpreter Live still routes to paid Qwen/Puter.'
            exit 1
          fi
          if grep -q 'qwen/qwen3.8-max' app/src/main/assets/interpreter_coach.html; then
            echo 'Interpreter Coach still exposes paid Qwen as its active provider.'
            exit 1
          fi

      - name: Install headless Chromium
        working-directory: /tmp
        run: |
          mkdir -p /tmp/interpreter-ci
          cd /tmp/interpreter-ci
          npm init -y >/dev/null
          npm install --silent playwright@1.55.0
          npx playwright install chromium

      - name: Verify free Gemini chat and Interpreter Live launch
        shell: bash
        run: |
          set -euo pipefail
          python3 -m http.server 8765 --directory "$GITHUB_WORKSPACE/app/src/main/assets" >/tmp/interpreter-ai-http.log 2>&1 &
          SERVER_PID=$!
          trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

          cat > /tmp/interpreter-ci/free-ai-test.cjs <<'NODE'
          const { chromium } = require('playwright');
          const path = require('path');

          (async () => {
            const browser = await chromium.launch({ headless:true });
            const page = await browser.newPage();
            const calls = [];

            await page.route('https://generativelanguage.googleapis.com/**', async route => {
              calls.push({ url:route.request().url(), body:route.request().postDataJSON() });
              await route.fulfill({
                status:200,
                contentType:'application/json',
                body:JSON.stringify({ candidates:[{ content:{ parts:[{ text:'FREE_AI_OK' }] } }] })
              });
            });

            await page.goto('http://127.0.0.1:8765/interpreter_coach.html', {
              waitUntil:'domcontentloaded', timeout:60000
            });

            await page.evaluate(() => {
              window.__voiceStarts = 0;
              window.__interpreterEnhancementsV3 = true;
              window.InterpreterNative = {
                getFreeAiApiKey(){ return 'test-free-gemini-key-1234567890'; },
                setFreeAiApiKey(){ return true; },
                openFreeAiKeyPage(){ return true; },
                getPracticeContext(){ return 'No saved practice context.'; },
                getPreparedAttachment(){ return ''; },
                setVoiceLanguage(){},
                startVoiceInput(){ window.__voiceStarts += 1; },
                stopVoiceInput(){},
                speakText(){ return true; },
                stopSpeaking(){}
              };
            });

            await page.addScriptTag({ path:path.join(process.env.GITHUB_WORKSPACE,'app/src/main/assets/interpreter_ai_bootstrap.js') });
            await page.addScriptTag({ path:path.join(process.env.GITHUB_WORKSPACE,'app/src/main/assets/interpreter_fast_voice.js') });
            await page.addScriptTag({ path:path.join(process.env.GITHUB_WORKSPACE,'app/src/main/assets/interpreter_precise_barge_in.js') });

            const ready = await page.evaluate(() => ({
              bootstrap:Boolean(window.__interpreterAiBootstrapV5),
              voice:Boolean(window.__fastInterpreterVoiceV4),
              live:Boolean(document.getElementById('voiceCallLaunch')),
              overlay:Boolean(document.getElementById('voiceCallOverlay'))
            }));
            if (!ready.bootstrap || !ready.voice || !ready.live || !ready.overlay) throw new Error('Free AI / Interpreter Live controls did not load.');

            await page.fill('#chatInput','Confirm free AI works.');
            await page.click('#sendBtn');
            await page.waitForFunction(() => [...document.querySelectorAll('.assistant .bubble')].some(n => /FREE_AI_OK/.test(n.textContent || '')), null, { timeout:10000 });
            if (calls.length !== 1) throw new Error(`Expected one Gemini API request, got ${calls.length}.`);
            if (!/gemini-3\.7-flash:generateContent/.test(calls[0].url)) throw new Error('Wrong free Gemini endpoint.');
            if (!calls[0].body?.contents?.length) throw new Error('Gemini request did not contain conversation content.');

            await page.click('#voiceCallLaunch');
            await page.waitForFunction(() => document.getElementById('voiceCallOverlay')?.classList.contains('active'), null, { timeout:5000 });
            const starts = await page.evaluate(() => window.__voiceStarts);
            if (starts < 1) throw new Error('Interpreter Live did not start native microphone input.');

            await browser.close();
            console.log('Free Gemini chat and Interpreter Live browser test passed.');
          })().catch(err => { console.error(err); process.exit(1); });
          NODE

          cd /tmp/interpreter-ci
          node free-ai-test.cjs

  build:
    name: Test, build and verify signed APK
    needs: free-ai-browser-test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Gradle 8.13
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.13'

      - name: Install stable development signing key
        shell: bash
        run: |
          set -euo pipefail
          KEYSTORE="$RUNNER_TEMP/interpreter-debug.keystore"
          base64 --decode .github/interpreter-debug.keystore.b64 > "$KEYSTORE"
          chmod 600 "$KEYSTORE"
          keytool -list -keystore "$KEYSTORE" -storepass android -alias androiddebugkey >/dev/null
          echo "Stable development signing key installed."

      - name: Guard app architecture
        shell: bash
        run: |
          set -euo pipefail
          test -f app/src/main/assets/interpreter_coach.html
          test -f app/src/main/assets/interpreter_ai_bootstrap.js
          test -f app/src/main/assets/interpreter_fast_voice.js
          test -f app/src/main/assets/sign_language_emulator.html
          test -f app/src/main/java/com/interpretertrainer/app/ui/screens/SignLanguageScreen.kt
          test -f app/src/main/java/com/interpretertrainer/app/ui/screens/LocalDocumentExtractor.kt
          grep -q "FREE_AI_MODEL='gemini-3.7-flash'" app/src/main/assets/interpreter_coach.html
          grep -q 'getFreeAiApiKey' app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt
          grep -q 'OpenMultipleDocuments' app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt
          grep -q 'OpenMultipleDocuments' app/src/main/java/com/interpretertrainer/app/ui/screens/SignLanguageScreen.kt
          grep -q 'SIGN_LANGUAGE' app/src/main/java/com/interpretertrainer/app/ui/InterpreterTrainerApp.kt
          grep -q 'MicrophoneSessionCoordinator' app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt
          grep -q 'NaturalAndroidVoice' app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt
          if grep -R -qE 'puter\.ai\.(chat|speech2txt|txt2speech)' app/src/main; then
            echo 'Paid Puter AI routing remains.'
            exit 1
          fi

      - name: Run unit tests
        run: gradle --no-daemon :app:testDebugUnitTest

      - name: Build debug APK
        env:
          INTERPRETER_VERSION_CODE: ${{ github.run_number }}
          INTERPRETER_DEBUG_KEYSTORE: ${{ runner.temp }}/interpreter-debug.keystore
        run: gradle --no-daemon :app:assembleDebug

      - name: Verify APK and signing certificate
        shell: bash
        run: |
          set -euo pipefail
          APK=app/build/outputs/apk/debug/app-debug.apk
          test -s "$APK"
          APKSIGNER=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -1)
          test -n "$APKSIGNER"
          "$APKSIGNER" verify --verbose --print-certs "$APK"

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: interpreter-trainer-debug-apk-gemini37-${{ github.sha }}
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14
'''


def verify():
    html = HTML.read_text(encoding='utf-8')
    fast = FAST.read_text(encoding='utf-8')
    boot = BOOT.read_text(encoding='utf-8')
    wf = WORKFLOW.read_text(encoding='utf-8')
    checks = {
        'no Puter SDK': 'js.puter.com' not in html,
        'no Qwen core': 'qwen/qwen3.8-max' not in html,
        'free Gemini transport': "FREE_AI_MODEL='gemini-3.7-flash'" in html,
        'fast voice free transport': 'window.__interpreterAiRequest' in fast and "model:'gemini-3.7-flash'" in fast,
        'fast voice no paid route': 'puter.ai.chat' not in fast and 'qwen/qwen3.8-max' not in fast,
        'fast voice awaits free setup': 'if (!(await ensureConnected()))' in fast,
        'bootstrap free provider': 'freeProviderReady' in boot,
        'new CI': 'free-ai-browser-test:' in wf and 'generativelanguage.googleapis.com' in wf,
    }
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        raise RuntimeError('Verification failed: ' + ', '.join(failed))


if __name__ == '__main__':
    patch_html()
    patch_fast_voice()
    WORKFLOW.write_text(workflow_text(), encoding='utf-8')
    verify()
