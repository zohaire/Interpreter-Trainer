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
        text = text.replace(old_client, "    webView.webChromeClient = CoachChromeClient(context, onFileChooser)\n", 1)

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
  const composer = document.querySelector('.composer');
  const shell = document.querySelector('.composer-shell');
  const sendButton = document.getElementById('sendBtn');
  const chatInput = document.getElementById('chatInput');
  if (!composer || !shell || !sendButton || !chatInput) return;

  let pending = [];
  let uploading = false;
  let armNextChat = false;

  const style = document.createElement('style');
  style.textContent = `.attachment-btn{color:var(--accent)!important;font-size:22px;font-weight:500}.attachment-strip{width:min(780px,100%);margin:0 auto 7px;display:none;gap:6px;flex-wrap:wrap}.attachment-strip.show{display:flex}.attachment-chip{max-width:48%;padding:6px 9px;border:1px solid var(--border);border-radius:999px;background:var(--surface-soft);color:var(--muted);font-size:10.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.attachment-chip.ready{color:var(--ok)}.attachment-chip.bad{color:var(--danger)}`;
  document.head.appendChild(style);

  const input = document.createElement('input');
  input.id = 'interpreterAttachmentInput';
  input.type = 'file';
  input.accept = '*/*';
  input.multiple = true;
  input.hidden = true;
  document.body.appendChild(input);

  const attach = document.createElement('button');
  attach.type = 'button';
  attach.className = 'icon-btn attachment-btn';
  attach.title = 'Attach files';
  attach.setAttribute('aria-label', 'Attach files of any type');
  attach.textContent = '+';
  attach.onclick = () => input.click();
  composer.insertBefore(attach, sendButton);

  const strip = document.createElement('div');
  strip.className = 'attachment-strip';
  shell.insertBefore(strip, composer);

  const error = message => {
    const box = document.getElementById('chatError');
    if (box) box.textContent = message;
  };

  const redraw = () => {
    strip.innerHTML = '';
    strip.classList.toggle('show', pending.length > 0);
    pending.forEach(item => {
      const chip = document.createElement('div');
      chip.className = 'attachment-chip ' + (item.status === 'ready' ? 'ready' : item.status === 'error' ? 'bad' : '');
      chip.textContent = `${item.status === 'uploading' ? '↑ ' : item.status === 'ready' ? '✓ ' : item.status === 'error' ? '! ' : ''}${item.name}`;
      chip.title = item.status === 'error' ? (item.error || 'Upload failed') : item.name;
      strip.appendChild(chip);
    });
  };

  const normalizeItems = value => Array.isArray(value) ? value : value ? [value] : [];
  const pathsFromUpload = value => normalizeItems(value).map(item => item?.path).filter(Boolean);

  input.onchange = async () => {
    const files = [...(input.files || [])];
    input.value = '';
    if (!files.length) return;
    if (!window.puter?.fs?.upload) {
      error('File upload service is not available. Check the internet connection and try again.');
      return;
    }

    const batch = files.map(file => ({ name:file.name, status:'uploading', path:null, error:null }));
    pending.push(...batch);
    uploading = true;
    redraw();
    error('');

    try {
      const result = await puter.fs.upload(files, './InterpreterTrainerUploads', {
        createMissingParents: true,
        dedupeName: true
      });
      const paths = pathsFromUpload(result);
      if (paths.length !== files.length) throw new Error(`Uploaded ${paths.length} of ${files.length} files`);
      batch.forEach((item, i) => {
        item.path = paths[i];
        item.status = 'ready';
      });
    } catch (e) {
      batch.forEach(item => {
        item.status = 'error';
        item.error = e?.message || String(e);
      });
      error('One or more files could not be uploaded. Remove them by starting a new chat or choose the files again.');
    } finally {
      uploading = false;
      redraw();
    }
  };

  const blockWhileUploading = event => {
    if (!uploading) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    error('Files are still uploading. Send after the attachment chips show ✓.');
  };
  sendButton.addEventListener('click', blockWhileUploading, true);
  chatInput.addEventListener('keydown', event => {
    if (uploading && event.key === 'Enter' && !event.shiftKey) blockWhileUploading(event);
  }, true);

  const installChatWrapper = () => {
    const current = window.sendChat;
    if (typeof current !== 'function' || current.__attachmentWrapper) return;
    const wrapped = function(...args) {
      const ready = pending.filter(item => item.status === 'ready' && item.path);
      if (ready.length) armNextChat = true;
      return current.apply(this, args);
    };
    wrapped.__attachmentWrapper = true;
    window.sendChat = wrapped;
  };

  const installPuterWrapper = () => {
    const ai = window.puter?.ai;
    if (!ai || typeof ai.chat !== 'function' || ai.chat.__attachmentWrapper) return;
    const original = ai.chat.bind(ai);
    const wrapped = function(messages, options) {
      if (!armNextChat) return original(messages, options);
      armNextChat = false;
      const ready = pending.filter(item => item.status === 'ready' && item.path);
      if (!ready.length || !Array.isArray(messages)) return original(messages, options);

      const cloned = messages.map(message => ({...message}));
      let index = -1;
      for (let i = cloned.length - 1; i >= 0; i--) {
        if (cloned[i]?.role === 'user') { index = i; break; }
      }
      if (index < 0) return original(messages, options);

      const originalContent = cloned[index].content;
      const content = [];
      if (Array.isArray(originalContent)) content.push(...originalContent);
      else content.push({ type:'text', text:String(originalContent ?? '') });
      ready.forEach(item => content.push({ type:'file', puter_path:item.path }));
      cloned[index] = {...cloned[index], content};

      pending = pending.filter(item => item.status !== 'ready');
      redraw();
      return original(cloned, options);
    };
    wrapped.__attachmentWrapper = true;
    ai.chat = wrapped;
  };

  installChatWrapper();
  installPuterWrapper();
  let attempts = 0;
  const installer = setInterval(() => {
    installChatWrapper();
    installPuterWrapper();
    if (++attempts > 80) clearInterval(installer);
  }, 250);
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
    required_html = ("interpreterAttachmentInput", "puter.fs.upload", "puter_path")
    if "onShowFileChooser" not in coach or not all(marker in html for marker in required_html):
        raise RuntimeError("Universal Puter attachment integration incomplete")


if __name__ == "__main__":
    replace_model_everywhere()
    patch_native_file_chooser()
    patch_attachment_ui()
    verify()
