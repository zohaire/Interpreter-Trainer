from pathlib import Path
import re

ROOT = Path('app/src/main')
AI = ROOT / 'java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt'
HTML = ROOT / 'assets/interpreter_coach.html'
BOOT = ROOT / 'assets/interpreter_ai_bootstrap.js'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding='utf-8')
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f'{label}: anchor not found in {path}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


def patch_native_bridge():
    replace_once(
        AI,
        '    private val microphoneOwnerId = "ai-voice-${System.identityHashCode(this)}"\n',
        '    private val microphoneOwnerId = "ai-voice-${System.identityHashCode(this)}"\n'
        '    private val aiSettings = context.getSharedPreferences("interpreter_ai_settings", Context.MODE_PRIVATE)\n',
        'AI settings storage'
    )

    old = '''    @JavascriptInterface
    fun getPracticeContext(): String = contextValue

    fun setPreparedAttachments(items: List<PreparedAttachment>) {'''
    new = '''    @JavascriptInterface
    fun getPracticeContext(): String = contextValue

    @JavascriptInterface
    fun getFreeAiApiKey(): String = aiSettings.getString("gemini_api_key", "").orEmpty()

    @JavascriptInterface
    fun setFreeAiApiKey(value: String): Boolean {
        val clean = value.trim()
        if (clean.length !in 20..256) return false
        aiSettings.edit().putString("gemini_api_key", clean).apply()
        return true
    }

    @JavascriptInterface
    fun clearFreeAiApiKey(): Boolean {
        aiSettings.edit().remove("gemini_api_key").apply()
        return true
    }

    @JavascriptInterface
    fun openFreeAiKeyPage(): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun setPreparedAttachments(items: List<PreparedAttachment>) {'''
    replace_once(AI, old, new, 'free API key bridge')


