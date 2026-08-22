from pathlib import Path
import re

path = Path('app/src/main/assets/interpreter_coach.html')
text = path.read_text(encoding='utf-8')

old_const = "  const FREE_AI_MODEL='gemini-3.7-flash';"
new_const = """  const FREE_AI_MODELS=['gemini-3.7-flash','gemini-3.6-flash','gemini-3.5-flash','gemini-3.5-flash-lite'];
  const FREE_AI_MODEL=FREE_AI_MODELS[0];
  const FREE_AI_LABELS={
    'gemini-3.7-flash':'Gemini 3.7 Flash',
    'gemini-3.6-flash':'Gemini 3.6 Flash',
    'gemini-3.5-flash':'Gemini 3.5 Flash',
    'gemini-3.5-flash-lite':'Gemini 3.5 Flash-Lite'
  };"""
if old_const not in text:
    raise SystemExit('FREE_AI_MODEL anchor not found')
text = text.replace(old_const, new_const, 1)

text = text.replace(
    'Free AI · Gemini 3.7 Flash · local documents + multimodal media',
    'Free AI · automatic Gemini fallback · local documents + multimodal media',
    1,
)

replacement = r'''  async function geminiChat(messages,options={}){
    const key=freeAiKey();
    if(!key){openFreeAiSetup();throw new Error('Free AI setup is required. Add a free Google AI Studio key once to continue without Puter payments.')}
    const system=messages.filter(m=>m?.role==='system').map(m=>plainTextContent(m.content)).filter(Boolean).join('\n\n');
    const contents=[];
    for(const message of messages){if(!message||message.role==='system')continue;const parts=geminiParts(message.content);if(!parts.length)continue;contents.push({role:message.role==='assistant'?'model':'user',parts})}
    const body={contents,generationConfig:{maxOutputTokens:Math.min(Number(options.max_tokens)||900,2200)}};
    if(system)body.system_instruction={parts:[{text:system}]};

    const preferred=String(options.model||'');
    const models=FREE_AI_MODELS.includes(preferred)
      ? [preferred,...FREE_AI_MODELS.filter(model=>model!==preferred)]
      : [...FREE_AI_MODELS];
    let lastFailure='The free AI request failed.';

    for(let index=0;index<models.length;index+=1){
      const model=models[index];
      const label=FREE_AI_LABELS[model]||model;
      if(index>0)setStatus('Free AI · switching to '+label+'…','idle');
      let response;
      let data={};
      try{
        response=await fetch('https://generativelanguage.googleapis.com/v1beta/models/'+model+':generateContent',{
          method:'POST',
          headers:{'Content-Type':'application/json','x-goog-api-key':key},
          body:JSON.stringify(body)
        });
        data=await response.json().catch(()=>({}));
      }catch(error){
        lastFailure=error?.message||String(error);
        if(index<models.length-1)continue;
        throw new Error('Free AI could not reach Google after trying all fallback models. Check the internet connection and try again.');
      }

      if(!response.ok){
        const upstream=data?.error?.message||('HTTP '+response.status);
        const lowered=String(upstream).toLowerCase();
        const keyProblem=(response.status===401||response.status===403||/api key|key not valid|invalid key|permission denied|unauthenticated|forbidden/.test(lowered));
        if(keyProblem){throw new Error('The free Gemini key was rejected. Open Free AI setup and replace the key.')}
        const retryable=(response.status===404||response.status===408||response.status===409||response.status===429||response.status>=500||/high demand|overload|overloaded|capacity|temporar|resource exhausted|unavailable|try again later|deadline/.test(lowered));
        lastFailure=upstream;
        if(retryable&&index<models.length-1)continue;
        if(response.status===429||/high demand|overload|capacity|resource exhausted/.test(lowered)){
          throw new Error('All free Gemini fallback models are currently busy or rate-limited. Please try again shortly.')
        }
        throw new Error(upstream);
      }

      const answer=(data?.candidates?.[0]?.content?.parts||[]).map(part=>part?.text||'').join('').trim();
      if(!answer){
        lastFailure='The free AI returned an empty response from '+label+'.';
        if(index<models.length-1)continue;
        throw new Error(lastFailure);
      }

      window.__interpreterAiActiveModel=model;
      setStatus('Free AI · '+label+' · ready','ok');
      const meta=document.querySelector('.composer-meta');
      if(meta)meta.textContent='Free AI · '+label+' · automatic fallback · local documents + multimodal media';
      return {message:{content:answer},text:answer,model};
    }
    throw new Error(lastFailure);
  }
  window.__interpreterAiRequest=geminiChat;'''

pattern = re.compile(r"  async function geminiChat\(messages,options=\{\}\)\{.*?\n  \}\n  window\.__interpreterAiRequest=geminiChat;", re.S)
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f'geminiChat replacement count={count}')

path.write_text(text, encoding='utf-8')
print('Added automatic free Gemini failover chain.')
