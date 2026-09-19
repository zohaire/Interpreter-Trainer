const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/interpreter_inline_generator.html', 'utf8');
const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).join('\n');
(async () => {
  for (const [response, expected, failure] of [
    [[{type:'text',text:'A passage.'}], 'A passage.'],
    [[{choices:[{delta:{content:'A passage.'}}]}], 'A passage.'],
    [[{type:'response.output_text.delta',delta:'A passage.'}], 'A passage.'],
    [{message:{content:[{type:'text',text:'A passage.'}]}}, 'A passage.'],
    [{choices:[{message:{content:'A passage.'}}]}, 'A passage.'],
    [[{type:'reasoning',text:'Hidden thinking'},{text:'A passage.'}], 'A passage.'],
    [[{type:'reasoning',text:'Hidden thinking'}], undefined, /empty passage/],
    [[], undefined, /empty passage/],
    [[{type:'error',error:{message:'Quota exhausted'}}], undefined, /Quota exhausted/]
  ]) {
    const generated=[], errors=[];
    const puter={auth:{isSignedIn:()=>true},ai:{chat:async(_,options)=>{
      assert.equal(options.model,'gpt-4.1-mini');
      assert.equal('reasoning_effort' in options,false);
      return Array.isArray(response) ? (async function*(){yield* response;})() : response;
    }}};
    const window={puter,InlineAiNative:{onReady(){},onGenerated:v=>generated.push(v),onError:v=>errors.push(v)}};
    const ctx=vm.createContext({window,puter});vm.runInContext(script,ctx);
    await window.generatePracticeText('Diplomacy',600);
    if(failure){assert.equal(generated.length,0);assert.match(errors[0],failure);}
    else {assert.deepEqual(generated,[expected]);assert.deepEqual(errors,[]);}
  }
  console.log('Inline response formats, reasoning exclusion, empty output and provider errors passed.');
})().catch(e=>{console.error(e);process.exit(1)});
