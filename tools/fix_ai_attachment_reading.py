from pathlib import Path
import re

path = Path('app/src/main/assets/interpreter_coach.html')
text = path.read_text(encoding='utf-8')

if "const DOC_READER_MODEL='claude-sonnet-4-6'" in text:
    raise SystemExit(0)

old_upload = r'''  const normalizeItems = value => Array\.isArray\(value\) \? value : value \? \[value\] : \[\];.*?  };\n\n  const blockWhileUploading'''
new_upload = r'''  const DOC_READER_MODEL='claude-sonnet-4-6';
  const MAX_LOCAL_TEXT=100000;
  const MAX_TOTAL_EXTRACTED=260000;
  const TEXT_EXTENSIONS=new Set(['txt','md','markdown','csv','tsv','json','jsonl','xml','html','htm','css','js','mjs','cjs','ts','tsx','jsx','kt','kts','java','py','rb','php','go','rs','c','cc','cpp','h','hpp','sh','bash','zsh','sql','yaml','yml','toml','ini','cfg','log','srt','vtt']);
  const AUDIO_EXTENSIONS=new Set(['mp3','wav','m4a','aac','ogg','oga','flac','opus','webm','amr','3gp']);
  const extOf=name=>String(name||'').toLowerCase().split('.').pop()||'';
  const classifyFile=file=>{
    const mime=String(file?.type||'').toLowerCase(),ext=extOf(file?.name);
    if(mime.startsWith('image/')||mime.startsWith('video/'))return'vision';
    if(mime.startsWith('audio/')||AUDIO_EXTENSIONS.has(ext))return'audio';
    if(mime.startsWith('text/')||TEXT_EXTENSIONS.has(ext))return'text';
    return'document';
  };
  const responseText=result=>{
    if(typeof result==='string')return result.trim();
    if(typeof result?.text==='string')return result.text.trim();
    const content=result?.message?.content;
    if(typeof content==='string')return content.trim();
    if(Array.isArray(content))return content.map(part=>typeof part==='string'?part:(part?.text||'')).join('').trim();
    return'';
  };
  const speechText=result=>typeof result==='string'?result.trim():String(result?.text||result?.transcript||'').trim();
  const safeFileName=name=>String(name||'file').replace(/[^a-zA-Z0-9._-]+/g,'_').slice(-120)||'file';

  const writeOneFile=async(file,index)=>{
    if(window.puter?.fs?.write){
      const target=`InterpreterTrainerUploads/${Date.now()}-${index}-${safeFileName(file.name)}`;
      const item=await puter.fs.write(target,file,{createMissingParents:true,dedupeName:true});
      if(!item?.path)throw new Error('Puter did not return a stored file path.');
      return item.path;
    }
    if(window.puter?.fs?.upload){
      const result=await puter.fs.upload([file],'./InterpreterTrainerUploads',{dedupeName:true});
      const item=Array.isArray(result)?result[0]:result;
      if(!item?.path)throw new Error('Puter did not return a stored file path.');
      return item.path;
    }
    throw new Error('File storage service is unavailable.');
  };

  input.onchange = async () => {
    const files = [...(input.files || [])];
    input.value = '';
    if (!files.length) return;
    if (!window.puter?.fs) {
      error('File upload service is not available. Check the internet connection and try again.');
      return;
    }

    const batch = files.map(file => ({
      name:file.name,
      mime:file.type||'',
      kind:classifyFile(file),
      status:'uploading',
      path:null,
      localText:null,
      error:null
    }));
    pending.push(...batch);
    uploading = true;
    redraw();
    error('');

    try {
      await Promise.all(batch.map(async(item,i)=>{
        if(item.kind==='text'){
          try{item.localText=(await files[i].text()).slice(0,MAX_LOCAL_TEXT)}catch(_){item.localText=null}
        }
        item.path=await writeOneFile(files[i],i);
        item.status='ready';
      }));
    } catch (e) {
      batch.filter(item=>item.status!=='ready').forEach(item => {
        item.status = 'error';
        item.error = e?.message || String(e);
      });
      error('One or more files could not be prepared for AI reading. Choose the failed files again.');
    } finally {
      uploading = false;
      redraw();
    }
  };

  const blockWhileUploading'''
