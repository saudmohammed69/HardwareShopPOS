// Alternate local diagnostic driver. Native workerd/D1 and original URL intact.
// No retry, hidden queuing limit reduction, signature change or timeout increase.
import http from 'node:http';
import {CoreHeaders} from 'miniflare';
export async function enableNodeHttpFixture(mf){
 const entry=await mf.ready;if(entry.protocol!=='http:'||entry.hostname!=='127.0.0.1')throw Error('Local workerd required');
 const agent=new http.Agent({keepAlive:true,maxSockets:100,maxFreeSockets:100,scheduling:'lifo'});
 const stats={kind:'LOCAL_NODE_HTTP',maxConnections:100,requests:0,connections:0,socketCloses:0,errors:0,peakConnected:0};
 const sockets=new WeakSet();let active=0;
 mf.dispatchFetch=(input,options={})=>new Promise((resolve,reject)=>{
  const original=new URL(input),target=new URL(original.pathname+original.search,entry.origin),headers=new Headers(options.headers);
  headers.set(CoreHeaders.ORIGINAL_URL,original.href);headers.set(CoreHeaders.DISABLE_PRETTY_ERROR,'true');
  const req=http.request(target,{method:options.method??'GET',headers:Object.fromEntries(headers),agent},res=>{
   const chunks=[];res.on('data',b=>chunks.push(b));res.on('error',fail);res.on('end',()=>{stats.requests++;resolve(new Response(Buffer.concat(chunks),{status:res.statusCode,headers:res.headers}));});
  });
  function fail(e){stats.errors++;reject(e);}
  req.on('error',fail);req.on('socket',socket=>{if(!sockets.has(socket)){sockets.add(socket);stats.connections++;active++;stats.peakConnected=Math.max(active,stats.peakConnected);socket.once('close',()=>{active--;stats.socketCloses++;});}});
  req.end(options.body);
 });
 return {stats,close:async()=>agent.destroy()};
}
