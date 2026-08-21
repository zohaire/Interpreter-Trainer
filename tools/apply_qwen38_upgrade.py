from pathlib import Path

MODEL_OLD = "qwen/qwen3.6-27b"
MODEL_NEW = "qwen/qwen3.8-max"


def replace_model_everywhere() -> None:
    for path in Path("app/src").rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".kt", ".js", ".html"}:
            continue
        text = path.read_text(encoding="utf-8")
        updated = text.replace(MODEL_OLD, MODEL_NEW).replace("Qwen3.6 27B", "Qwen3.8 Max")
        if updated != text:
            path.write_text(updated, encoding="utf-8")


def patch_native_file_chooser() -> None:
    path = Path("app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt")
    text = path.read_text(encoding="utf-8")

    if "import android.net.Uri\n" not in text:
        text = text.replace(
            "import android.graphics.drawable.ColorDrawable\n",
            "import android.graphics.drawable.ColorDrawable\nimport android.net.Uri\n",
            1,
        )
    if "import android.webkit.ValueCallback\n" not in text:
        text = text.replace(
            "import android.webkit.JavascriptInterface\n",
            "import android.webkit.JavascriptInterface\nimport android.webkit.ValueCallback\n",
            1,
        )

    anchor = "    val webViewRef = remember { mutableStateOf<WebView?>(null) }\n"
    replacement = """    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val fileCallbackRef = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileCallbackRef.value?.onReceiveValue(uris.toTypedArray())
        fileCallbackRef.value = null
    }
"""
    if "val fileCallbackRef = remember" not in text:
        if anchor not in text:
            raise RuntimeError("AiCoachScreen webViewRef anchor changed")
        text = text.replace(anchor, replacement, 1)

    old_factory = "                createCoachWebView(webContext, bridge).also { webViewRef.value = it }\n"
    new_factory = """                createCoachWebView(webContext, bridge) { callback ->
                    fileCallbackRef.value?.onReceiveValue(null)
                    fileCallbackRef.value = callback
                    filePickerLauncher.launch(arrayOf("*/*"))
                    true
                }.also { webViewRef.value = it }
"""
    if "createCoachWebView(webContext, bridge) { callback ->" not in text:
        if old_factory not in text:
            raise RuntimeError("AiCoachScreen factory anchor changed")
        text = text.replace(old_factory, new_factory, 1)

    dispose_anchor = "        onDispose {\n            bridge.dispose()\n"
    dispose_new = "        onDispose {\n            fileCallbackRef.value?.onReceiveValue(null)\n            fileCallbackRef.value = null\n            bridge.dispose()\n"
    if "fileCallbackRef.value?.onReceiveValue(null)\n            fileCallbackRef.value = null\n            bridge.dispose()" not in text:
        if dispose_anchor not in text:
            raise RuntimeError("AiCoachScreen dispose anchor changed")
        text = text.replace(dispose_anchor, dispose_new, 1)

    old_create = "private fun createCoachWebView(context: Context, bridge: PracticeContextBridge): WebView {"
    new_create = """private fun createCoachWebView(
    context: Context,
    bridge: PracticeContextBridge,
    onFileChooser: (ValueCallback<Array<Uri>>) -> Boolean
): WebView {"""
    if "onFileChooser: (ValueCallback<Array<Uri>>) -> Boolean" not in text:
        if old_create not in text:
            raise RuntimeError("createCoachWebView signature anchor changed")
        text = text.replace(old_create, new_create, 1)

    old_client = "    webView.webChromeClient = CoachChromeClient(context)\n"
    if "webView.webChromeClient = CoachChromeClient(context, onFileChooser)" not in text:
        if old_client not in text:
            raise RuntimeError("CoachChromeClient call anchor changed")
        text = text.replace(
            old_client,
            "    webView.webChromeClient = CoachChromeClient(context, onFileChooser)\n",
            1,
        )

    old_class = "private class CoachChromeClient(private val context: Context) : WebChromeClient() {"
    new_class = """private class CoachChromeClient(
    private val context: Context,
    private val onFileChooser: (ValueCallback<Array<Uri>>) -> Boolean
) : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        val callback = filePathCallback ?: return false
        return onFileChooser(callback)
    }
"""
    if "override fun onShowFileChooser(" not in text:
        if old_class not in text:
            raise RuntimeError("CoachChromeClient class anchor changed")
        text = text.replace(old_class, new_class, 1)

    path.write_text(text, encoding="utf-8")


