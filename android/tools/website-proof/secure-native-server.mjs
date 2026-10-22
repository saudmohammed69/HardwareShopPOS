// Disposable synthetic authority. Never import into a Worker/customer build.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {signedFixture} from './security-fixture.mjs';
import {call} from './runtime.mjs';
const r=await signedFixture(),control=crypto.randomUUID();
const directory=path.resolve('.local-state/phase4b-native');await fs.mkdir(directory,{recursive:true});
const controlPath=path.join(directory,'control.json');
await fs.writeFile(controlPath,JSON.stringify({control}),{mode:0o600});
let tail=Promise.resolve();
const serial=fn=>{const next=tail.then(fn);tail=next.catch(()=>{});return next;};
const server=http.createServer((req,res)=>serial(async()=>{
 try {
  if(req.headers.host!=='127.0.0.1:4318'||req.headers.origin){res.writeHead(403).end();return;}
  let body=Buffer.alloc(0);for await(const c of req){body=Buffer.concat([body,c]);if(body.length>32768){res.writeHead(413).end();return;}}
  const url=new URL(req.url,'http://127.0.0.1:4318');let result;
  if(url.pathname.startsWith('/__fixture/')){
   if(req.headers['x-synthetic-control']!==control){res.writeHead(403).end();return;}
   const input=JSON.parse(body.toString()||'{}');
   if(url.pathname==='/__fixture/token'&&['synthetic-owner','synthetic-worker'].includes(input.uid))result={status:200,body:{token:r.token(input.uid)}};
   else if(url.pathname==='/__fixture/approve')result=await r.send('auth/approve',{uid:'synthetic-owner',device:r.owner,body:input});
   else if(url.pathname==='/__fixture/revoke')result=await r.send('auth/revoke-device',{uid:'synthetic-owner',device:r.owner,body:input});
   else if(url.pathname==='/__fixture/bind-worker')result=await r.send('auth/bind-local-worker',{uid:'synthetic-owner',device:r.owner,body:input});
   else if(url.pathname==='/__fixture/orders')result=await call(r,'orders',{body:input});
   else {res.writeHead(404).end();return;}
   res.writeHead(result.status,{'content-type':'application/json','cache-control':'no-store'}).end(JSON.stringify(result.body));return;
  }
  if(!url.pathname.startsWith('/shops/proof-shop/')){res.writeHead(404).end();return;}
  // Preserve exact signed URL/body. No rewriting proxy may change signed bytes.
  const response=await r.mf.dispatchFetch(url.href,{method:req.method,headers:req.headers,...(body.length?{body}:{})});
  res.writeHead(response.status,{'content-type':'application/json','cache-control':'no-store'}).end(Buffer.from(await response.arrayBuffer()));
 }catch{if(!res.headersSent)res.writeHead(503,{'content-type':'application/json'});res.end(JSON.stringify({error:'Synthetic secure bridge unavailable'}));}
}));
await new Promise(resolve=>server.listen(4318,'127.0.0.1',resolve));
console.log('Signed synthetic native bridge ready on loopback 4318. No production connection.');
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{await new Promise(resolve=>server.close(resolve));await r.close();await fs.rm(controlPath,{force:true});process.exit(0);});
