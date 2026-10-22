// LOCAL test transport only. Miniflare 4.20260730.0 dispatchFetch sets reset=true
// on every request, causing connection churn. Remaining Windows peer closures
// and ingress delays have not been attributed to one underlying component.
// Use the same native workerd entry and original-URL headers, with bounded HTTP
// keep-alive. No Worker, signature, D1 statement or customer concurrency changes.
import {Pool,fetch} from 'undici';
import diagnostics from 'node:diagnostics_channel';
import {CoreHeaders} from 'miniflare';
import {readFile} from 'node:fs/promises';
import {X509Certificate} from 'node:crypto';
import {checkServerIdentity} from 'node:tls';
export async function enablePooledFixture(mf){
 const direct=process.env.PROOF_4J_DIRECT==='1',nativeTls=process.env.PROOF_4J_NATIVE_TLS==='1';
 if(direct&&nativeTls)throw Error('Do not mix the HTTP direct-socket diagnostic with native TLS');
 const entry=direct?await mf.unsafeGetDirectURL():await mf.ready;
 if(entry.protocol!==(nativeTls?'https:':'http:')||entry.hostname!=='127.0.0.1')throw Error('Pooled fixture requires the local workerd entry');
 // Bound idle reuse and request lifetime too. Windows/workerd closed reused
 // sockets during sustained runs; never retry an ambiguous signed mutation in
 // this transport. These bounds do not reduce customer concurrency or change a
 // request timeout, signature, Worker statement or application acceptance limit.
 const legacy=process.env.PROOF_LEGACY_RECYCLE==='1';
 const noReuse=process.env.PROOF_4J_NO_REUSE==='1';
 let tlsOptions={},expectedLeaf=null;
 if(nativeTls){
  const ca=await readFile(new URL('./evidence/phase4i-tls-fixture/local-ca.pem',import.meta.url));
  expectedLeaf=new X509Certificate(await readFile(new URL('./evidence/phase4i-tls-fixture/localhost-cert.pem',import.meta.url))).fingerprint256;
  tlsOptions={connect:{ca,rejectUnauthorized:true,checkServerIdentity:(host,cert)=>checkServerIdentity(host,cert)??(cert.fingerprint256===expectedLeaf?undefined:Error('Isolated local TLS peer differs from pinned fixture leaf'))}};
 }
 const pool=new Pool(entry.origin,{...tlsOptions,connections:100,pipelining:noReuse?0:1,keepAliveTimeout:4000,keepAliveMaxTimeout:4000,...(legacy?{maxRequestsPerClient:16}:{})}),
  stats={kind:'LOCAL_WORKERD_KEEP_ALIVE',entryKind:nativeTls?'NATIVE_WORKERD_TLS':direct?'DIRECT_WORKERD_HTTP_PROXY':'MINIFLARE_ENTRY',tlsCertificateVerification:nativeTls,expectedLeafFingerprint256:expectedLeaf,maxConnections:100,idleReuseMs:4000,noReuse,maxRequestsPerConnection:legacy?16:null,requests:0,peakConnected:0,peakActive:0},traces=[];
 // Sanitized local-only lifecycle evidence. No bodies, signatures or customer data.
 const detailed=process.env.PROOF_4J_SOCKET_TRACE==='1',socketIds=new WeakMap();let socketId=0;
 const sockets=[],subscriptions=[],requestIdentities=new WeakMap(),requestSockets=new WeakMap();
 // Undici diagnostics can expose headers as a flat array, not just a string.
 // Extract only local correlation IDs; never serialize the header collection.
 function traceHeader(headers,name){
  let value;
  if(typeof headers?.get==='function')value=headers.get(name);
  else if(Array.isArray(headers)){
   for(let i=0;i<headers.length;i++){
    if(Array.isArray(headers[i])&&String(headers[i][0]).toLowerCase()===name){value=String(headers[i][1]);break;}
    if(String(headers[i]).toLowerCase()===name){value=String(headers[i+1]??'');break;}
   }
  }else if(typeof headers==='string'){
   for(const line of headers.split(/\r?\n/)){const split=line.indexOf(':');if(split>0&&line.slice(0,split).trim().toLowerCase()===name){value=line.slice(split+1).trim();break;}}
  }
  return /^[a-f0-9-]{36}$/i.test(value??'')?value:undefined;
 }
 const localRequest=req=>!req?.origin||String(req.origin)===entry.origin;
 const listen=(name,fn)=>{const channel=diagnostics.channel(name);channel.subscribe(fn);subscriptions.push([channel,fn]);};
 const stamp=()=>({monoMs:performance.now(),wallMs:Date.now(),active,queued:waiting.length,connected:pool.stats.connected,pending:pool.stats.pending,running:pool.stats.running});
 const socketNumber=socket=>{if(!socket)return null;if(!socketIds.has(socket))socketIds.set(socket,++socketId);return socketIds.get(socket);};
 if(detailed){
  for(const name of ['undici:client:connected','undici:client:connectError','undici:client:beforeConnect','undici:request:headers','undici:request:error'])listen(name,info=>{
   const req=info.request;if(!localRequest(req))return;
   const known=req?requestIdentities.get(req):null;
   const operation=known?.operation??traceHeader(req?.headers,'x-proof-trace-id'),attempt=known?.attempt??traceHeader(req?.headers,'x-proof-attempt-id');
   const requestSocket=info.socket??(req?requestSockets.get(req):null);
   const row={...stamp(),channel:name,operation,attempt,route:req?.path?.split('?')[0],socket:socketNumber(requestSocket),code:info.error?.code??info.error?.cause?.code,status:info.response?.statusCode};
   sockets.push(row);
   if(name==='undici:client:connected'){const socket=info.socket,id=socketNumber(socket);socket.once('end',()=>sockets.push({...stamp(),channel:'socket:end',socket:id,bytesRead:socket.bytesRead,bytesWritten:socket.bytesWritten}));socket.once('close',error=>sockets.push({...stamp(),channel:'socket:close',socket:id,error,bytesRead:socket.bytesRead,bytesWritten:socket.bytesWritten}));}
  });
 }
 let active=0;const waiting=[];
 const acquire=()=>active<100?(active++,Promise.resolve()):new Promise(resolve=>waiting.push(resolve));
 const release=()=>{if(waiting.length)waiting.shift()();else active--;};
 const onSend=({headers,socket,request})=>{
  if(!localRequest(request))return;
  const operation=traceHeader(headers,'x-proof-trace-id')??traceHeader(request?.headers,'x-proof-trace-id');
  const attempt=traceHeader(headers,'x-proof-attempt-id')??traceHeader(request?.headers,'x-proof-attempt-id');
  if(request){requestIdentities.set(request,{operation,attempt});if(socket)requestSockets.set(request,socket);}
  if(operation)traces.push({operation,attempt,stage:'network-send',clientMonoMs:performance.now(),clientWallMs:Date.now()});
  if(detailed)sockets.push({...stamp(),channel:'sendHeaders',operation,attempt,socket:socketNumber(socket),route:request?.path?.split('?')[0],bytesRead:socket?.bytesRead,bytesWritten:socket?.bytesWritten});
 };
 diagnostics.channel('undici:client:sendHeaders').subscribe(onSend);
 mf.dispatchFetch=async(input,options={})=>{
  const original=new URL(input),target=new URL(original.pathname+original.search,entry.origin),operation=new Headers(options.headers).get('x-proof-trace-id'),attempt=new Headers(options.headers).get('x-proof-attempt-id');
  if(operation)traces.push({operation,attempt,stage:'transport-enqueue',clientMonoMs:performance.now(),clientWallMs:Date.now()});
  await acquire();stats.peakActive=Math.max(stats.peakActive,active);
  try{
  const {beforeTransmit,...fetchOptions}=options,prepared=beforeTransmit?.()??{},headers=new Headers({...Object.fromEntries(new Headers(options.headers)),...prepared.headers});
  headers.set(CoreHeaders.ORIGINAL_URL,original.href);headers.set(CoreHeaders.DISABLE_PRETTY_ERROR,'true');
  // Native workerd's explicit local HTTP-proxy socket accepts an absolute-form
  // request target. It reconstructs the ORIGINAL signed URL itself, without a
  // JS header-rewrite/auth bypass or the intermediate Miniflare ProxyWorker.
  // This is a LOCAL diagnostic path, not production Cloudflare behavior.
  const dispatcher=direct?{dispatch:(opts,handler)=>pool.dispatch({...opts,path:original.href},handler)}:pool;
  const response=await fetch(target,{...fetchOptions,...prepared,headers,dispatcher,redirect:'manual'});
  // Keep the admission slot through body consumption. Otherwise another signed
  // request can queue inside Undici after the apparent slot has been released.
  const bytes=await response.arrayBuffer();
  stats.requests++;stats.peakConnected=Math.max(stats.peakConnected,pool.stats.connected);
  if(operation)traces.push({operation,attempt,stage:'response-complete',status:response.status,clientMonoMs:performance.now(),clientWallMs:Date.now()});
  return new Response(bytes,{status:response.status,statusText:response.statusText,headers:response.headers});
  }catch(e){if(operation)traces.push({operation,attempt,stage:'transport-failure',code:e.code??e.cause?.code??'UNKNOWN',clientMonoMs:performance.now(),clientWallMs:Date.now()});throw e;}finally{release();}
 };
 return {stats,traces,sockets,close:async()=>{await pool.close();diagnostics.channel('undici:client:sendHeaders').unsubscribe(onSend);for(const [channel,fn]of subscriptions)channel.unsubscribe(fn);}};
}
