from pathlib import Path
import re

ROOT = Path('.')
AI = ROOT / 'app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
HTML = ROOT / 'app/src/main/assets/interpreter_coach.html'
EXTRACTOR = ROOT / 'app/src/main/java/com/interpretertrainer/app/ui/screens/LocalDocumentExtractor.kt'

ai = AI.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')
html = HTML.read_text(encoding='utf-8')

# 1. Add local PDF dependency.
pdf_dep = '    implementation("com.tom-roush:pdfbox-android:2.0.27.0")\n'
if pdf_dep.strip() not in gradle:
    anchor = '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")\n'
    if anchor not in gradle:
        raise RuntimeError('Gradle dependency anchor changed')
    gradle = gradle.replace(anchor, anchor + '\n' + pdf_dep, 1)
GRADLE.write_text(gradle, encoding='utf-8')

# 2. Native local document extractor. It handles PDF + modern Office/OpenDocument/EPUB without AI calls.
EXTRACTOR.write_text(r'''package com.interpretertrainer.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class PreparedAttachment(
    val name: String,
    val kind: String,
    val text: String? = null,
    val error: String? = null
)

internal object LocalDocumentExtractor {
    private const val MAX_CHARS = 260_000
    private const val MAX_ENTRY_BYTES = 6 * 1024 * 1024
    private const val MAX_ZIP_BYTES = 36 * 1024 * 1024

    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm",
        "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py",
        "rb", "php", "go", "rs", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "sql", "yaml", "yml", "toml", "ini", "cfg", "log", "srt", "vtt", "rtf"
    )

    fun prepare(context: Context, uris: List<Uri>): List<PreparedAttachment> = uris.map { uri ->
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            return@map PreparedAttachment(name, "vision")
        }
        if (mime.startsWith("audio/")) {
            return@map PreparedAttachment(name, "audio")
        }

        runCatching {
            val extracted = when {
                mime.startsWith("text/") || ext in textExtensions -> readText(context, uri, ext)
                ext == "pdf" || mime == "application/pdf" -> readPdf(context, uri)
                ext == "docx" -> readDocx(context, uri)
                ext == "pptx" -> readPptx(context, uri)
                ext == "xlsx" -> readXlsx(context, uri)
                ext in setOf("odt", "ods", "odp") -> readOpenDocument(context, uri)
                ext == "epub" -> readEpub(context, uri)
                else -> sniffReadableText(context, uri)
            }.trim().take(MAX_CHARS)

            if (extracted.isBlank()) {
                PreparedAttachment(
                    name = name,
                    kind = "document",
                    error = "No readable text was found. Scanned/image-only PDFs need OCR; legacy .doc/.xls/.ppt files should be saved as DOCX/XLSX/PPTX."
                )
            } else {
                PreparedAttachment(name = name, kind = "document", text = extracted)
            }
        }.getOrElse { error ->
            PreparedAttachment(
                name = name,
                kind = "document",
                error = error.message ?: "This file could not be read locally."
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty().ifBlank { "attachment" }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "attachment" } ?: "attachment"
    }

    private fun open(context: Context, uri: Uri): InputStream =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Unable to open attachment" }

    private fun readText(context: Context, uri: Uri, ext: String): String {
        val raw = open(context, uri).use { it.readBytesLimited(MAX_ENTRY_BYTES) }
        val decoded = raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return if (ext == "rtf") stripRtf(decoded) else decoded
    }

    private fun stripRtf(value: String): String = value
        .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ")
        .replace("{", " ")
        .replace("}", " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return open(context, uri).use { input ->
            PDDocument.load(input).use { document ->
                PDFTextStripper().getText(document)
            }
        }
    }

    private fun readDocx(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) { it == "word/document.xml" }
        val xml = entries["word/document.xml"] ?: error("DOCX document.xml is missing")
        return extractXmlText(xml, setOf("t"), setOf("p"))
    }

    private fun readPptx(context: Context, uri: Uri): String {
        val slides = zipEntries(context, uri) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        return slides.entries
            .sortedBy { slideNumber(it.key) }
            .joinToString("\n\n") { (name, bytes) ->
                "[${name.substringAfterLast('/').substringBeforeLast('.')}]\n" + extractXmlText(bytes, setOf("t"), setOf("p"))
            }
    }

    private fun readOpenDocument(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) { it == "content.xml" }
        val xml = entries["content.xml"] ?: error("OpenDocument content.xml is missing")
        return extractXmlText(xml, setOf("p", "h", "span"), setOf("p", "h"), captureElementText = true)
    }

    private fun readEpub(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) {
            val lower = it.lowercase(Locale.ROOT)
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
        }
        return entries.entries
            .sortedBy { it.key }
            .joinToString("\n\n") { (_, bytes) ->
                @Suppress("DEPRECATION")
                Html.fromHtml(bytes.toString(Charsets.UTF_8)).toString()
            }
    }

    private fun readXlsx(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) {
            it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml"))
        }
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return entries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") }
            .sortedBy { slideNumber(it.key) }
            .joinToString("\n\n") { (name, bytes) ->
                "[${name.substringAfterLast('/').substringBeforeLast('.')}]\n" + parseWorksheet(bytes, shared)
            }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
        val result = mutableListOf<String>()
        var inItem = false
        var inText = false
        val item = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "si" -> { inItem = true; item.setLength(0) }
                    "t" -> if (inItem) inText = true
                }
                XmlPullParser.TEXT -> if (inItem && inText) item.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> inText = false
                    "si" -> { result += item.toString(); inItem = false }
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseWorksheet(bytes: ByteArray, shared: List<String>): String {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
        val out = StringBuilder()
        var cellRef = ""
        var cellType = ""
        var value: String? = null
        var inline = StringBuilder()
        var captureV = false
        var captureT = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r").orEmpty()
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = null
                        inline = StringBuilder()
                    }
                    "v" -> captureV = true
                    "t" -> captureT = true
                }
                XmlPullParser.TEXT -> {
                    if (captureV) value = (value ?: "") + parser.text
                    if (captureT) inline.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "v" -> captureV = false
                    "t" -> captureT = false
                    "c" -> {
                        val raw = inline.toString().ifBlank { value.orEmpty() }
                        val rendered = if (cellType == "s") raw.toIntOrNull()?.let(shared::getOrNull).orEmpty() else raw
                        if (rendered.isNotBlank()) out.append(if (cellRef.isBlank()) "• " else "$cellRef: ").append(rendered).append('\n')
                    }
                }
            }
            parser.next()
        }
        return out.toString()
    }

    private fun extractXmlText(
        bytes: ByteArray,
        textTags: Set<String>,
        paragraphTags: Set<String>,
        captureElementText: Boolean = false
    ): String {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
        val out = StringBuilder()
        var captureDepth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val name = if (parser.eventType == XmlPullParser.START_TAG || parser.eventType == XmlPullParser.END_TAG) {
                parser.name.substringAfter(':')
            } else ""
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (name in textTags) captureDepth++
                XmlPullParser.TEXT -> if (captureDepth > 0) out.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (name in textTags && captureDepth > 0) captureDepth--
                    if (name in paragraphTags) out.append('\n')
                }
            }
            parser.next()
        }
        return out.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun sniffReadableText(context: Context, uri: Uri): String {
        val bytes = open(context, uri).use { it.readBytesLimited(512 * 1024) }
        if (bytes.isEmpty()) return ""
        val zeroes = bytes.count { it == 0.toByte() }
        val controls = bytes.count { value ->
            val v = value.toInt() and 0xff
            v < 9 || (v in 14..31)
        }
        if (zeroes > bytes.size / 100 || controls > bytes.size / 20) return ""
        return bytes.toString(Charsets.UTF_8)
    }

    private fun zipEntries(context: Context, uri: Uri, accept: (String) -> Boolean): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0
        open(context, uri).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && accept(entry.name)) {
                        val bytes = zip.readBytesLimited(MAX_ENTRY_BYTES)
                        total += bytes.size
                        if (total > MAX_ZIP_BYTES) error("Document is too large to extract safely")
                        result[entry.name] = bytes
                    }
                    zip.closeEntry()
                }
            }
        }
        return result
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) error("Attachment is too large to read safely")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun slideNumber(path: String): Int = Regex("(\\d+)(?=\\.xml$)")
        .find(path)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}
''', encoding='utf-8')

