// Private synthetic authority, loopback only, never a production artifact.
import http from 'node:http';
import {writeFile,mkdir} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {pairedFixture} from './pairing-fixture.mjs';
import {call} from './runtime.mjs';
const r=await pairedFixture({provision:false,bindings:{PROOF_TRACE:'true'}}),control=randomBytes(32).toString('hex');
await mkdir('evidence/phase4h-native',{recursive:true});await writeFile('evidence/phase4h-native/control.json',JSON.stringify({control}));
const server=http.createServer(async(req,res)=>{try{
 if(req.headers.host!=='127.0.0.1:4318')throw Error('Host');let bytes='';for await(const chunk of req){bytes+=chunk;if(bytes.length>32768)throw Error('size');}
 let reply;
 if(req.url==='/__fixture/evidence'){
  if(req.headers['x-synthetic-control']!==control)throw Error('control');const trace=await r.lifecycle();await writeFile('evidence/phase4h-native/lifecycle.json',JSON.stringify(trace));
  const counts=await r.db.prepare("SELECT COUNT(*) orders,COUNT(DISTINCT bill_id) billIdentities,SUM(status='COMPLETED') completed FROM orders").first(),receipts=await r.db.prepare('SELECT COUNT(*) n FROM pair_operations').first();
  await writeFile('evidence/phase4h-native/backend-results.json',JSON.stringify({counts,receipts:receipts.n,apiMeter:await r.apiMeter(),fixture:r.directory}));reply={status:200,body:{saved:true}};
 }else if(req.url==='/__fixture/bootstrap'){
  if(req.headers['x-synthetic-control']!==control)throw Error('control');const b=JSON.parse(bytes),ticket=await r.provisionOwner({deviceId:b.deviceId,databaseEpoch:b.databaseEpoch,publicJwk:b.publicKey});reply={status:200,body:{ticket}};
 }else{
  if(!req.url.startsWith('/shops/proof-shop/'))throw Error('route');reply=await call(r,req.url.slice('/shops/proof-shop/'.length),{method:req.method,raw:bytes||undefined,headers:req.headers});
 }
 res.writeHead(reply.status,{'content-type':'application/json'});res.end(JSON.stringify(reply.body));
 }catch{res.writeHead(503);res.end('{}');}});
await new Promise(resolve=>server.listen(4318,'127.0.0.1',resolve));console.log('Phase4H pairing native fixture ready');
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{await new Promise(resolve=>server.close(resolve));await writeFile('evidence/phase4h-native/lifecycle.json',JSON.stringify(await r.lifecycle()));await r.close();process.exit(0);});