def patch_html_core():
    text = HTML.read_text(encoding='utf-8')
    text = text.replace(
        'Qwen3.8 Max · streaming online AI · verify critical source facts',
        'Free AI · Gemini 3.7 Flash · Qwen3.8 Max optional · verify critical source facts'
    )

    marker = "const FREE_AI_MODEL='gemini-3.7-flash'"
    if marker not in text:
        anchor = "  async function connectAi(){"
        if anchor not in text:
            raise RuntimeError('core provider anchor missing')
        provider = r'''  const FREE_AI_MODEL='gemini-3.7-flash';
  function freeAiKey(){try{return String(window.InterpreterNative?.getFreeAiApiKey?.()||'').trim()}catch(_){return''}}
  function freeAiReady(){return freeAiKey().length>=20}
  function ensureFreeAiSetupUi(){
    let overlay=document.getElementById('freeAiSetupOverlay');
    if(overlay)return overlay;
    const style=document.createElement('style');
    style.textContent='.free-ai-setup{position:fixed;inset:0;z-index:12000;display:none;align-items:center;justify-content:center;padding:22px;background:#0007;backdrop-filter:blur(5px)}.free-ai-setup.show{display:flex}.free-ai-card{width:min(470px,94vw);border:1px solid var(--border);border-radius:24px;background:var(--surface);padding:22px;box-shadow:0 28px 90px #0004}.free-ai-card h2{margin:0 0 8px;font-size:21px}.free-ai-card p{margin:0 0 15px;color:var(--muted);font-size:13px;line-height:1.55}.free-ai-key{width:100%;border:1px solid var(--border);border-radius:13px;background:var(--soft);color:var(--text);padding:12px;outline:0}.free-ai-actions{display:flex;gap:8px;margin-top:13px;flex-wrap:wrap}.free-ai-actions button{border-radius:12px;padding:10px 13px;font-weight:700}.free-ai-save{border:0;background:linear-gradient(145deg,var(--accent),var(--accent2));color:white}.free-ai-link,.free-ai-close{border:1px solid var(--border);background:var(--surface);color:var(--text)}.free-ai-note{margin-top:10px!important;font-size:11px!important}';
    document.head.appendChild(style);
    overlay=document.createElement('div');
    overlay.id='freeAiSetupOverlay';overlay.className='free-ai-setup';
    overlay.innerHTML='<div class="free-ai-card"><h2>Free AI setup</h2><p>Interpreter AI now uses Gemini 3.7 Flash free tier by default so Puter balance cannot block the app. Create a free Google AI Studio key, paste it once, and the key stays on this device.</p><input id="freeAiKeyInput" class="free-ai-key" type="password" autocomplete="off" placeholder="Paste Gemini API key"><div class="free-ai-actions"><button id="freeAiSave" class="free-ai-save" type="button">Save free key</button><button id="freeAiOpen" class="free-ai-link" type="button">Get free key</button><button id="freeAiClose" class="free-ai-close" type="button">Close</button></div><p class="free-ai-note">No Puter payment is required for this mode. Google free-tier limits still apply.</p></div>';
    document.body.appendChild(overlay);
    document.getElementById('freeAiSave').onclick=()=>{const value=String(document.getElementById('freeAiKeyInput').value||'').trim();const ok=window.InterpreterNative?.setFreeAiApiKey?.(value)===true;if(!ok){$('chatError').textContent='That key format does not look valid.';return}overlay.classList.remove('show');setStatus('Free AI · ready','ok');$('chatError').textContent='';};
    document.getElementById('freeAiOpen').onclick=()=>window.InterpreterNative?.openFreeAiKeyPage?.();
    document.getElementById('freeAiClose').onclick=()=>overlay.classList.remove('show');
    return overlay;
  }
  function openFreeAiSetup(){const overlay=ensureFreeAiSetupUi();const input=document.getElementById('freeAiKeyInput');if(input&&!input.value)input.value=freeAiKey();overlay.classList.add('show');setTimeout(()=>input?.focus(),80)}
  window.openFreeAiSetup=openFreeAiSetup;
  const plainTextContent=value=>{if(typeof value==='string')return value;if(Array.isArray(value))return value.map(part=>typeof part==='string'?part:(part?.type==='text'?part.text:'')).filter(Boolean).join('\n');return String(value??'')};
  const geminiParts=value=>{const source=Array.isArray(value)?value:[{type:'text',text:String(value??'')}];const parts=[];for(const part of source){if(typeof part==='string'){if(part)parts.push({text:part});continue}if(part?.type==='text'){if(part.text)parts.push({text:String(part.text)});continue}if(part?.type==='interpreter_media'&&part.data){parts.push({inline_data:{mime_type:part.mime_type||'application/octet-stream',data:part.data}})}}return parts};
  async function geminiChat(messages,options={}){
    const key=freeAiKey();
    if(!key){openFreeAiSetup();throw new Error('Free AI setup is required. Add a free Google AI Studio key once to continue without Puter payments.')}
    const system=messages.filter(m=>m?.role==='system').map(m=>plainTextContent(m.content)).filter(Boolean).join('\n\n');
    const contents=[];
    for(const message of messages){if(!message||message.role==='system')continue;const parts=geminiParts(message.content);if(!parts.length)continue;contents.push({role:message.role==='assistant'?'model':'user',parts})}
    const body={contents,generationConfig:{maxOutputTokens:Math.min(Number(options.max_tokens)||900,2200)}};
    if(system)body.system_instruction={parts:[{text:system}]};
    const response=await fetch('https://generativelanguage.googleapis.com/v1beta/models/'+FREE_AI_MODEL+':generateContent',{method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':key},body:JSON.stringify(body)});
    const data=await response.json().catch(()=>({}));
    if(!response.ok){const upstream=data?.error?.message||('HTTP '+response.status);if(response.status===429)throw new Error('The free Gemini rate limit was reached. Wait for the free quota window to reset and try again.');if(response.status===400||response.status===401||response.status===403)throw new Error('The free Gemini key was rejected. Open Free AI setup and replace the key.');throw new Error(upstream)}
    const answer=(data?.candidates?.[0]?.content?.parts||[]).map(part=>part?.text||'').join('').trim();
    if(!answer)throw new Error('The free AI returned an empty response.');
    setStatus('Free AI · ready','ok');
    return {message:{content:answer},text:answer};
  }
  window.__interpreterAiRequest=geminiChat;

'''
        text = text.replace(anchor, provider + anchor, 1)

    old_connect = "async function connectAi(){if(!window.puter){setStatus('No internet connection','bad');return false}try{if(!puter.auth.isSignedIn()){setStatus('Connecting…');await puter.auth.signIn({attempt_temp_user_creation:true})}setStatus('Online · ready','ok');return true}catch(e){setStatus('Connection needed','bad');$('chatError').textContent='Could not connect: '+(e?.message||e);return false}}async function ensureConnected(){if(window.puter&&puter.auth.isSignedIn()){setStatus('Online · ready','ok');return true}return connectAi()}"
    new_connect = "async function connectAi(){if(navigator.onLine===false){setStatus('No internet connection','bad');$('chatError').textContent='Interpreter AI needs an internet connection.';return false}if(!freeAiReady()){setStatus('Free AI setup needed','bad');openFreeAiSetup();return false}setStatus('Free AI · ready','ok');$('chatError').textContent='';return true}async function ensureConnected(){return connectAi()}"
    if old_connect in text:
        text = text.replace(old_connect, new_connect, 1)
    elif new_connect not in text:
        raise RuntimeError('connectAi block changed unexpectedly')

    text = text.replace("const r=await puter.ai.chat([{role:'system',content:system},...history.slice(-8),{role:'user',content:text}],{model:CHAT_MODEL,max_tokens:650,temperature:.24});", "const r=await window.__interpreterAiRequest([{role:'system',content:system},...history.slice(-8),{role:'user',content:text}],{model:FREE_AI_MODEL,max_tokens:650});")
    text = text.replace("const r=await puter.ai.chat([{role:'system',content:'You are a rigorous professional interpreter-performance evaluator. Do not invent evidence.'},{role:'user',content:prompt}],{model:EVALUATION_MODEL,max_tokens:1400,temperature:.15});", "const r=await window.__interpreterAiRequest([{role:'system',content:'You are a rigorous professional interpreter-performance evaluator. Do not invent evidence.'},{role:'user',content:prompt}],{model:FREE_AI_MODEL,max_tokens:1400});")

    old_init = "function initialize(){renderHistory();const input=$('chatInput');input.addEventListener('input',()=>{resizeComposer();updateSendState()});input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();sendChat()}});resizeComposer();updateSendState();if(!window.puter)setStatus('No internet connection','bad');else if(puter.auth.isSignedIn())setStatus('Online · ready','ok');else setStatus('Ready to connect')}"
    new_init = "function initialize(){renderHistory();const input=$('chatInput');input.addEventListener('input',()=>{resizeComposer();updateSendState()});input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();sendChat()}});resizeComposer();updateSendState();if(navigator.onLine===false)setStatus('No internet connection','bad');else if(freeAiReady())setStatus('Free AI · ready','ok');else setStatus('Free AI setup needed')}"
    if old_init in text:
        text = text.replace(old_init, new_init, 1)
    elif new_init not in text:
        raise RuntimeError('initialize block changed unexpectedly')

    # Native Android TTS is free and avoids Puter text-to-speech billing/popups.
    text = re.sub(
        r"  \(\(\)=>\{const original=window\.InterpreterNative;if\(!original\)return;let activeAudio=null;.*?\}\)\(\);\n\n  \(\(\)=>\{const icons=",
        "  (()=>{const original=window.InterpreterNative;if(!original)return;const naturalSpeak=(text,lang)=>{const clean=String(text||'').trim().slice(0,8000);if(!clean)return false;return original.speakText?.(clean,lang)===true};window.playNaturalInterpreterVoice=naturalSpeak;window.stopNaturalInterpreterVoice=()=>{try{original.stopSpeaking?.()}catch(_){}}})();\n\n  (()=>{const icons=",
        text,
        count=1,
        flags=re.S
    )

    HTML.write_text(text, encoding='utf-8')


