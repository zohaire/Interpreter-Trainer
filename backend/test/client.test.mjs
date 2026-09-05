import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const code=fs.readFileSync(new URL('../../app/src/main/assets/interpreter_backend.js',import.meta.url),'utf8');
function fixture(mode='ok'){
 let calls=0,cancels=0; const events=[];
 const window={addEventListener(){},InterpreterBackend:{available:()=>true,online:()=>mode!=='offline',
 start(id){calls++;setTimeout(()=>{
   window.TrainerBackend.onEvent(id,{type:'state',state:'thinking'});
   if(mode==='error')window.TrainerBackend.onEvent(id,{type:'error',code:'RATE_LIMITED'});
   else {window.TrainerBackend.onEvent(id,{type:'delta',text:'مرحبا'});if(mode!=='stall')window.TrainerBackend.onEvent(id,{type:'done'})}
 },0);return true},cancel(){cancels++}}};
 const context=vm.createContext({window,setTimeout,clearTimeout,setStatus:s=>events.push(s),nativePracticeContext:()=>''});vm.runInContext(code,context);
 return {api:window.TrainerBackend,events,get calls(){return calls},get cancels(){return cancels}};
}
const messages=[{role:'user',content:'hi'}];
test('client streams progressively then clears in-flight request',async()=>{
 const f=fixture();const stream=await f.api.chat(messages,{stream:true});assert.equal((await stream.next()).value.text,'مرحبا');assert.equal((await stream.next()).done,true);
 assert.equal((await f.api.chat(messages)).message.content,'مرحبا');assert.equal(f.calls,2);
 assert.ok(f.events.includes('Thinking…'));assert.ok(f.events.includes('Generating…'));
});
test('client rejects duplicate requests and cancels a pending read',async()=>{
 const f=fixture('stall');const stream=await f.api.chat(messages,{stream:true});await stream.next();
 await assert.rejects(f.api.chat(messages),{code:'BUSY'});const pending=stream.next();f.api.cancel();
 await assert.rejects(pending,{code:'CANCELLED'});assert.equal(f.calls,1);assert.equal(f.cancels,1);
});
test('client typed error does not leave a permanently pending request',async()=>{
 const f=fixture('error');await assert.rejects(f.api.chat(messages),{code:'RATE_LIMITED'});
 await assert.rejects(f.api.chat(messages),{code:'RATE_LIMITED'});assert.equal(f.calls,2);
});
test('offline client avoids backend call',async()=>{const f=fixture('offline');await assert.rejects(f.api.chat(messages),{code:'NETWORK_UNAVAILABLE'});assert.equal(f.calls,0)});
