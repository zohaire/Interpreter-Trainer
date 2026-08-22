from pathlib import Path
import re

path = Path('app/src/main/assets/interpreter_coach.html')
text = path.read_text(encoding='utf-8')

old = "ready.forEach(item => content.push({ type:'file', puter_path:item.path }));"
new = "ready.filter(item=>item.kind==='vision').forEach(item => content.push({ type:'file', puter_path:item.path }));"
if new not in text:
    if old not in text:
        raise RuntimeError('Expected attachment routing anchor not found')
    text = text.replace(old, new, 1)

text = text.replace(
    "// Keep the original file attached as Puter documents, and also provide extracted/transcribed\n      // text so Qwen3.8 Max can reason over formats that are not native Qwen input modalities.",
    "// Qwen3.8 Max natively receives image/video files. Text, audio and general documents are\n      // converted to text first so unsupported binary formats cannot break or be silently ignored."
)
text = text.replace(
    "Some attachment formats could not be converted to text, but their original files are still attached: ",
    "Some attachment formats could not be converted into readable text: "
)

# re.sub() interprets backslash escapes in replacement strings. The guarded migration deliberately
# uses a second pass here so the human-readable attachment prefix cannot become an invalid quoted
# JavaScript string containing literal newlines.
context_pattern = re.compile(
    r"text:'The following attachment content was extracted or transcribed for reliable reading\. "
    r"Treat it as source material from the attached files:\s*'\+boundedContexts\.join\('\s*"
    r"--- NEXT FILE ---\s*'\)"
)
context_replacement = (
    "text:'The following attachment content was extracted or transcribed for reliable reading. "
    "Treat it as source material from the attached files:'+String.fromCharCode(10,10)+"
    "boundedContexts.join(String.fromCharCode(10,10)+'--- NEXT FILE ---'+String.fromCharCode(10,10))"
)
text, repaired = context_pattern.subn(context_replacement, text, count=1)
if repaired != 1 and "String.fromCharCode(10,10)+boundedContexts.join" not in text:
    raise RuntimeError('Attachment context escaping anchor was not repaired')

path.write_text(text, encoding='utf-8')

required = [
    "ready.filter(item=>item.kind==='vision')",
    "String.fromCharCode(10,10)+boundedContexts.join",
]
missing = [marker for marker in required if marker not in text]
if missing:
    raise RuntimeError('Qwen attachment routing repair incomplete: ' + ', '.join(missing))
