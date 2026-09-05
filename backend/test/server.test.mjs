import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createServer} from '../server.mjs';
import {AiError} from '../ai.mjs';
async function fixture(t,options={}) {
 const server=createServer({verifyToken:async token=>{if(token!=='test-token')throw Error();return {uid:'one',email_verified:true}},router:{async *stream(){yield {type:'delta',text:'reply'}}},...options});
 server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>{server.closeAllConnections();server.close()});
 const url=`http://127.0.0.1:${server.address().port}/v1/chat`;
 return (body={requestId:'request1',messages:[{role:'user',content:'مرحبا'}]},token='test-token')=>fetch(url,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(body)});
}
test('authenticated request streams state, text, completion',async t=>{
 const request=await fixture(t);const response=await request();assert.equal(response.status,200);
 const events=(await response.text()).trim().split('\n').map(JSON.parse);assert.deepEqual(events.map(x=>x.type),['state','delta','done']);
});
test('invalid or unverified session cannot reach model',async t=>{
 let calls=0;const request=await fixture(t,{router:{async *stream(){calls++}}});assert.equal((await request(undefined,'wrong')).status,401);assert.equal(calls,0);
 const unverified=await fixture(t,{verifyToken:async()=>({uid:'u',firebase:{sign_in_provider:'password'},email_verified:false})});assert.equal((await unverified()).status,403);
});
test('malformed requests rejected before generation',async t=>{const request=await fixture(t);assert.equal((await request({messages:[]})).status,400)});
test('concurrent messages are rejected and recover after completion',async t=>{
 let release;const gate=new Promise(resolve=>{release=resolve});
 const request=await fixture(t,{router:{async *stream(){await gate;yield {type:'delta',text:'done'}}}});
 const first=await request();assert.equal((await request()).status,409);release();await first.text();assert.equal((await request()).status,200);
});
test('provider error is typed and never exposes internal detail',async t=>{
 const request=await fixture(t,{router:{async *stream(){throw new AiError('RATE_LIMITED',429,'secret diagnostic')}}});
 const body=await (await request()).text();assert.match(body,/RATE_LIMITED/);assert.doesNotMatch(body,/secret diagnostic/);
});
test('rate limiting prevents unbounded usage',async t=>{
 const request=await fixture(t);for(let i=0;i<12;i++) await (await request()).text();assert.equal((await request()).status,429);
});
test('overall deadline releases a stalled generation',async t=>{
 const request=await fixture(t,{generationMs:30,router:{async *stream(_m,signal){await new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(signal.reason)))}}});
 assert.match(await (await request()).text(),/TIMEOUT/);
});
