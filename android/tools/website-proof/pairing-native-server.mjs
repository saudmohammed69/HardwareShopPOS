// Private loopback fixture for one disposable emulator pairing test.
import http from 'node:http';
import {writeFile,mkdir} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {pairedFixture} from './pairing-fixture.mjs';
import {call} from './runtime.mjs';
const r=await pairedFixture({provision:false}),control=randomBytes(32).toString('hex');
await mkdir('evidence/phase4g-native',{recursive:true});await writeFile('evidence/phase4g-native/control.json',JSON.stringify({control}));
const server=http.createServer(async(req,res)=>{try{
 if(req.headers.host!=='127.0.0.1:4318')throw Error('Host');let bytes='';for await(const chunk of req){bytes+=chunk;if(bytes.length>32768)throw Error('size');}
 let reply;
 if(req.url==='/__fixture/bootstrap'){
  if(req.headers['x-synthetic-control']!==control)throw Error('control');const b=JSON.parse(bytes),ticket=await r.provisionOwner({deviceId:b.deviceId,databaseEpoch:b.databaseEpoch,publicJwk:b.publicKey});reply={status:200,body:{ticket}};
 }else{
  if(!req.url.startsWith('/shops/proof-shop/'))throw Error('route');
  reply=await call(r,req.url.slice('/shops/proof-shop/'.length),{method:req.method,raw:bytes||undefined,headers:req.headers});
 }
 res.writeHead(reply.status,{'content-type':'application/json'});res.end(JSON.stringify(reply.body));
 }catch{res.writeHead(503);res.end('{}');}});
await new Promise(resolve=>server.listen(4318,'127.0.0.1',resolve));console.log('Phase4G pairing native fixture ready');
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{await new Promise(resolve=>server.close(resolve));await r.close();process.exit(0);});
