from pathlib import Path
import re

ROOT = Path('app/src/main')


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f'{label}: expected anchor not found in {path}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


def patch_profile_context() -> None:
    path = ROOT / 'java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt'
    old = '''    appendLine("If the user asks who created, developed, designed, owns, or made the app, answer with Zouhair Elachaqi and do not claim that the creator is unknown.")
    appendLine()

    if (sessions.isEmpty()) {'''
    new = '''    appendLine("If the user asks who created, developed, designed, owns, or made the app, answer with Zouhair Elachaqi and do not claim that the creator is unknown.")
    appendLine()

    appendLine("AUTHORITATIVE CREATOR ACADEMIC PROFILE:")
    appendLine("When someone asks about Zouhair Elachaqi's university career, education, academic background, research, or teaching practicum, give several relevant facts from this profile rather than only repeating his name.")
    appendLine("Zouhair Elachaqi studied at Université Mohammed V de Rabat, Faculty of Education Sciences / Faculty of Educational Sciences.")
    appendLine("His undergraduate program was a Licence en Éducation (Bachelor's Degree in Education), Secondary Education - English, spanning 2023-2026 as a three-year, six-semester course of study.")
    appendLine("His university studies included didactics, educational sciences, docimology and assessment, classroom management, inclusive education, linguistics, grammar, and English language and culture studies.")
    appendLine("His research project was titled: The Use of Interactive Learning Applications to Foster Student Engagement and Vocabulary Acquisition in Moroccan Middle School EFL Classrooms: The Case of Quizlet.")
    appendLine("The research reported strong student engagement and concentration with Quizlet, while vocabulary gains in the short intervention were slight and statistically limited.")
    appendLine("He completed school-based practicum experience at Al-Khawarizmi Secondary School in Rabat, including classroom observation, teaching practice, administrative work, clubs, sports competitions, and school communication.")
    appendLine("His university supervisor for the practicum/research portfolio was Ahmed Ech-charfi.")
    appendLine("Do not invent grades, awards, degrees, jobs, dates, or biographical details that are not stated in this authoritative profile.")
    appendLine()

    if (sessions.isEmpty()) {'''
    replace_once(path, old, new, 'academic profile')


def patch_coach_ui() -> None:
    path = ROOT / 'assets/interpreter_coach.html'
    html = path.read_text(encoding='utf-8')

    html = re.sub(
        r'\n  <div class="mode-wrap"><div class="modes">.*?</div></div>',
        '',
        html,
        count=1,
        flags=re.S,
    )
    html = re.sub(
        r'\n  <section id="evaluatePane" class="pane">.*?</section>',
        '',
        html,
        count=1,
        flags=re.S,
    )

    old_show = "function showPane(name){const chat=name==='chat';$('chatPane').classList.toggle('active',chat);$('evaluatePane').classList.toggle('active',!chat);$('chatTab').classList.toggle('active',chat);$('evalTab').classList.toggle('active',!chat)}"
    new_show = "function showPane(_name){$('chatPane')?.classList.add('active')}"
    if old_show in html:
        html = html.replace(old_show, new_show, 1)
    elif new_show not in html:
        raise RuntimeError('coach UI: showPane anchor changed')

    path.write_text(html, encoding='utf-8')


def patch_bootstrap_manual_interrupt() -> None:
    path = ROOT / 'assets/interpreter_ai_bootstrap.js'
    text = path.read_text(encoding='utf-8')
    marker = 'window.__manualLiveInterruptUiV1'
    if marker in text:
        return

    anchor = '''    window.__voiceInputStarted = () => {
'''
    if anchor not in text:
        raise RuntimeError('bootstrap: voiceInputStarted anchor changed')

    insert = '''    // Manual tap-to-interrupt fallback. The precise barge-in layer replaces the core
    // interruption function when it is ready, while this keeps the central orb usable immediately.
    if (!window.__manualLiveInterruptUiV1) {
      window.__manualLiveInterruptUiV1 = true;
      const orbButton = byId('voiceOrb');
      if (orbButton) {
        orbButton.setAttribute('role', 'button');
        orbButton.setAttribute('tabindex', '0');
        orbButton.setAttribute('aria-label', 'Interrupt Interpreter Live and speak');
        orbButton.title = 'Tap to interrupt Interpreter Live';
        orbButton.style.cursor = 'pointer';

        const interruptFromOrb = () => {
          if (!window.__voiceCallActive || window.__voiceCallMuted) return false;
          if (typeof window.__interpreterManualBargeIn === 'function') {
            return window.__interpreterManualBargeIn() === true;
          }
          stopVoice();
          try { native?.stopVoiceInput?.(); } catch (_) {}
          native?.setVoiceLanguage?.(byId('callVoiceLang')?.value || 'en-US');
          callStatus('Listening…', 'Go ahead — I am listening.');
          setOrbState('listening');
          setTimeout(() => {
            if (window.__voiceCallActive && !window.__voiceCallMuted) native?.startVoiceInput?.();
          }, 30);
          return true;
        };

        window.interruptInterpreterLive = interruptFromOrb;
        orbButton.onclick = interruptFromOrb;
        orbButton.onkeydown = event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            interruptFromOrb();
          }
        };
      }
    }

'''
    path.write_text(text.replace(anchor, insert + anchor, 1), encoding='utf-8')


