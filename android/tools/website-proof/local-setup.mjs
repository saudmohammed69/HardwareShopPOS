// Workstation-only wiring: one disposable D1 store for the customer and Android
// fixtures. This module, its authority and private files must never be deployed.
import http from 'node:http';
import path from 'node:path';
import {mkdir,writeFile} from 'node:fs/promises';
import {randomBytes,randomUUID,timingSafeEqual} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {startWeb} from './web-server.mjs';
import {pairedFixture} from './pairing-fixture.mjs';
import {call,root} from './runtime.mjs';

const signatureHeaders=['x-device-id','x-database-epoch','x-device-time','x-device-nonce','x-operation-id','x-device-signature','content-type'];
const uuid=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
function equalSecret(actual,expected){
 if(typeof actual!=='string'||actual.length!==expected.length)return false;
 return timingSafeEqual(Buffer.from(actual),Buffer.from(expected));
}
async function bodyBytes(req){
 const parts=[];let count=0;
 for await(const chunk of req){count+=chunk.length;if(count>32768){const error=Error('Request too large');error.status=413;throw error;}parts.push(chunk);}
 return Buffer.concat(parts).toString('utf8');
}
const closeServer=server=>new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve()));

export async function startLocalSetup({synthetic=false}={}){
 if(synthetic!==true)throw Error('Pass --synthetic explicitly. No production or real device connection is supported.');
 const major=Number(process.versions.node.split('.')[0]);if(major<22)throw Error('Node.js 22 or newer is required');
 const runId=randomUUID(),directory=path.join(root,'.local-state','shared-setup',runId);
 await mkdir(path.dirname(directory),{recursive:true});
 await mkdir(directory,{recursive:false});
 const control=randomBytes(32).toString('hex');
 // Credentials stay outside the public site and cannot approve a real owner.
 await writeFile(path.join(directory,'control.json'),JSON.stringify({classification:'SYNTHETIC_LOCAL_ONLY',control}),{flag:'wx',mode:0o600});
 let customer,native;
 try{
  customer=await startWeb({port:4317,directory,largeCatalog:false,
   runtimeFactory:options=>pairedFixture({...options,provision:false,bindings:{...options.bindings,PROOF_TRACE:'true'}})});
  const r=customer.r;
  native=http.createServer(async(req,res)=>{
   const finish=(status,value)=>{if(res.destroyed||res.writableEnded)return;res.writeHead(status,{'content-type':'application/json','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(value));};
   try{
    // Android's loopback adapter signs proof.invalid; never rewrite the signed
    // request bytes or supply a local bearer credential as an authority bypass.
    if(req.headers.host!=='127.0.0.1:4318'||req.headers.origin!==undefined){finish(403,{error:'Private loopback Android fixture only'});return;}
    if(req.headers.authorization!==undefined){finish(403,{error:'Signed installation authorization required'});return;}
    if(!['GET','POST'].includes(req.method)){finish(405,{error:'Method not allowed'});return;}
    const url=new URL(req.url,'http://127.0.0.1:4318');
    const isControl=url.pathname.startsWith('/__fixture/');
    if(isControl&&(!equalSecret(req.headers['x-synthetic-control'],control)||req.method!=='POST'||url.search||req.headers['content-type']!=='application/json')){
     finish(403,{error:'Private synthetic control authorization required'});return;
    }
    // Control mutations are separate from signed Android shop operations.
    if(isControl){
     if(url.pathname!=='/__fixture/bootstrap'){finish(404,{error:'Unknown private control'});return;}
     const b=JSON.parse(await bodyBytes(req));
     if(!b||Object.keys(b).sort().join(',')!=='databaseEpoch,deviceId,publicKey'||!uuid.test(b.deviceId??'')||!uuid.test(b.databaseEpoch??'')){
      finish(400,{error:'Invalid synthetic installation request'});return;
     }
     const k=b.publicKey;
     if(!k||Object.keys(k).sort().join(',')!=='crv,kty,x,y'||k.kty!=='EC'||k.crv!=='P-256'||![k.x,k.y].every(x=>typeof x==='string'&&/^[A-Za-z0-9_-]{43}$/.test(x))){
      finish(400,{error:'A public P-256 installation key is required'});return;
     }
     const ticket=await r.provisionOwner({deviceId:b.deviceId,databaseEpoch:b.databaseEpoch,publicJwk:k});
     finish(200,{ticket});return;
    }
    if(!/^\/shops\/proof-shop\/[A-Za-z0-9/_-]+$/.test(url.pathname)||url.href!=='http://127.0.0.1:4318'+req.url){
     finish(404,{error:'Unknown fixture route'});return;
    }
    const bytes=await bodyBytes(req);
    if(req.method==='GET'&&bytes){finish(400,{error:'Unexpected GET body'});return;}
    const headers={};for(const key of signatureHeaders)if(typeof req.headers[key]==='string')headers[key]=req.headers[key];
    const route=url.pathname.slice('/shops/proof-shop/'.length)+url.search;
    const reply=await call(r,route,{method:req.method,raw:bytes||undefined,headers,abuse:false});
    // A publication receipt must not be replaced by a failure if snapshot refresh
    // fails after commit. Keep the original result; manual refresh may recover.
    if(req.method==='POST'&&route.startsWith('catalog/')&&reply.status<300){
     try{await customer.refreshCatalog();}catch{console.warn('Local catalog snapshot refresh failed after publication; restart review is required.');}
    }
    finish(reply.status,reply.body);
   }catch(error){
    // This is an uncertain transport result, never a manufactured order receipt
    // or permission to mint a new billing operation/claim.
    finish(error?.status??503,{error:'Local operation not confirmed. Recover the same operation before retrying.',retryable:true});
   }
  });
  native.requestTimeout=15000;
  await new Promise((resolve,reject)=>{native.once('error',reject);native.listen(4318,'127.0.0.1',resolve);});
  const publicInfo={classification:'SYNTHETIC_LOCAL_ONLY_UNVERIFIED',runId,customerUrl:customer.origin,nativeUrl:'http://127.0.0.1:4318',shop:'proof-shop',databaseDirectory:directory,realEnrollment:false,liveOrdering:false};
  await writeFile(path.join(directory,'setup.json'),JSON.stringify(publicInfo,null,2)+'\n',{flag:'wx'});
  let closing;
  return {...publicInfo,customer,native,close:()=>closing??=(async()=>{await closeServer(native);await customer.close();})()};
 }catch(error){
  if(native?.listening)await closeServer(native);
  if(customer)await customer.close();
  throw error;
 }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 if(process.argv.slice(2).join(' ')!=='--synthetic')throw Error('Usage: node local-setup.mjs --synthetic');
 const setup=await startLocalSetup({synthetic:true});
 console.log(JSON.stringify({status:'LOCAL_SETUP_STARTED_NOT_ACCEPTED',...Object.fromEntries(Object.entries(setup).filter(([key])=>!['customer','native','close'].includes(key)))},null,2));
 console.log('No real POS connection. Use the disposable DEBUG fixture only. Ctrl+C drains requests and stops both listeners; retained data is not deleted.');
 let stopping=false;for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{if(stopping)return;stopping=true;try{await setup.close();process.exitCode=0;}catch{console.error('Local shutdown incomplete; retain this run directory for recovery.');process.exitCode=1;}});
}
