from pathlib import Path

path = Path('app/src/main/assets/interpreter_coach.html')
text = path.read_text(encoding='utf-8')

broken = "filter(Boolean).join('\n\n');"
fixed = "filter(Boolean).join('\\n\\n');"
count = text.count(broken)
if count != 1:
    raise SystemExit(f'Expected exactly one broken failover newline sequence, found {count}')
text = text.replace(broken, fixed, 1)
path.write_text(text, encoding='utf-8')
print('Repaired failover inline JavaScript escaping.')