def patch_precise_barge_in() -> None:
    path = ROOT / 'assets/interpreter_precise_barge_in.js'
    text = path.read_text(encoding='utf-8')

    if "window.__interpreterManualBargeIn = () =>" not in text:
        anchor = '''  window.startVoiceCall = async () => {
'''
        if anchor not in text:
            raise RuntimeError('precise barge-in: startVoiceCall anchor changed')
        insert = '''  window.__interpreterManualBargeIn = () => {
    if (!window.__voiceCallActive || window.__voiceCallMuted) return false;

    if (turn.phase === 'barge-listening' || state.userBarging) {
      startRecognizerForInterruptedTurn();
      return true;
    }

    if (state.speaking || state.bargeArmed || turn.phase === 'speaking') {
      beginBargeListening('');
      startRecognizerForInterruptedTurn();
      return true;
    }

    stopMonitoring();
    turn.phase = 'listening';
    state.userBarging = false;
    state.bargeArmed = false;
    try { native.stopVoiceInput?.(); } catch (_) {}
    native.setVoiceLanguage?.(callLanguage());
    setStatus('Listening…', 'Go ahead — I am listening.');
    setOrb('listening');
    setTimeout(() => {
      if (window.__voiceCallActive && !window.__voiceCallMuted && !state.speaking) {
        native.startVoiceInput?.();
      }
    }, 30);
    return true;
  };

  const liveOrbButton = document.getElementById('voiceOrb');
  if (liveOrbButton) {
    liveOrbButton.setAttribute('role', 'button');
    liveOrbButton.setAttribute('tabindex', '0');
    liveOrbButton.setAttribute('aria-label', 'Interrupt Interpreter Live and speak');
    liveOrbButton.title = 'Tap to interrupt Interpreter Live';
    liveOrbButton.style.cursor = 'pointer';
    liveOrbButton.onclick = window.__interpreterManualBargeIn;
    liveOrbButton.onkeydown = event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        window.__interpreterManualBargeIn();
      }
    };
  }

'''
        text = text.replace(anchor, insert + anchor, 1)

    text = text.replace(
        "setStatus('Interpreter AI is speaking', state.streamAnswer || 'You can interrupt me while I speak.');",
        "setStatus('Interpreter AI is speaking', 'Tap the center icon or speak to interrupt.');",
    )
    path.write_text(text, encoding='utf-8')


def verify() -> None:
    coach = (ROOT / 'java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt').read_text(encoding='utf-8')
    html = (ROOT / 'assets/interpreter_coach.html').read_text(encoding='utf-8')
    bootstrap = (ROOT / 'assets/interpreter_ai_bootstrap.js').read_text(encoding='utf-8')
    precise = (ROOT / 'assets/interpreter_precise_barge_in.js').read_text(encoding='utf-8')

    checks = {
        'academic profile': 'AUTHORITATIVE CREATOR ACADEMIC PROFILE:' in coach and 'Université Mohammed V de Rabat' in coach,
        'evaluation removed': 'id="evalTab"' not in html and 'id="evaluatePane"' not in html,
        'manual orb fallback': 'interruptInterpreterLive' in bootstrap and 'Tap to interrupt Interpreter Live' in bootstrap,
        'precise manual barge-in': 'window.__interpreterManualBargeIn = () =>' in precise and 'Tap the center icon or speak to interrupt.' in precise,
    }
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        raise RuntimeError('Verification failed: ' + ', '.join(failed))


if __name__ == '__main__':
    patch_profile_context()
    patch_coach_ui()
    patch_bootstrap_manual_interrupt()
    patch_precise_barge_in()
    verify()
