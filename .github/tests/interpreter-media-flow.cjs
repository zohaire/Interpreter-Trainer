const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const scriptOf = path => [...fs.readFileSync(path,'utf8').matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).join('\n');
(async () => {
  const window = {};
  vm.runInNewContext(fs.readFileSync('app/src/main/assets/interpreter_sentence_queue.js','utf8'),{window});
  const spoken=[];let done=0,stops=0;
  const queue=window.createInterpreterSpeechQueue({speak:t=>{spoken.push(t);return true},stop:()=>stops++,onDone:()=>done++});
  queue.append('First sentence. Second');
  assert.deepEqual(spoken,['First sentence.']);
  queue.append(' sentence. Last fragment');queue.finish();
  assert.equal(done,0);queue.speechEnded();queue.speechEnded();
  assert.deepEqual(spoken,['First sentence.','Second sentence.','Last fragment']);
  queue.speechEnded();assert.equal(done,1);
  const cancelled=window.createInterpreterSpeechQueue({speak:t=>{spoken.push(t);return true},stop:()=>stops++,onDone:()=>done++});
  cancelled.append('Starting. Queued.');cancelled.cancel();cancelled.speechEnded();cancelled.append('Late.');cancelled.finish();
  assert.equal(spoken.at(-1),'Starting.');assert.equal(stops,1);assert.equal(done,1);

  const script=scriptOf('app/src/main/assets/interpreter_inline_generator.html');
  for (const fail of [false,true]) {
    const generated=[],errors=[],requests=[];
    const puter={auth:{isSignedIn:()=>true},ai:{
      chat:async()=>({message:{content:'A practice passage.'}}),
      txt2speech:async(text,options)=>{requests.push({text,options});if(fail)throw Error('Speech quota exhausted');return {src:'blob:audio'}}
    }};
    const win={puter,InlineAiNative:{onReady(){},onGenerated(){throw Error('Audio was requested')},onAudioGenerated:v=>generated.push(JSON.parse(v)),onError:v=>errors.push(v)}};
    class FileReader {readAsDataURL(blob){blob.arrayBuffer().then(b=>{this.result='data:audio/mpeg;base64,'+Buffer.from(b).toString('base64');this.onload()})}}
    vm.runInNewContext(script,{window:win,puter,Blob,FileReader,URL:{revokeObjectURL(){}},fetch:async()=>({ok:true,blob:async()=>new Blob(['audio bytes'])})});
    await win.generatePracticeText('Topic',600,'audio','sage');
    assert.equal(requests[0].options.voice,'sage');
    if(fail){assert.equal(generated.length,0);assert.match(errors[0],/quota/)}
    else {assert.equal(generated[0].text,'A practice passage.');assert.equal(Buffer.from(generated[0].data,'base64').toString(),'audio bytes');assert.deepEqual(errors,[])}
  }
  let state=1,plays=0;
  const playerWindow={};
  const ctx=vm.createContext({window:playerWindow,location:{origin:'https://interpreter-trainer.app'},YT:{Player:function(){return {getPlayerState:()=>state,playVideo:()=>plays++}}}});
  vm.runInContext(scriptOf('app/src/main/assets/interpreter_video_player.html').replace('__VIDEO_ID_JSON__','"test-video"'),ctx);
  vm.runInContext('onYouTubeIframeAPIReady()',ctx);
  playerWindow.prepareForRecognition();state=2;playerWindow.restoreAfterRecognition();assert.equal(plays,1);
  playerWindow.restoreAfterRecognition();assert.equal(plays,1);
  playerWindow.prepareForRecognition();playerWindow.restoreAfterRecognition();assert.equal(plays,1,'Do not start a video that was already paused');
  console.log('Sentence order/cancellation, audio generation/errors and video recovery passed (simulated SDK/player).');
})().catch(e=>{console.error(e);process.exit(1)});
