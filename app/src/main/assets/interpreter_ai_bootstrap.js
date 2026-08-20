(() => {
  if (window.__interpreterAiBootstrapV4) return 'ready';
  window.__interpreterAiBootstrapV4 = true;
  window.__interpreterAiCoreVersion = 'AIV4';

  const byId = id => document.getElementById(id);

  const setConnectionStatus = (text, state = 'idle') => {
    const statusText = byId('statusText');
    const statusDot = byId('statusDot');
    if (statusText) statusText.textContent = text;
    if (statusDot) {
      statusDot.className = 'status-dot' + (state === 'ok' ? ' ok' : state === 'bad' ? ' bad' : '');
    }
  };

  const setError = message => {
    const node = byId('chatError');
    if (node) node.textContent = message || '';
  };

  const sdkReady = () => Boolean(window.puter?.ai?.chat);

  // Deliberately synchronous: the first puter.ai.chat() invocation must still happen inside the
  // original user action so Android WebView retains transient activation for first-use auth.
  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV4', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!sdkReady()) {
      setConnectionStatus('AI service unavailable · AIV4', 'bad');
      setError('Interpreter AI could not load its online service. Check your connection and try again.');
      return false;
    }

    setConnectionStatus('Online AI · ready · AIV4', 'ok');
    setError('');
    return true;
  };

  window.connectAi = providerReady;
  window.ensureConnected = providerReady;
  window.__connectInterpreterAi = providerReady;

  // Activation-safe fallback chat. The faster voice layer replaces this later when available, but
  // keeping the base path safe means chat still works even if an optional voice patch fails.
  window.sendChat = async function(fromVoice = false) {
    if (typeof busy !== 'undefined' && busy) return;
    const input = byId('chatInput');
    const text = input?.value?.trim() || '';
    if (!text) return;

    setError('');
    addMessage('user', text);
    input.value = '';
    resizeComposer();
    updateSendState();

    if (providerReady() === false) return;

    busy = true;
    updateSendState();
    showTyping();

    try {
      const system = `You are Interpreter AI, a fast professional coach for interpreters working especially in Arabic, English and French. Respond naturally and directly.\n\n${nativePracticeContext()}`;
      const conversation = [{ role:'system', content:system }, ...history.slice(-8), { role:'user', content:text }];

      // Invoke Puter NOW, before any await. This is the critical Android first-use auth fix.
      const request = puter.ai.chat(conversation, {
        model:'qwen/qwen3.6-27b',
        max_tokens: fromVoice ? 320 : 650,
        temperature:0.24
      });
      const response = await request;
      const answer = responseText(response);
      if (!answer) throw new Error('The AI returned an empty response.');

      history.push({ role:'user', content:text }, { role:'assistant', content:answer });
      history = history.slice(-20);
      saveHistory();
      hideTyping();
      addMessage('assistant', answer);
      setConnectionStatus('Online AI · ready · AIV4', 'ok');
    } catch (error) {
      hideTyping();
      const message = error?.msg || error?.message || String(error);
      setError('Interpreter AI could not answer: ' + message);
      setConnectionStatus('Request failed · AIV4', 'bad');
    } finally {
      busy = false;
      updateSendState();
    }
  };

  // The Evaluate tab can be the user's first AI action, so it needs the same no-await-before-chat
  // rule instead of relying on chat having authenticated earlier.
  window.evaluatePerformance = async function() {
    if (typeof busy !== 'undefined' && busy) return;
    const source = byId('sourceText')?.value?.trim() || '';
    const trainee = byId('traineeText')?.value?.trim() || '';
    const result = byId('evaluationResult');
    if (!source || !trainee) {
      if (result) result.innerHTML = '<div class="result-card error-box">Add both the source and your interpretation first.</div>';
      return;
    }
    if (providerReady() === false) return;

    busy = true;
    const evaluateButton = byId('evaluateBtn');
    if (evaluateButton) evaluateButton.disabled = true;
    if (result) result.innerHTML = '<div class="result-card"><div class="typing"><span></span><span></span><span></span></div></div>';

    try {
      const prompt = `Mode: ${byId('mode')?.value || 'not specified'}\nDirection: ${byId('languages')?.value || 'not specified'}\nSource duration: ${byId('sourceSeconds')?.value || 'not provided'}\nTrainee duration: ${byId('traineeSeconds')?.value || 'not provided'}\n\nSOURCE:\n${source}\n\nTRAINEE:\n${trainee}\n\nEvaluate meaning transfer, omissions, additions, numbers, names, terminology, register, fluency and give three concrete drills.`;

      // Same activation rule: create the cloud request before the first await.
      const request = puter.ai.chat([
        { role:'system', content:'You are a rigorous professional interpreter-performance evaluator. Do not invent evidence.' },
        { role:'user', content:prompt }
      ], {
        model:'qwen/qwen3.6-27b',
        max_tokens:1400,
        temperature:0.15
      });
      const response = await request;
      const answer = responseText(response);
      if (!answer) throw new Error('The AI returned an empty evaluation.');

      if (result) {
        result.innerHTML = '';
        const card = document.createElement('div');
        card.className = 'result-card';
        const head = document.createElement('div');
        head.className = 'result-head';
        head.innerHTML = '<div class="mini-mark">IT</div><span>Performance feedback</span>';
        const body = document.createElement('div');
        body.className = 'bubble';
        renderRichText(body, answer);
        card.append(head, body);
        result.appendChild(card);
      }
      setConnectionStatus('Online AI · ready · AIV4', 'ok');
    } catch (error) {
      const message = error?.msg || error?.message || String(error);
      if (result) result.innerHTML = '<div class="result-card error-box">Evaluation failed: ' + escapeHtml(message) + '</div>';
      setConnectionStatus('Request failed · AIV4', 'bad');
    } finally {
      busy = false;
      if (evaluateButton) evaluateButton.disabled = false;
    }
  };

  window.__baseAiActivationSafeV4 = true;

  const refresh = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV4', 'bad');
      return;
    }
    if (!sdkReady()) {
      setConnectionStatus('Loading AI service · AIV4');
      return;
    }
    setConnectionStatus('Online AI · ready · AIV4', 'ok');
    setError('');
  };

  let refreshAttempts = 0;
  const refreshTimer = setInterval(() => {
    refresh();
    refreshAttempts += 1;
    if (sdkReady() || refreshAttempts >= 40) clearInterval(refreshTimer);
  }, 250);

  refresh();
  window.addEventListener('online', refresh);
  window.addEventListener('offline', () => setConnectionStatus('No internet connection · AIV4', 'bad'));

  return 'ready';
})();