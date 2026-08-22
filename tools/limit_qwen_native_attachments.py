from pathlib import Path

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

path.write_text(text, encoding='utf-8')

if "ready.filter(item=>item.kind==='vision')" not in text:
    raise RuntimeError('Qwen native media routing was not applied')