text2, count = re.subn(old_upload, new_upload, text, count=1, flags=re.S)
if count != 1:
    raise RuntimeError('Upload block anchor changed; refusing unsafe edit')
text = text2

old_wrapper = r'''  const installPuterWrapper = \(\) => \{.*?    ai\.chat = wrapped;\n  };'''
new_wrapper = r'''  const extractReadableContext=async(item,original)=>{
    if(item.kind==='text'&&item.localText){
      return `FILE: ${item.name}\nTYPE: text\nCONTENT:\n${item.localText}`;
    }

    if(item.kind==='audio'&&window.puter?.ai?.speech2txt){
      try{
        const transcript=await puter.ai.speech2txt(item.path,{model:'gpt-4o-mini-transcribe',response_format:'text'});
        const text=speechText(transcript);
        if(text)return `FILE: ${item.name}\nTYPE: audio transcript\nCONTENT:\n${text}`;
      }catch(e){item.readError='Audio transcription failed: '+(e?.message||e)}
    }

    if(item.kind==='document'){
      try{
        const result=await original([
          {role:'user',content:[
            {type:'file',puter_path:item.path},
            {type:'text',text:'Extract the readable content of this file for another AI. Preserve headings, paragraphs, names, dates, numbers, tables and lists as plain text. Do not answer questions and do not invent content. If the file is very long, retain as much substantive content as possible while preserving its structure.'}
          ]}
        ],{model:DOC_READER_MODEL,max_tokens:6500,temperature:0});
        const extracted=responseText(result);
        if(extracted)return `FILE: ${item.name}\nTYPE: extracted document\nCONTENT:\n${extracted}`;
        item.readError='The document reader returned no readable text.';
      }catch(e){item.readError='Document reading failed: '+(e?.message||e)}
    }

    return'';
  };

  const installPuterWrapper = () => {
    const ai = window.puter?.ai;
    if (!ai || typeof ai.chat !== 'function' || ai.chat.__attachmentWrapper) return;
    const original = ai.chat.bind(ai);
    const wrapped = async function(messages, options) {
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

      const contexts=(await Promise.all(ready.map(item=>extractReadableContext(item,original))))
        .filter(Boolean);
      let used=0;
      const boundedContexts=[];
      for(const context of contexts){
        if(used>=MAX_TOTAL_EXTRACTED)break;
        const slice=context.slice(0,MAX_TOTAL_EXTRACTED-used);
        boundedContexts.push(slice);
        used+=slice.length;
      }

      const originalContent = cloned[index].content;
      const originalParts = Array.isArray(originalContent)
        ? [...originalContent]
        : [{ type:'text', text:String(originalContent ?? '') }];
      const content = [];

      // Keep the original file attached as Puter documents, and also provide extracted/transcribed
      // text so Qwen3.8 Max can reason over formats that are not native Qwen input modalities.
      ready.forEach(item => content.push({ type:'file', puter_path:item.path }));
      if(boundedContexts.length){
        content.push({
          type:'text',
          text:'The following attachment content was extracted or transcribed for reliable reading. Treat it as source material from the attached files:\n\n'+boundedContexts.join('\n\n--- NEXT FILE ---\n\n')
        });
      }
      content.push(...originalParts);
      cloned[index] = {...cloned[index], content};

      const unread=ready.filter(item=>item.readError);
      if(unread.length){
        error('Some attachment formats could not be converted to text, but their original files are still attached: '+unread.map(item=>item.name).join(', '));
      }

      pending = pending.filter(item => item.status !== 'ready');
      redraw();
      return original(cloned, options);
    };
    wrapped.__attachmentWrapper = true;
    ai.chat = wrapped;
  };'''
text2, count = re.subn(old_wrapper, new_wrapper, text, count=1, flags=re.S)
if count != 1:
    raise RuntimeError('Puter wrapper anchor changed; refusing unsafe edit')
text = text2

path.write_text(text, encoding='utf-8')

required = [
    "const DOC_READER_MODEL='claude-sonnet-4-6'",
    "puter.ai.speech2txt",
    "item.localText",
    "type:'file',puter_path:item.path",
    "The following attachment content was extracted or transcribed for reliable reading",
]
missing = [marker for marker in required if marker not in text]
if missing:
    raise RuntimeError('Attachment migration incomplete: ' + ', '.join(missing))
