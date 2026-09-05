import http from 'node:http';
import {once} from 'node:events';
import {pathToFileURL} from 'node:url';
import {AiError, classify, messages, validateRequest, ProviderRouter, configuredProviders} from './ai.mjs';

export function createServer({verifyToken, router, diagnostic = () => {}, generationMs = 75000}) {
  const active = new Set(), allowance = new Map();
  const clean = setInterval(() => { const now=Date.now(); for(const [id,v] of allowance) if(v.until<now) allowance.delete(id); },60000);
  clean.unref();
  const server = http.createServer(async (req,res) => {
    let uid, requestId, acquired = false;
    const start = Date.now(), controller = new AbortController();
    const deadline = setTimeout(() => controller.abort(new DOMException('Deadline','TimeoutError')), generationMs);
    res.on('close', () => controller.abort());
    res.setHeader('Cache-Control','no-store'); res.setHeader('X-Content-Type-Options','nosniff');
    const send = async value => {
      controller.signal.throwIfAborted();
      if (!res.write(JSON.stringify(value)+'\n')) await once(res,'drain',{signal:controller.signal});
    };
    try {
      if (req.method !== 'POST' || req.url !== '/v1/chat') { res.writeHead(404); res.end(); return; }
      const authorization = req.headers.authorization || '';
      if (!authorization.startsWith('Bearer ') || authorization.length > 8192) throw new AiError('AUTH_EXPIRED',401);
      let claims;
      try { claims = await Promise.race([verifyToken(authorization.slice(7)), new Promise((_, reject) => controller.signal.addEventListener('abort', () => reject(controller.signal.reason), {once:true}))]); }
      catch(error) { if(controller.signal.aborted) throw error; throw new AiError('AUTH_EXPIRED',401); }
      uid = claims.uid;
      if (!uid) throw new AiError('AUTH_EXPIRED',401);
      if (claims.firebase?.sign_in_provider === 'password' && !claims.email_verified) throw new AiError('EMAIL_UNVERIFIED',403);
      if (active.has(uid)) throw new AiError('BUSY',409);
      if (active.size >= 100) throw new AiError('RATE_LIMITED',429);
      let bucket = allowance.get(uid);
      if (!bucket || bucket.until < Date.now()) bucket = {count:0,until:Date.now()+60000};
      if (bucket.count >= 12 || allowance.size >= 100000) throw new AiError('RATE_LIMITED',429);
      bucket.count++; allowance.set(uid,bucket); active.add(uid); acquired = true;
      if (!req.headers['content-type']?.startsWith('application/json')) throw new AiError('INVALID_REQUEST',400);
      let chunks = [], size = 0;
      req.setTimeout(10000,()=>req.destroy());
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 262144) throw new AiError('INVALID_REQUEST',413);
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new AiError('INVALID_REQUEST',400); }
      const conversation = validateRequest(body); requestId = body.requestId;
      const log = info => diagnostic({...info,requestId,latencyMs:Date.now()-start});
      log({event:'request_start'});
      res.writeHead(200, {'Content-Type':'application/x-ndjson; charset=utf-8','X-Accel-Buffering':'no'});
      await send({type:'state',state:'thinking',requestId});
      let first = true;
      for await (const event of router.stream(conversation,controller.signal,log)) {
        if (first) { log({event:'stream_start'}); first=false; }
        await send(event);
      }
      await send({type:'done',requestId}); log({event:'stream_end'}); res.end();
    } catch (cause) {
      const error = classify(cause);
      diagnostic({event:'request_error',requestId,code:error.code,latencyMs:Date.now()-start});
      if (!res.destroyed && !res.writableEnded) {
        if (!res.headersSent) res.writeHead(error.status,{'Content-Type':'application/json',...(error.code==='RATE_LIMITED'?{'Retry-After':'60'}:{})});
        res.end(JSON.stringify({type:'error',code:error.code,message:messages[error.code] || messages.SERVER_ERROR,requestId})+'\n');
      }
    } finally { clearTimeout(deadline); if (acquired) active.delete(uid); }
  });
  server.requestTimeout=15000; server.headersTimeout=10000; server.on('close',()=>clearInterval(clean));
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const {initializeApp,applicationDefault} = await import('firebase-admin/app');
  const {getAuth} = await import('firebase-admin/auth');
  initializeApp({credential:applicationDefault()});
  const router = new ProviderRouter(configuredProviders());
  if (!router.providers.length) throw new AiError('CONFIGURATION_ERROR');
  createServer({verifyToken:token=>getAuth().verifyIdToken(token,true),router,
    diagnostic: process.env.NODE_ENV === 'production' ? () => {} : event=>console.info(JSON.stringify(event))
  }).listen(Number(process.env.PORT || 8080),'0.0.0.0');
}
