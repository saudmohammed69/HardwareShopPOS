// Diagnostic TLS channel over local IPC, NOT proof that Avast-intercepted TCP
// is fixed. No trust-store installation, AV change or production fallback.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFile} from 'node:fs/promises';
import {X509Certificate,createHash,createPublicKey,sign,randomUUID} from 'node:crypto';
import {signedFixture} from '../security-fixture.mjs';
test('diagnostic named-pipe HTTPS verifies known CA/hostname and exact peer; wrong trust/name and signed replay fail',{skip:process.platform!=='win32'},async()=>{
 assert.equal(process.platform,'win32','This diagnostic is specifically Windows named-pipe TLS');
 const cert=await readFile(new URL('../evidence/phase4d-tls-fixture/localhost-cert.pem',import.meta.url));
 const key=await readFile(new URL('../evidence/phase4d-tls-fixture/localhost-key.pem',import.meta.url));
 const ca=await readFile(new URL('../evidence/phase4d-tls-fixture/local-ca.pem',import.meta.url));
 const leaf=new X509Certificate(cert),root=new X509Certificate(ca);
 assert.ok(root.ca&&root.verify(root.publicKey)&&leaf.verify(root.publicKey));
 assert.equal(leaf.publicKey.export({type:'spki',format:'pem'}),createPublicKey(key).export({type:'spki',format:'pem'}));
 assert.ok(Date.parse(leaf.validFrom)<=Date.now()&&Date.parse(leaf.validTo)>Date.now());
 const r=await signedFixture(),pipe='\\\\.\\pipe\\hwpos-phase4f-'+randomUUID();let server;
 try{
  server=https.createServer({cert:Buffer.concat([cert,Buffer.from('\n'),ca]),key,minVersion:'TLSv1.2'},async(req,res)=>{
   try{let bytes='';for await(const b of req)bytes+=b;const response=await r.mf.dispatchFetch('https://localhost'+req.url,{method:req.method,headers:req.headers,body:bytes});res.writeHead(response.status,{'content-type':'application/json'});res.end(Buffer.from(await response.arrayBuffer()));}catch{res.writeHead(503);res.end('{}');}
  });await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(pipe,resolve);});
  const url='https://localhost/shops/proof-shop/owner/archive-snapshot',body=JSON.stringify({orderIds:[]}),jwt=r.token('synthetic-owner'),nonce=randomUUID(),time=Math.floor(Date.now()/1000),digest=x=>createHash('sha256').update(x).digest('hex');
  const message=['HWPOS-DEVICE-V1','POST',url,digest(body),digest(jwt),r.owner.deviceId,'1',String(time),nonce].join('\n');
  const headers={host:'localhost',authorization:'Bearer '+jwt,'content-type':'application/json','x-device-id':r.owner.deviceId,'x-device-key-version':'1','x-device-time':String(time),'x-device-nonce':nonce,'x-device-signature':sign('sha256',Buffer.from(message),{key:r.owner.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url')};
  const send=options=>new Promise((resolve,reject)=>{
   let peer;const request=https.request({socketPath:pipe,hostname:'localhost',servername:options.servername??'localhost',path:new URL(url).pathname,method:'POST',headers,agent:false,rejectUnauthorized:true,minVersion:'TLSv1.2',...(options.trust?{ca}:{})},response=>{let data='';response.on('data',b=>data+=b);response.on('end',()=>{try{resolve({status:response.statusCode,body:JSON.parse(data),peer});}catch(e){reject(e);}});});
   request.on('socket',socket=>socket.on('secureConnect',()=>{peer=socket.getPeerCertificate().fingerprint256;}));request.on('error',reject);request.setTimeout(8000,()=>request.destroy(Error('Diagnostic transport timed out')));request.end(body);
  });
  await assert.rejects(send({}),/certificate|self.signed|verify/i);
  await assert.rejects(send({trust:true,servername:'wrong-host.invalid'}),/hostname|altnames|certificate/i);
  const response=await send({trust:true});assert.equal(response.status,200);assert.equal(response.peer,leaf.fingerprint256);assert.equal(response.body.cleanupAuthorized,false);
  assert.equal((await send({trust:true})).status,403);
  console.log('Named-pipe strict TLS peer matched the generated fixture certificate; TCP acceptance remains a separate test.');
 }finally{if(server)await new Promise(resolve=>server.close(resolve));await r.close();}
});
