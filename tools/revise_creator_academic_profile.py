from pathlib import Path
import re

path = Path('app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt')
text = path.read_text(encoding='utf-8')

pattern = re.compile(
    r'    appendLine\("AUTHORITATIVE CREATOR ACADEMIC PROFILE:"\)\n'
    r'.*?'
    r'    appendLine\(\)\n\n'
    r'    if \(sessions\.isEmpty\(\)\) \{',
    re.S,
)

replacement = '''    appendLine("AUTHORITATIVE CREATOR ACADEMIC PROFILE:")
    appendLine("When someone asks about Zouhair Elachaqi's university career, education, or academic background, give several relevant facts from this profile rather than only repeating his name.")
    appendLine("Zouhair Elachaqi studied at Université Mohammed V de Rabat, Faculty of Education Sciences / Faculty of Educational Sciences.")
    appendLine("He completed a Licence en Éducation (Bachelor's Degree in Education), Secondary Education - English.")
    appendLine("Present his academic background first through English Studies and linguistics: linguistics, grammar, English language study, and English language and culture were important components of his university training.")
    appendLine("His studies also included educational sciences and professional teacher preparation, including didactics, docimology and assessment, classroom management, and inclusive education.")
    appendLine("When summarizing his university profile, emphasize linguistics, English Studies, language knowledge, and multilingual communication before discussing education or didactics.")
    appendLine("Do not mention a research topic, research findings, university supervisor, graduation year, grades, or other details that are not included in this authoritative profile.")
    appendLine()

    if (sessions.isEmpty()) {'''

updated, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f'Expected exactly one academic profile block, found {count}')

for forbidden in [
    'The Use of Interactive Learning Applications to Foster Student Engagement',
    'Ahmed Ech-charfi',
    'spanning 2023-2026',
    'The research reported strong student engagement',
]:
    if forbidden in updated:
        raise SystemExit(f'Forbidden profile detail remains: {forbidden}')

required = [
    'Present his academic background first through English Studies and linguistics',
    'emphasize linguistics, English Studies, language knowledge, and multilingual communication',
    'Do not mention a research topic, research findings, university supervisor, graduation year',
]
for item in required:
    if item not in updated:
        raise SystemExit(f'Missing required profile wording: {item}')

path.write_text(updated, encoding='utf-8')
