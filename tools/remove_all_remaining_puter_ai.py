from pathlib import Path
import re

ROOT = Path('app/src/main')
AI = ROOT / 'java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt'
BOOT = ROOT / 'assets/interpreter_ai_bootstrap.js'
ARABIC = ROOT / 'assets/interpreter_standard_arabic.js'
LATENCY = ROOT / 'assets/interpreter_live_latency.js'


def patch_ai_coach_native_script():
    text = AI.read_text(encoding='utf-8')
    old = '''      const stream = await puter.ai.chat(conversation, {
        model:'qwen/qwen3.8-max',
        stream:true,
        max_tokens:fromVoice ? 420 : 650,
        temperature:0.24
      });

      hideTyping();
      streamRow = messageElement('assistant', '');
      streamRow.dataset.streaming = '1';
      document.getElementById('messages').appendChild(streamRow);
      const bubble = streamRow.querySelector('.bubble');

      for await (const part of stream) {
        if (part?.type === 'error') throw new Error(part?.error?.message || part?.message || 'Streaming request failed.');
        const chunk = typeof part === 'string'
          ? part
          : (typeof part?.text === 'string'
              ? part.text
              : (typeof part?.delta?.content === 'string' ? part.delta.content : ''));
        if (!chunk) continue;
        answer += chunk;
        if (bubble) bubble.textContent = answer;
        requestAnimationFrame(scrollToBottom);
      }
'''
    new = '''      const result = await window.__interpreterAiRequest(conversation, {
        model:'gemini-3.7-flash',
        max_tokens:fromVoice ? 420 : 650
      });

      hideTyping();
      answer = responseText(result);
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise RuntimeError('AiCoachScreen paid chat block was not found')
    text = text.replace("      setStatus('Online · ready', 'ok');", "      setStatus('Free AI · ready', 'ok');")
    AI.write_text(text, encoding='utf-8')


def patch_bootstrap():
    text = BOOT.read_text(encoding='utf-8')
    text = text.replace('puter.ai.chat(', 'window.__interpreterAiRequest(')
    text = text.replace("model:'qwen/qwen3.8-max'", "model:'gemini-3.7-flash'")
    text = text.replace("setConnectionStatus('Online AI · ready · AIV5-LIVE', 'ok');", "setConnectionStatus('Free AI · ready · AIV5-LIVE', 'ok');")
    text = text.replace('// Invoke Puter NOW, before any await. This is the critical Android first-use auth fix.\n      ', '')
    BOOT.write_text(text, encoding='utf-8')


def rewrite_arabic_policy():
    ARABIC.write_text(r'''(() => {
  if (window.__interpreterStandardArabicV3) return 'ready';
  window.__interpreterStandardArabicV3 = true;
  window.__interpreterStandardArabicV2 = true;

  const MSA_INSTRUCTION = `When Arabic is selected or when you answer in Arabic, use Modern Standard Arabic (العربية الفصحى) only. Do not use Moroccan Darija, Maghrebi dialect, Egyptian Arabic, Levantine Arabic, Gulf dialect, or any other colloquial variety unless the user explicitly asks for a dialect. Keep terminology, grammar, pronunciation-oriented text, exercises, model answers, translations, and interpreter-training material in clear professional Modern Standard Arabic.`;

  const relabelArabic = () => {
    document.querySelectorAll('option').forEach(option => {
      const value = String(option.value || '').toLowerCase();
      const text = String(option.textContent || '').trim().toLowerCase();
      if (value === 'ar-ma' || value === 'ar-sa' || value === 'ar' || text === 'arabic' || text === 'العربية') {
        option.textContent = option.closest('#callVoiceLang') ? 'العربية الفصحى' : 'AR · MSA';
      }
    });
  };

  relabelArabic();
  const observer = new MutationObserver(relabelArabic);
  observer.observe(document.documentElement, { childList:true, subtree:true });

  const originalPracticeContext = window.nativePracticeContext;
  if (typeof originalPracticeContext === 'function' && !originalPracticeContext.__msaWrapped) {
    const wrappedContext = () => {
      const base = String(originalPracticeContext() || '');
      return `${base}\n\nARABIC LANGUAGE POLICY:\n${MSA_INSTRUCTION}`;
    };
    wrappedContext.__msaWrapped = true;
    window.nativePracticeContext = wrappedContext;
  }

  // Guard every provider request, including voice paths that may not rebuild practice context.
  const installProviderGuard = () => {
    const current = window.__interpreterAiRequest;
    if (typeof current !== 'function' || current.__msaWrapped) return typeof current === 'function';

    const wrapped = async (...args) => {
      const request = args[0];
      if (Array.isArray(request)) {
        const cloned = request.map(item => item && typeof item === 'object' ? { ...item } : item);
        const system = cloned.find(item => item && item.role === 'system' && typeof item.content === 'string');
        if (system && !system.content.includes('Modern Standard Arabic (العربية الفصحى) only')) {
          system.content += `\n\nARABIC LANGUAGE POLICY:\n${MSA_INSTRUCTION}`;
        } else if (!system) {
          cloned.unshift({ role:'system', content:MSA_INSTRUCTION });
        }
        args[0] = cloned;
      }
      return current(...args);
    };
    wrapped.__msaWrapped = true;
    window.__interpreterAiRequest = wrapped;
    return true;
  };

  if (!installProviderGuard()) {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (installProviderGuard() || attempts >= 80) clearInterval(timer);
    }, 150);
  }

  return 'ready';
})();
''', encoding='utf-8')


def rewrite_latency():
    LATENCY.write_text(r'''(() => {
  if (window.__interpreterLiveLatencyV4) return 'ready';
  if (!(window.__fastInterpreterVoiceV4 || window.__fastInterpreterVoiceV3)) return 'pending';

  const LIVE_MARKER = 'INTERPRETER LIVE LOW-LATENCY POLICY';

  const visibleUserTurns = () => {
    try {
      if (typeof document === 'undefined' || !document.querySelectorAll) return [];
      return Array.from(document.querySelectorAll('.message.user .bubble'))
        .map(node => String(node.innerText || node.textContent || '').trim())
        .filter(Boolean);
    } catch (_) {
      return [];
    }
  };

  const preserveInterruptedContext = request => {
    if (!Array.isArray(request) || request.length < 2) return;
    const visible = visibleUserTurns();
    if (visible.length < 2) return;
    const previousUserText = visible[visible.length - 2];
    if (!previousUserText) return;
    const alreadyPresent = request.some(item =>
      item && item.role === 'user' && String(item.content || '').trim() === previousUserText
    );
    if (alreadyPresent) return;
    let insertAt = request.length - 1;
    while (insertAt > 0 && request[insertAt]?.role !== 'user') insertAt -= 1;
    request.splice(Math.max(1, insertAt), 0, { role:'user', content:previousUserText });
  };

  // Preserve the low-latency/live policy by wrapping the app's provider-agnostic request function.
  const installLatencyGuard = () => {
    const current = window.__interpreterAiRequest;
    if (typeof current !== 'function' || current.__interpreterLiveLatencyWrapped) return typeof current === 'function';

    const wrapped = async (...args) => {
      if (window.__voiceCallActive) {
        const request = args[0];
        if (Array.isArray(request)) {
          const cloned = request.map(item => item && typeof item === 'object' ? { ...item } : item);
          preserveInterruptedContext(cloned);
          const system = cloned.find(item => item && item.role === 'system' && typeof item.content === 'string');
          if (system && !system.content.includes(LIVE_MARKER)) {
            system.content += `\n\n${LIVE_MARKER}: Respond immediately and conversationally. Put the direct answer in the first sentence. Unless the user explicitly asks for detail, use only 1–2 short spoken sentences. No preamble, headings, recap, or filler. If the user interrupts or adds information, treat that complete new utterance as the newest turn, retain the immediately preceding user request as context, and answer the new combined intent directly.`;
          }
          args[0] = cloned;
        }
        const currentOptions = args[1] && typeof args[1] === 'object' ? args[1] : {};
        args[1] = { ...currentOptions, max_tokens:Math.min(Number(currentOptions.max_tokens) || 160, 160) };
      }
      return current(...args);
    };
    wrapped.__interpreterLiveLatencyWrapped = true;
    window.__interpreterAiRequest = wrapped;
    return true;
  };

  if (!installLatencyGuard()) {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (installLatencyGuard() || attempts >= 80) clearInterval(timer);
    }, 150);
  }

  // Android SpeechRecognizer and native TTS are the only speech fallbacks. No paid cloud STT/TTS.
  window.__stopOnlineVoice = () => {};
  window.__onlineVoiceSpeak = async () => false;

  if (!window.__interpreterSmoothComposerV1) {
    window.__interpreterSmoothComposerV1 = true;
    let resizeFrame = 0;
    window.resizeComposer = () => {
      if (resizeFrame) return;
      resizeFrame = requestAnimationFrame(() => {
        resizeFrame = 0;
        const input = document.getElementById('chatInput');
        if (!input) return;
        input.style.height = 'auto';
        const target = Math.min(Math.max(input.scrollHeight, 38), 150);
        input.style.height = target + 'px';
      });
    };

    let scrollFrame = 0;
    let requestedSmooth = false;
    window.scrollToBottom = smoothArg => {
      requestedSmooth = requestedSmooth || smoothArg === true;
      if (scrollFrame) return;
      scrollFrame = requestAnimationFrame(() => {
        scrollFrame = 0;
        const scroll = document.getElementById('chatScroll');
        if (!scroll) return;
        const smooth = requestedSmooth && !window.__voiceCallActive;
        requestedSmooth = false;
        if (smooth) scroll.scrollTo({ top:scroll.scrollHeight, behavior:'smooth' });
        else scroll.scrollTop = scroll.scrollHeight;
      });
    };
  }

  window.__interpreterLiveLatencyV2 = true;
  window.__interpreterLiveLatencyV3 = true;
  window.__interpreterLiveLatencyV4 = true;
  return 'ready';
})();
''', encoding='utf-8')


def verify():
    files = [AI, BOOT, ARABIC, LATENCY]
    bad = []
    for path in files:
        text = path.read_text(encoding='utf-8')
        if re.search(r'puter\.ai\.(chat|speech2txt|txt2speech)', text):
            bad.append(str(path))
    if bad:
        raise RuntimeError('Paid Puter AI references remain: ' + ', '.join(bad))

    ai = AI.read_text(encoding='utf-8')
    boot = BOOT.read_text(encoding='utf-8')
    arabic = ARABIC.read_text(encoding='utf-8')
    latency = LATENCY.read_text(encoding='utf-8')
    checks = {
        'native injected coach uses free provider': 'window.__interpreterAiRequest(conversation' in ai and "model:'gemini-3.7-flash'" in ai,
        'bootstrap uses free provider': 'window.__interpreterAiRequest(conversation' in boot and 'puter.ai.chat' not in boot,
        'Arabic wraps provider abstraction': 'installProviderGuard' in arabic and 'window.__interpreterAiRequest = wrapped' in arabic,
        'latency wraps provider abstraction': 'installLatencyGuard' in latency and 'window.__interpreterAiRequest = wrapped' in latency,
        'latency has no paid STT/TTS': 'speech2txt' not in latency and 'txt2speech' not in latency,
    }
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        raise RuntimeError('Verification failed: ' + ', '.join(failed))


if __name__ == '__main__':
    patch_ai_coach_native_script()
    patch_bootstrap()
    rewrite_arabic_policy()
    rewrite_latency()
    verify()
