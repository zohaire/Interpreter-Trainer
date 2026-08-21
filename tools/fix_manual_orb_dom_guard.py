from pathlib import Path

path = Path('app/src/main/assets/interpreter_precise_barge_in.js')
text = path.read_text(encoding='utf-8')
replacements = {
    "liveOrbButton.setAttribute('role', 'button');": "liveOrbButton.setAttribute?.('role', 'button');",
    "liveOrbButton.setAttribute('tabindex', '0');": "liveOrbButton.setAttribute?.('tabindex', '0');",
    "liveOrbButton.setAttribute('aria-label', 'Interrupt Interpreter Live and speak');": "liveOrbButton.setAttribute?.('aria-label', 'Interrupt Interpreter Live and speak');",
    "liveOrbButton.style.cursor = 'pointer';": "if (liveOrbButton.style) liveOrbButton.style.cursor = 'pointer';",
}
for old, new in replacements.items():
    text = text.replace(old, new)
path.write_text(text, encoding='utf-8')

if "liveOrbButton.setAttribute?.('role', 'button');" not in text:
    raise SystemExit('DOM guard patch did not apply')