def patch_attachments():
    text = HTML.read_text(encoding='utf-8')

    # Media stays local and is encoded directly for Gemini instead of Puter storage/AI.
    old_remote = re.search(r"  const safeFileName = name => .*?\n  input\.onchange = async \(\) => \{", text, flags=re.S)
    if old_remote and 'const fileToBase64' not in text:
        replacement = r'''  const fileToBase64 = async file => {
    const MAX_MEDIA_BYTES = 18 * 1024 * 1024;
    if (file.size > MAX_MEDIA_BYTES) throw new Error('This media file is larger than 18 MB. Use a shorter/compressed file for free direct AI analysis.');
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    return btoa(binary);
  };

  input.onchange = async () => {'''
        text = text[:old_remote.start()] + replacement + text[old_remote.end():]

    old_batch = "const batch = files.map(file => ({name:file.name,mime:file.type||'',kind:classifyFile(file),status:'uploading',path:null,localText:null,error:null}));"
    new_batch = "const batch = files.map(file => ({name:file.name,mime:file.type||'',kind:classifyFile(file),status:'uploading',data:null,localText:null,error:null}));"
    text = text.replace(old_batch, new_batch)
    text = text.replace("        } else {\n          item.path = await writeRemoteMedia(file, index);\n        }", "        } else {\n          item.data = await fileToBase64(file);\n        }")
    text = text.replace("const readyItems = () => pending.filter(item => item.status === 'ready' && (item.localText || item.path));", "const readyItems = () => pending.filter(item => item.status === 'ready' && (item.localText || item.data));")

    # Audio is sent natively to Gemini; no second speech-to-text AI call.
    text = re.sub(r"\n    if \(item\.kind === 'audio'.*?\n    \}\n    return '';", "\n    return '';", text, count=1, flags=re.S)

    text = text.replace("  const installPuterWrapper = () => {\n    const ai = window.puter?.ai;\n    if (!ai || typeof ai.chat !== 'function' || ai.chat.__attachmentWrapper) return;\n    const original = ai.chat.bind(ai);\n    const wrapped = async function(messages, options) {\n      if (!armNextChat) return original(messages, options);", "  const installProviderWrapper = () => {\n    const current = window.__interpreterAiRequest;\n    if (typeof current !== 'function' || current.__attachmentWrapper) return;\n    const wrapped = async function(messages, options) {\n      if (!armNextChat) return current(messages, options);")
    text = text.replace("      if (!ready.length || !Array.isArray(messages)) return original(messages, options);", "      if (!ready.length || !Array.isArray(messages)) return current(messages, options);")
    text = text.replace("      if (index < 0) return original(messages, options);", "      if (index < 0) return current(messages, options);")
    text = text.replace("      ready.filter(item => item.kind === 'vision' && item.path).forEach(item => content.push({type:'file',puter_path:item.path}));", "      ready.filter(item => (item.kind === 'vision' || item.kind === 'audio') && item.data).forEach(item => content.push({type:'interpreter_media',mime_type:item.mime||'application/octet-stream',data:item.data,name:item.name}));")
    text = text.replace("      return original(cloned, options);\n    };\n    wrapped.__attachmentWrapper = true;\n    ai.chat = wrapped;\n  };\n\n  installChatWrapper();\n  installPuterWrapper();", "      return current(cloned, options);\n    };\n    wrapped.__attachmentWrapper = true;\n    window.__interpreterAiRequest = wrapped;\n  };\n\n  installChatWrapper();\n  installProviderWrapper();")
    text = text.replace("    installChatWrapper();\n    installPuterWrapper();", "    installChatWrapper();\n    installProviderWrapper();")

    HTML.write_text(text, encoding='utf-8')