# 3. Wire extraction into the Android file chooser and expose it to WebView JavaScript.
imports_anchor = 'import androidx.compose.runtime.remember\n'
extra_imports = (
    'import androidx.compose.runtime.rememberCoroutineScope\n'
    'import kotlinx.coroutines.Dispatchers\n'
    'import kotlinx.coroutines.launch\n'
    'import kotlinx.coroutines.withContext\n'
    'import java.util.concurrent.ConcurrentHashMap\n'
)
if 'rememberCoroutineScope' not in ai:
    if imports_anchor not in ai:
        raise RuntimeError('Compose import anchor changed')
    ai = ai.replace(imports_anchor, imports_anchor + extra_imports, 1)

if 'val scope = rememberCoroutineScope()' not in ai:
    ai = ai.replace(
        '    val context = LocalContext.current\n',
        '    val context = LocalContext.current\n    val scope = rememberCoroutineScope()\n',
        1
    )

old_picker = '''    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileCallbackRef.value?.onReceiveValue(uris.toTypedArray())
        fileCallbackRef.value = null
    }
'''
new_picker = '''    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = fileCallbackRef.value
        if (callback == null) return@rememberLauncherForActivityResult
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                LocalDocumentExtractor.prepare(context.applicationContext, uris)
            }
            bridgeHolder.value?.setPreparedAttachments(prepared)
            callback.onReceiveValue(uris.toTypedArray())
            fileCallbackRef.value = null
        }
    }
'''
if new_picker not in ai:
    if old_picker not in ai:
        raise RuntimeError('File-picker block changed')
    ai = ai.replace(old_picker, new_picker, 1)

