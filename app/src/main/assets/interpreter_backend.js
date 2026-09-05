(() => {
  const errors = {
    NETWORK_UNAVAILABLE:'No internet connection or service unreachable. Please retry.',
    AUTH_EXPIRED:'Your session expired. Sign out and sign in again.',
    EMAIL_UNVERIFIED:'Verify your email before using AI.',
    MODEL_UNAVAILABLE:'The model is unavailable. Please retry later.',
    RATE_LIMITED:'The AI allowance is temporarily exhausted. Please retry later.',
    SERVER_ERROR:'The AI service is temporarily unavailable.',
    INVALID_RESPONSE:'The AI response was interrupted. Please retry.',
    TIMEOUT:'The AI took too long. Please retry.',
    CONFIGURATION_ERROR:'The AI service is not configured in this build.',
    INVALID_REQUEST:'Please shorten your message and try again.',
    BUSY:'A response is already in progress.', CANCELLED:'Generation stopped.'
  };
  const makeError = code => Object.assign(new Error(errors[code] || errors.SERVER_ERROR),{code});
  let current = null;
  const state = text => { if(typeof setStatus==='function') setStatus(text); };
  async function chat(messages, options={}) {
    if(current) throw makeError('BUSY');
    if(!window.InterpreterBackend?.available()) throw makeError('CONFIGURATION_ERROR');
    if(!window.InterpreterBackend.online()) throw makeError('NETWORK_UNAVAILABLE');
    const id = 'chat_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    let queue=[], waiting=null, ended=false, failure=null, received=false;
    const finish = error => {
      if(ended) return;
      ended=true; failure=error; clearTimeout(deadline); clearTimeout(idle);
      if(current?.id===id) current=null;
      if(waiting) { const waiter=waiting; waiting=null; error?waiter.reject(error):waiter.resolve({done:true}); }
      if(error) window.InterpreterBackend.cancel(id);
    };
    let idle;
    const armIdle=()=>{clearTimeout(idle);idle=setTimeout(()=>finish(makeError('TIMEOUT')),30000)};
    const deadline=setTimeout(()=>finish(makeError('TIMEOUT')),85000);
    const iterator={
      [Symbol.asyncIterator](){return this},
      next(){ if(queue.length) return Promise.resolve({value:queue.shift(),done:false});
        if(failure) return Promise.reject(failure); if(ended)return Promise.resolve({done:true});
        return new Promise((resolve,reject)=>{waiting={resolve,reject}}); },
      return(){finish(makeError('CANCELLED')); return Promise.resolve({done:true})}
    };
    current={id,cancel:()=>finish(makeError('CANCELLED')),event:event=>{
      if(ended)return;
      armIdle();
      if(event.type==='state') state('Thinking…');
      else if(event.type==='delta' && typeof event.text==='string') {
        received=true; state('Generating…');
        const value={text:event.text};
        if(waiting){const waiter=waiting;waiting=null;waiter.resolve({value,done:false})} else queue.push(value);
      } else if(event.type==='done') finish(received?null:makeError('INVALID_RESPONSE'));
      else if(event.type==='error') finish(makeError(event.code));
      else finish(makeError('INVALID_RESPONSE'));
    }};
    state('Connecting…');armIdle();
    try {
      const body={messages:messages.filter(m=>['user','assistant'].includes(m.role)).slice(-21),
        context:typeof nativePracticeContext==='function'?nativePracticeContext().slice(0,8000):''};
      if(window.InterpreterBackend.start(id,JSON.stringify(body))!==true) finish(makeError('BUSY'));
    } catch {finish(makeError('INVALID_REQUEST'))}
    if(options.stream) return iterator;
    let answer=''; for await(const part of iterator)answer+=part.text;
    return {message:{content:answer}};
  }
  window.TrainerBackend={chat,cancel:()=>current?.cancel(),onEvent:(id,event)=>{if(current?.id===id)current.event(event)},
    errorMessage:error=>errors[error?.code]||errors.SERVER_ERROR};
  window.addEventListener('offline',()=>{current?.cancel();state('No internet connection')});
  window.addEventListener('online',()=>{if(!current)state('Network available · send a message')});
  window.addEventListener('pagehide',()=>current?.cancel());
})();