def patch_attachment_ui() -> None:
    path = Path("app/src/main/assets/interpreter_coach.html")
    html = path.read_text(encoding="utf-8")
    if "interpreterAttachmentInput" in html:
        return

    script = r'''
<script id="universal-attachment-support">
(() => {
  const readableExtensions = new Set(['txt','md','markdown','csv','tsv','json','xml','yaml','yml','srt','vtt','log','html','htm','css','js','mjs','ts','tsx','jsx','kt','java','py','rb','go','rs','c','cpp','h','hpp','sql','ini','toml','properties']);
  let attachments = [];
  const composer = document.querySelector('.composer');
  const sendButton = document.getElementById('sendBtn');
  if (!composer || !sendButton) return;
  const style = document.createElement('style');
  style.textContent = `.attachment-btn{color:var(--accent)!important}.attachment-strip{width:min(780px,100%);margin:0 auto 7px;display:none;gap:6px;flex-wrap:wrap}.attachment-strip.show{display:flex}.attachment-chip{max-width:46%;padding:6px 9px;border:1px solid var(--border);border-radius:999px;background:var(--surface-soft);color:var(--muted);font-size:10.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}`;
  document.head.appendChild(style);
  const input = document.createElement('input');
  input.id = 'interpreterAttachmentInput'; input.type = 'file'; input.accept = '*/*'; input.multiple = true; input.hidden = true;
  document.body.appendChild(input);
  const attach = document.createElement('button');
  attach.type = 'button'; attach.className = 'icon-btn attachment-btn'; attach.title = 'Attach files'; attach.setAttribute('aria-label','Attach files of any type'); attach.textContent = '+'; attach.onclick = () => input.click();
  composer.insertBefore(attach, sendButton);
  const strip = document.createElement('div'); strip.className = 'attachment-strip'; document.querySelector('.composer-shell')?.insertBefore(strip, composer);
  const redraw = () => { strip.innerHTML = ''; strip.classList.toggle('show', attachments.length > 0); attachments.forEach(file => { const chip=document.createElement('div'); chip.className='attachment-chip'; chip.textContent=`${file.name} · ${Math.max(1,Math.round(file.size/1024))} KB`; strip.appendChild(chip); }); };
  input.onchange = () => { attachments = [...input.files]; redraw(); input.value = ''; };
  const isTextLike = file => { const ext=(file.name.split('.').pop()||'').toLowerCase(); return file.type.startsWith('text/') || readableExtensions.has(ext) || file.type.includes('json') || file.type.includes('xml'); };
  const attachmentContext = async () => { if (!attachments.length) return ''; const blocks=[]; for (const file of attachments) { const header=`[Attached file: ${file.name}; type=${file.type||'unknown'}; size=${file.size} bytes]`; if (isTextLike(file) && file.size <= 6_000_000) { try { blocks.push(`${header}\n${(await file.text()).slice(0,120000)}`); continue; } catch (_) {} } blocks.push(`${header}\nThe file is attached in the app, but this binary format is not converted to text locally. Do not pretend to have read binary contents that are not present in the prompt.`); } return `\n\n--- ATTACHMENTS ---\n${blocks.join('\n\n')}\n--- END ATTACHMENTS ---`; };
  let wrappedFunction = null;
  const installSendWrapper = () => { const current=window.sendChat; if (typeof current !== 'function' || current === wrappedFunction) return; const original=current; wrappedFunction=async function(...args){ if(attachments.length){ const chatInput=document.getElementById('chatInput'); if(chatInput){ chatInput.value=`${chatInput.value||''}${await attachmentContext()}`; window.resizeComposer?.(); window.updateSendState?.(); attachments=[]; redraw(); } } return original.apply(this,args); }; window.sendChat=wrappedFunction; };
  installSendWrapper(); let attempts=0; const installer=setInterval(()=>{ installSendWrapper(); if(++attempts>28) clearInterval(installer); },250);
})();
</script>
'''
    if "</body>" not in html:
        raise RuntimeError("interpreter_coach.html missing body close")
    path.write_text(html.replace("</body>", script + "\n</body>", 1), encoding="utf-8")


def verify() -> None:
    leftovers = []
    for path in Path("app/src").rglob("*"):
        if path.is_file() and path.suffix.lower() in {".kt", ".js", ".html"}:
            if MODEL_OLD in path.read_text(encoding="utf-8"):
                leftovers.append(str(path))
    if leftovers:
        raise RuntimeError("Old Qwen model remains in: " + ", ".join(leftovers))
    coach = Path("app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt").read_text(encoding="utf-8")
    html = Path("app/src/main/assets/interpreter_coach.html").read_text(encoding="utf-8")
    if "onShowFileChooser" not in coach or "interpreterAttachmentInput" not in html:
        raise RuntimeError("Universal attachment integration incomplete")


if __name__ == "__main__":
    replace_model_everywhere()
    patch_native_file_chooser()
    patch_attachment_ui()
    verify()