def patch_bootstrap():
    text = BOOT.read_text(encoding='utf-8')
    old_ready = '''  const sdkReady = () => Boolean(window.puter?.ai?.chat);

  // Deliberately synchronous: the first puter.ai.chat() invocation must still happen inside the
  // original user action so Android WebView retains transient activation for first-use auth.
  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV5-LIVE', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!sdkReady()) {
      setConnectionStatus('AI service unavailable · AIV5-LIVE', 'bad');
      setError('Interpreter AI could not load its online service. Check your connection and try again.');
      return false;
    }

    setConnectionStatus('Online AI · ready · AIV5-LIVE', 'ok');
    setError('');
    return true;
  };'''
    new_ready = '''  const freeProviderReady = () => {
    try { return String(window.InterpreterNative?.getFreeAiApiKey?.() || '').trim().length >= 20; }
    catch (_) { return false; }
  };

  const providerReady = () => {
    if (navigator.onLine === false) {
      setConnectionStatus('No internet connection · AIV5-LIVE', 'bad');
      setError('Interpreter AI needs an internet connection.');
      return false;
    }
    if (!freeProviderReady()) {
      setConnectionStatus('Free AI setup needed · AIV5-LIVE', 'bad');
      setError('Add a free Google AI Studio key once to use Interpreter AI without Puter payments.');
      try { window.openFreeAiSetup?.(); } catch (_) {}
      return false;
    }

    setConnectionStatus('Free AI · ready · AIV5-LIVE', 'ok');
    setError('');
    return true;
  };'''
    if old_ready in text:
        text = text.replace(old_ready, new_ready, 1)
    elif new_ready not in text:
        raise RuntimeError('bootstrap provider block changed')

    text = re.sub(
        r"      // Voice recognition callbacks are not browser user gestures\..*?\n      native\?\.setVoiceLanguage",
        "      if (typeof window.ensureConnected === 'function' && !(await window.ensureConnected())) {\n        callStatus('Connection failed', 'Complete Free AI setup and try again.');\n        window.__voiceCallActive = false;\n        return;\n      }\n\n      native?.setVoiceLanguage",
        text,
        count=1,
        flags=re.S
    )
    BOOT.write_text(text, encoding='utf-8')


def verify():
    ai = AI.read_text(encoding='utf-8')
    html = HTML.read_text(encoding='utf-8')
    boot = BOOT.read_text(encoding='utf-8')
    required = {
        'native free key bridge': 'getFreeAiApiKey' in ai and 'openFreeAiKeyPage' in ai,
        'free Gemini transport': "FREE_AI_MODEL='gemini-3.7-flash'" in html and 'generativelanguage.googleapis.com' in html,
        'free setup UI': 'freeAiSetupOverlay' in html and 'Get free key' in html,
        'no core Puter chat': "const r=await puter.ai.chat" not in html,
        'local media encoding': 'const fileToBase64' in html and "type:'interpreter_media'" in html,
        'no Puter audio STT': 'puter.ai.speech2txt' not in html,
        'provider attachment wrapper': 'installProviderWrapper' in html and 'installPuterWrapper' not in html,
        'free voice provider': 'freeProviderReady' in boot and 'Complete Free AI setup' in boot,
    }
    failed = [name for name, ok in required.items() if not ok]
    if failed:
        raise RuntimeError('Verification failed: ' + ', '.join(failed))


if __name__ == '__main__':
    patch_native_bridge()
    patch_html_core()
    patch_attachments()
    patch_bootstrap()
    verify()