bridge_anchor = '    private var pendingVoiceStart = false\n'
bridge_fields = '''    private val preparedAttachments = ConcurrentHashMap<String, PreparedAttachment>()
'''
if 'preparedAttachments = ConcurrentHashMap' not in ai:
    if bridge_anchor not in ai:
        raise RuntimeError('Bridge field anchor changed')
    ai = ai.replace(bridge_anchor, bridge_anchor + '\n' + bridge_fields, 1)

method_anchor = '    @JavascriptInterface\n    fun getPracticeContext(): String = contextValue\n'
attachment_methods = '''
    fun setPreparedAttachments(items: List<PreparedAttachment>) {
        preparedAttachments.clear()
        items.forEach { preparedAttachments[it.name] = it }
    }

    @JavascriptInterface
    fun getPreparedAttachment(name: String): String {
        val item = preparedAttachments[name] ?: return ""
        return JSONObject().apply {
            put("name", item.name)
            put("kind", item.kind)
            put("text", item.text ?: JSONObject.NULL)
            put("error", item.error ?: JSONObject.NULL)
        }.toString()
    }
'''
if 'fun getPreparedAttachment(name: String)' not in ai:
    if method_anchor not in ai:
        raise RuntimeError('Bridge method anchor changed')
    ai = ai.replace(method_anchor, method_anchor + attachment_methods, 1)

if 'preparedAttachments.clear()' not in ai.split('fun dispose()',1)[1]:
    ai = ai.replace(
        '            ttsReady = false\n            webView = null\n',
        '            ttsReady = false\n            preparedAttachments.clear()\n            webView = null\n',
        1
    )
AI.write_text(ai, encoding='utf-8')

