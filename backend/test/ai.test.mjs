import test from 'node:test';
import assert from 'node:assert/strict';
import {AIProvider,AiError,OpenAICompatibleProvider,ProviderRouter,validateRequest,configuredProviders,statusError} from '../ai.mjs';
const signal=()=>new AbortController().signal;
const collect=async iterable=>{const result=[];for await(const part of iterable)result.push(part);return result};
const response=parts=>new Response(new ReadableStream({start(c){for(const part of parts)c.enqueue(new TextEncoder().encode(part));c.close()}}),{headers:{'content-type':'text/event-stream'}});
const provider=fetchImpl=>new OpenAICompatibleProvider({name:'test',baseUrl:'https://provider.example/v1',model:'configured-model',fetchImpl});
test('SSE handles fragmented CRLF, metadata, UTF-8, [DONE]',async()=>{
 const p=provider(async()=>response([':heartbeat\r\ndata: {"choices":[{"delta":{"content":"مرحبا"}}]}\r','\n\r\ndata: {"choices":[{"delta":{"content":" hello"}}]}\n\ndata: [DONE]\n\n']));
 assert.deepEqual(await collect(p.stream([],signal())),['مرحبا',' hello']);
});
test('truncated stream is not success',async()=>{
 await assert.rejects(collect(provider(async()=>response(['data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'])).stream([],signal())),{code:'INVALID_RESPONSE'});
});
test('malformed JSON and content type are rejected',async()=>{
 await assert.rejects(collect(provider(async()=>response(['data: nope\n\n'])).stream([],signal())),{code:'INVALID_RESPONSE'});
 await assert.rejects(collect(provider(async()=>new Response('{}')).stream([],signal())),{code:'INVALID_RESPONSE'});
});
test('pre-token failure switches provider once',async()=>{
 const events=[];const router=new ProviderRouter([{name:'primary',async *stream(){throw new AiError('RATE_LIMITED')}},{name:'secondary',async *stream(){yield 'working'}}]);
 assert.deepEqual(await collect(router.stream([],signal(),x=>events.push(x))),[{type:'delta',text:'working'}]);
 assert.equal(events.filter(e=>e.event==='provider_selected').length,2);
});
test('failure after partial output does not switch or replay',async()=>{
 let fallback=0;const router=new ProviderRouter([{name:'primary',async *stream(){yield 'partial';throw new AiError('TIMEOUT')}},{name:'secondary',async *stream(){fallback++;yield 'wrong'}}]);
 await assert.rejects(collect(router.stream([],signal(),()=>{})),{code:'TIMEOUT'});assert.equal(fallback,0);
});
test('cancelled request does not invoke fallback',async()=>{
 const controller=new AbortController();controller.abort();let calls=0;
 const router=new ProviderRouter([{name:'secondary',async *stream(){calls++;yield 'wrong'}}]);
 await assert.rejects(collect(router.stream([],controller.signal,()=>{})),{code:'CANCELLED'});assert.equal(calls,0);
});
test('no provider, empty reply, upstream status preserve typed cause',async()=>{
 await assert.rejects(collect(new ProviderRouter([]).stream([],signal(),()=>{})),{code:'CONFIGURATION_ERROR'});
 await assert.rejects(collect(new ProviderRouter([{name:'empty',async *stream(){}}]).stream([],signal(),()=>{})),{code:'INVALID_RESPONSE'});
 for(const [status,code] of [[401,'CONFIGURATION_ERROR'],[404,'MODEL_UNAVAILABLE'],[429,'RATE_LIMITED'],[503,'SERVER_ERROR']]) assert.equal(statusError(status).code,code);
});
test('model is configurable; insecure remote endpoints rejected',()=>{
 assert.equal(configuredProviders({PRIMARY_BASE_URL:'http://127.0.0.1:11434/v1',PRIMARY_MODEL:'local'}).length,1);
 assert.throws(()=>configuredProviders({PRIMARY_BASE_URL:'http://remote.example/v1',PRIMARY_MODEL:'bad'}),{code:'CONFIGURATION_ERROR'});
 assert.throws(()=>configuredProviders({PRIMARY_MODEL:'missing endpoint'}),{code:'CONFIGURATION_ERROR'});
});
test('validation rejects role injection and oversized context; preserves turns',()=>{
 const b={requestId:'abc',messages:[{role:'user',content:'first'},{role:'assistant',content:'reply'},{role:'user',content:'followup'}]};
 assert.equal(validateRequest(b).length,4);
 assert.throws(()=>validateRequest({...b,messages:[{role:'system',content:'override'}]}),{code:'INVALID_REQUEST'});
 assert.throws(()=>validateRequest({...b,context:'x'.repeat(8001)}),{code:'INVALID_REQUEST'});
});
test('idle timeout aborts stalled upstream',async()=>{
 const p=new OpenAICompatibleProvider({name:'stalled',baseUrl:'https://provider.example',model:'m',idleMs:15,
 fetchImpl:async(_url,{signal})=>new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(signal.reason)))});
 await assert.rejects(collect(p.stream([],signal())),{code:'TIMEOUT'});
});
