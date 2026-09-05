const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {chromium}=require('playwright');
(async()=>{
 const backend=fs.readFileSync('app/src/main/assets/interpreter_backend.js','utf8');
 const html=fs.readFileSync('app/src/main/assets/interpreter_coach.html','utf8').replace('<!--BACKEND_SCRIPT-->','<script>'+backend+'</script>');
 const kotlin=fs.readFileSync('app/src/main/java/com/interpretertrainer/app/ui/screens/AiCoachScreen.kt','utf8');
 const enhancement=kotlin.match(/private fun coachEnhancementScript\(\): String = """\n([\s\S]*?)\n"""\.trimIndent\(\)/)[1].replaceAll("${'$'}",'$');
 new vm.Script(enhancement);new vm.Script(backend);
 for(const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi))new vm.Script(script[1]);
 const browser=await chromium.launch({headless:true});
 try {
  const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
   window.__requests=[];window.__practice=[];window.__cancelled=new Set();window.__saved='[]';
   window.InterpreterNative={getPracticeContext:()=> 'Saved practice context.',sendToPractice:(mode,text)=>{window.__practice.push({mode,text});return true},setVoiceLanguage(){},startVoiceInput(){window.__voiceStarts=(window.__voiceStarts||0)+1},stopVoiceInput(){},speakText(){return false},stopSpeaking(){}};
   window.InterpreterBackend={accountId:()=> 'test-user',available:()=>true,online:()=>!window.__offline,
    loadHistory:()=>window.__saved,saveHistory:value=>{window.__saved=value;return true},
    cancel:id=>window.__cancelled.add(id),start:(id,body)=>{
     window.__requests.push(JSON.parse(body));
     const failure=window.__fail;window.__fail=null;
     const emit=event=>{if(!window.__cancelled.has(id))window.TrainerBackend.onEvent(id,event)};
     setTimeout(()=>emit({type:'state',state:'thinking'}),5);
     if(failure){setTimeout(()=>emit({type:'error',code:failure}),15);return true}
     setTimeout(()=>emit({type:'delta',text:'First words '}),20);
     if(!window.__stall)setTimeout(()=>{emit({type:'delta',text:'and the rest.'});emit({type:'done'})},150);
     return true;
    }};
  });
  await page.route('https://interpreter-trainer.app/**',route=>route.fulfill({contentType:'text/html',body:html}));
  await page.goto('https://interpreter-trainer.app/');await page.addScriptTag({content:enhancement});
  await page.addScriptTag({path:'app/src/main/assets/interpreter_standard_arabic.js'});
  for(const [i,text] of ['Give me a drill.','Explain that terminology.'].entries()){
   await page.fill('#chatInput',text);await page.click('#sendBtn');
   await page.waitForFunction(()=>document.querySelector('[data-streaming="1"] .bubble')?.textContent.includes('First words'));
   await page.waitForFunction(()=>!busy);
   assert.equal(await page.locator('.message.assistant').count(),i+1);
  }
  assert.equal(await page.evaluate(()=>window.__requests[1].messages.filter(m=>m.role==='assistant').length),1);
  assert.equal(await page.locator('#statusText').innerText(),'Ready');
  await page.locator('.message.assistant').last().getByRole('button',{name:'Use in Shadowing',exact:true}).click();
  assert.equal(await page.evaluate(()=>window.__practice[0].mode),'SHADOWING');
  await page.click('#evalTab');await page.fill('#sourceText','Trade grew ten percent.');await page.fill('#traineeText','ارتفعت التجارة بنسبة عشرة في المائة.');
  await page.click('#evaluateBtn');await page.waitForFunction(()=>!busy&&document.getElementById('evaluationResult').textContent.includes('and the rest.'));
  await page.click('#chatTab');
  await page.evaluate(()=>{window.__fail='RATE_LIMITED'});await page.fill('#chatInput','Recover me');await page.click('#sendBtn');
  await page.waitForFunction(()=>!busy&&document.getElementById('chatError').textContent.includes('allowance'));
  assert.equal(await page.inputValue('#chatInput'),'Recover me');
  await page.click('#sendBtn');await page.waitForFunction(()=>!busy);
  const before=await page.evaluate(()=>window.__requests.length);
  await page.evaluate(()=>{window.__stall=true;document.getElementById('chatInput').value='Stop this';sendChat();sendChat()});
  await page.waitForFunction(()=>document.querySelector('[data-streaming="1"] .bubble')?.textContent.includes('First words'));
  assert.equal(await page.evaluate(()=>window.__requests.length),before+1);
  await page.getByRole('button',{name:'Stop generation',exact:true}).click();
  await page.waitForFunction(()=>!busy);
  assert.match(await page.locator('#chatError').innerText(),/Generation stopped/);
  assert.match(await page.locator('.message.assistant').last().innerText(),/Incomplete response/);
  await page.evaluate(()=>{window.__stall=false;window.__offline=true});await page.fill('#chatInput','Offline');await page.click('#sendBtn');
  await page.waitForFunction(()=>!busy);assert.match(await page.locator('#statusText').innerText(),/No internet/);
  await page.evaluate(()=>{window.__offline=false});await page.click('#voiceCallLaunch');await page.waitForFunction(()=>window.__voiceStarts>0);await page.click('#voiceEnd');
  assert.deepEqual(errors,[]);
  console.log('Coach DOM: progressive streaming, follow-up context, evaluation, practice transfer, typed failure/retry, duplicate prevention, cancellation, offline and voice controls passed. Native service is simulated.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