# 4. Replace attachment JS with a local-first pipeline. No second chat model is used for documents.
new_script = r'''<script id="universal-attachment-support">
(() => {
  const composer = document.querySelector('.composer');
  const shell = document.querySelector('.composer-shell');
  const sendButton = document.getElementById('sendBtn');
  const chatInput = document.getElementById('chatInput');
  if (!composer || !shell || !sendButton || !chatInput) return;

  let pending = [];
  let uploading = false;
  let armNextChat = false;
  const MAX_LOCAL_TEXT = 260000;
  const MAX_TOTAL_EXTRACTED = 300000;
  const TEXT_EXTENSIONS = new Set(['txt','md','markdown','csv','tsv','json','jsonl','xml','html','htm','css','js','mjs','cjs','ts','tsx','jsx','kt','kts','java','py','rb','php','go','rs','c','cc','cpp','h','hpp','sh','bash','zsh','sql','yaml','yml','toml','ini','cfg','log','srt','vtt','rtf']);
  const AUDIO_EXTENSIONS = new Set(['mp3','wav','m4a','aac','ogg','oga','flac','opus','webm','amr','3gp']);
  const extOf = name => String(name || '').toLowerCase().split('.').pop() || '';

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

  const classifyFile = file => {
    const mime = String(file?.type || '').toLowerCase();
    const ext = extOf(file?.name);
    if (mime.startsWith('image/') || mime.startsWith('video/')) return 'vision';
    if (mime.startsWith('audio/') || AUDIO_EXTENSIONS.has(ext)) return 'audio';
    if (mime.startsWith('text/') || TEXT_EXTENSIONS.has(ext)) return 'text';
    return 'document';
  };

  const redraw = () => {
    strip.innerHTML = '';
    strip.classList.toggle('show', pending.length > 0);
    pending.forEach(item => {
      const chip = document.createElement('div');
      chip.className = 'attachment-chip ' + (item.status === 'ready' ? 'ready' : item.status === 'error' ? 'bad' : '');
      chip.textContent = `${item.status === 'uploading' ? '↑ ' : item.status === 'ready' ? '✓ ' : item.status === 'error' ? '! ' : ''}${item.name}`;
      chip.title = item.status === 'error' ? (item.error || 'Attachment failed') : item.name;
      strip.appendChild(chip);
    });
  };

  const nativePrepared = name => {
    try {
      const raw = window.InterpreterNative?.getPreparedAttachment?.(name);
      if (!raw) return null;
      return JSON.parse(raw);
    } catch (_) { return null; }
  };

  const safeFileName = name => String(name || 'file').replace(/[^a-zA-Z0-9._-]+/g,'_').slice(-120) || 'file';
  const writeRemoteMedia = async (file, index) => {
    if (window.puter?.fs?.write) {
      const target = `InterpreterTrainerUploads/${Date.now()}-${index}-${safeFileName(file.name)}`;
      const item = await puter.fs.write(target, file, {createMissingParents:true,dedupeName:true});
      if (!item?.path) throw new Error('Puter did not return a stored file path.');
      return item.path;
    }
    if (window.puter?.fs?.upload) {
      const result = await puter.fs.upload([file], './InterpreterTrainerUploads', {dedupeName:true});
      const item = Array.isArray(result) ? result[0] : result;
      if (!item?.path) throw new Error('Puter did not return a stored file path.');
      return item.path;
    }
    throw new Error('Media upload service is unavailable.');
  };

  input.onchange = async () => {
    const files = [...(input.files || [])];
    input.value = '';
    if (!files.length) return;

    const batch = files.map(file => ({name:file.name,mime:file.type||'',kind:classifyFile(file),status:'uploading',path:null,localText:null,error:null}));
    pending.push(...batch);
    uploading = true;
    redraw();
    error('');

    await Promise.all(batch.map(async (item, index) => {
      try {
        const file = files[index];
        if (item.kind === 'text') {
          item.localText = (await file.text()).slice(0, MAX_LOCAL_TEXT);
        } else if (item.kind === 'document') {
          const prepared = nativePrepared(item.name);
          if (prepared?.text) item.localText = String(prepared.text).slice(0, MAX_LOCAL_TEXT);
          else throw new Error(prepared?.error || 'This document could not be read locally.');
        } else {
          item.path = await writeRemoteMedia(file, index);
        }
        item.status = 'ready';
      } catch (e) {
        item.status = 'error';
        item.error = e?.message || String(e);
      }
    }));

    uploading = false;
    redraw();
    const failed = batch.filter(item => item.status === 'error');
    if (failed.length) error(failed.map(item => `${item.name}: ${item.error}`).join(' · '));
  };

  const blockWhileUploading = event => {
    if (!uploading) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    error('Files are still being prepared. Send after the attachment chips show ✓.');
  };
  sendButton.addEventListener('click', blockWhileUploading, true);
  chatInput.addEventListener('keydown', event => {
    if (uploading && event.key === 'Enter' && !event.shiftKey) blockWhileUploading(event);
  }, true);

  const readyItems = () => pending.filter(item => item.status === 'ready' && (item.localText || item.path));

  const installChatWrapper = () => {
    const current = window.sendChat;
    if (typeof current !== 'function' || current.__attachmentWrapper) return;
    const wrapped = function(...args) {
      if (readyItems().length) armNextChat = true;
      return current.apply(this, args);
    };
    wrapped.__attachmentWrapper = true;
    window.sendChat = wrapped;
  };

  const responseText = result => {
    if (typeof result === 'string') return result.trim();
    if (typeof result?.text === 'string') return result.text.trim();
    const content = result?.message?.content;
    if (typeof content === 'string') return content.trim();
    if (Array.isArray(content)) return content.map(part => typeof part === 'string' ? part : (part?.text || '')).join('').trim();
    return '';
  };

  const speechText = result => typeof result === 'string' ? result.trim() : String(result?.text || result?.transcript || '').trim();

  const extractReadableContext = async item => {
    if ((item.kind === 'text' || item.kind === 'document') && item.localText) {
      return `FILE: ${item.name}\nTYPE: ${item.kind}\nCONTENT:\n${item.localText}`;
    }
    if (item.kind === 'audio' && item.path && window.puter?.ai?.speech2txt) {
      try {
        const transcript = await puter.ai.speech2txt(item.path, {model:'gpt-4o-mini-transcribe',response_format:'text'});
        const text = speechText(transcript);
        if (text) return `FILE: ${item.name}\nTYPE: audio transcript\nCONTENT:\n${text}`;
      } catch (e) {
        item.readError = 'Audio transcription needs available AI usage: ' + (e?.message || e);
      }
    }
    return '';
  };

  const installPuterWrapper = () => {
    const ai = window.puter?.ai;
    if (!ai || typeof ai.chat !== 'function' || ai.chat.__attachmentWrapper) return;
    const original = ai.chat.bind(ai);
    const wrapped = async function(messages, options) {
      if (!armNextChat) return original(messages, options);
      armNextChat = false;
      const ready = readyItems();
      if (!ready.length || !Array.isArray(messages)) return original(messages, options);

      const cloned = messages.map(message => ({...message}));
      let index = -1;
      for (let i = cloned.length - 1; i >= 0; i--) {
        if (cloned[i]?.role === 'user') { index = i; break; }
      }
      if (index < 0) return original(messages, options);

      const contexts = (await Promise.all(ready.map(extractReadableContext))).filter(Boolean);
      let used = 0;
      const bounded = [];
      for (const context of contexts) {
        if (used >= MAX_TOTAL_EXTRACTED) break;
        const slice = context.slice(0, MAX_TOTAL_EXTRACTED - used);
        bounded.push(slice);
        used += slice.length;
      }

      const originalContent = cloned[index].content;
      const originalParts = Array.isArray(originalContent) ? [...originalContent] : [{type:'text',text:String(originalContent ?? '')}];
      const content = [];
      ready.filter(item => item.kind === 'vision' && item.path).forEach(item => content.push({type:'file',puter_path:item.path}));
      if (bounded.length) {
        content.push({type:'text',text:'Read and use the following attachment content as source material. Do not claim you cannot access it:\n\n' + bounded.join('\n\n--- NEXT FILE ---\n\n')});
      }
      content.push(...originalParts);
      cloned[index] = {...cloned[index], content};

      const unread = ready.filter(item => item.readError);
      if (unread.length) error(unread.map(item => `${item.name}: ${item.readError}`).join(' · '));

      pending = pending.filter(item => !ready.includes(item));
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
</script>'''

html2, count = re.subn(r'<script id="universal-attachment-support">.*?</script>', lambda _: new_script, html, count=1, flags=re.S)
if count != 1:
    raise RuntimeError('Attachment script block changed')
HTML.write_text(html2, encoding='utf-8')

# Basic guarded assertions before CI compiles anything.
checks = {
    GRADLE: ['pdfbox-android:2.0.27.0'],
    AI: ['LocalDocumentExtractor.prepare', 'getPreparedAttachment(name: String)', 'rememberCoroutineScope'],
    HTML: ['getPreparedAttachment?.(name)', 'item.kind === \'document\'', 'ready.filter(item => item.kind === \'vision\''],
    EXTRACTOR: ['PDFTextStripper', 'readDocx', 'readPptx', 'readXlsx']
}
for path, markers in checks.items():
    body = path.read_text(encoding='utf-8')
    missing = [m for m in markers if m not in body]
    if missing:
        raise RuntimeError(f'{path}: missing {missing}')
