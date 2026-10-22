import {test} from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFile} from 'node:fs/promises';
import {createHash,sign,X509Certificate} from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {signedFixture} from '../security-fixture.mjs';
const openssl=process.platform==='win32'?'C:/Program Files/Git/mingw64/bin/openssl.exe':'openssl';
const tlsFixture=process.env.PROOF_TLS_FIXTURE??'phase4d-tls-fixture';assert.ok(['phase4d-tls-fixture','phase4i-tls-fixture'].includes(tlsFixture));
const tlsFile=name=>new URL(`../evidence/${tlsFixture}/${name}`,import.meta.url);
function parseHttp(bytes){
 const split=bytes.indexOf('\r\n\r\n');assert.ok(split>=0,'Incomplete HTTP headers');
 const head=bytes.subarray(0,split).toString('ascii'),status=Number(head.match(/^HTTP\/1\.1 (\d+)/)?.[1]);
 let payload=bytes.subarray(split+4);assert.ok(payload.length<=4*1024*1024,'Bounded TLS fixture response');
 if(/^transfer-encoding:\s*chunked\s*$/im.test(head)){
  const chunks=[];let at=0;
  while(true){const end=payload.indexOf('\r\n',at);assert.ok(end>=at,'Incomplete chunk header');const size=payload.subarray(at,end).toString('ascii');assert.match(size,/^[0-9a-f]+$/i);const n=parseInt(size,16);at=end+2;
   assert.ok(at+n+2<=payload.length,'Incomplete HTTP chunk');assert.equal(payload.subarray(at+n,at+n+2).toString(),'\r\n');
   if(n===0){assert.equal(at+2,payload.length,'Unexpected HTTP trailers');break;}chunks.push(payload.subarray(at,at+n));at+=n+2;
  }payload=Buffer.concat(chunks);
 }else {const length=head.match(/^content-length:\s*(\d+)\s*$/im);if(length)assert.equal(payload.length,Number(length[1]));}
 return {status,body:JSON.parse(payload.toString('utf8'))};
}
test('actual loopback TLS validates certificate/hostname and carries exact signed tenant archive bytes',async()=>{
 const cert=await readFile(tlsFile('localhost-cert.pem'));
 const key=await readFile(tlsFile('localhost-key.pem'));
 const ca=await readFile(tlsFile('local-ca.pem'));
 const r=await signedFixture();let server;
 try {
  server=https.createServer({cert:Buffer.concat([cert,Buffer.from('\n'),ca]),key,minVersion:'TLSv1.2'},async(req,res)=>{
   try{let bytes='';for await(const chunk of req)bytes+=chunk;const response=await r.mf.dispatchFetch('https://'+req.headers.host+req.url,{method:req.method,headers:req.headers,body:bytes});res.writeHead(response.status,{'content-type':'application/json'});res.end(Buffer.from(await response.arrayBuffer()));}
   catch{res.writeHead(503);res.end('{}');}
  });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const port=server.address().port;
  const url=`https://127.0.0.1:${port}/shops/proof-shop/owner/archive-snapshot`,body=JSON.stringify({orderIds:[]}),jwt=r.token('synthetic-owner'),nonce=crypto.randomUUID(),time=Math.floor(Date.now()/1000),digest=x=>createHash('sha256').update(x).digest('hex');
  const message=['HWPOS-DEVICE-V1','POST',url,digest(body),digest(jwt),r.owner.deviceId,'1',String(time),nonce].join('\n');
  const headers={authorization:'Bearer '+jwt,'content-type':'application/json','x-device-id':r.owner.deviceId,'x-device-key-version':'1','x-device-time':String(time),'x-device-nonce':nonce,'x-device-signature':sign('sha256',Buffer.from(message),{key:r.owner.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url')};
  // This host's Node TLS client fails even a minimal valid local CA handshake
  // (preserved in phase4d-tls-minimal.log). Use the installed OpenSSL CLI as the
  // explicit test client, with mandatory chain and hostname/IP validation.
  // No application TLS verification is disabled and no production fallback added.
  const send=options=>new Promise((resolve,reject)=>{
   const args=['s_client','-connect',`127.0.0.1:${port}`,'-verify_return_error','-quiet',
    ...(options.ca?['-CAfile',fileURLToPath(tlsFile('local-ca.pem'))]:[]),
    ...(options.servername?['-verify_hostname',options.servername]:['-verify_ip','127.0.0.1'])];
   const child=spawn(openssl,args,{windowsHide:true});const chunks=[];let error='';
   child.stdout.on('data',c=>chunks.push(c));child.stderr.on('data',c=>error+=c);child.on('error',reject);
   child.on('close',code=>{if(code!==0){reject(Error('TLS certificate/hostname validation rejected: '+error));return;}
    try{resolve(parseHttp(Buffer.concat(chunks)));}catch(e){reject(e);}
   });
   child.stdin.on('error',()=>{});
   child.stdin.end(`POST /shops/proof-shop/owner/archive-snapshot HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nConnection: close\r\nContent-Length: ${Buffer.byteLength(body)}\r\n`+Object.entries(headers).map(([k,v])=>`${k}: ${v}`).join('\r\n')+'\r\n\r\n'+body);
  });
  console.log('TLS fixture stage: reject untrusted issuer');
  await assert.rejects(send({}),/self-signed|certificate/i);
  console.log('TLS fixture stage: reject wrong hostname');
  await assert.rejects(send({ca,servername:'wrong-host.invalid'}),/hostname|altnames|certificate/i);
  console.log('TLS fixture stage: accept explicitly trusted CA');
  const advertised=await new Promise((resolve,reject)=>{const c=spawn(openssl,['s_client','-connect',`127.0.0.1:${port}`,'-showcerts'],{windowsHide:true});let out='';c.stdout.on('data',b=>out+=b);c.stderr.resume();c.on('error',reject);c.on('close',()=>resolve(out));c.stdin.end();});
  const certificates=advertised.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g)??[];
  console.log('Public TLS certificate diagnostic',JSON.stringify({expected:new X509Certificate(cert).fingerprint256,received:certificates.map(c=>{const x=new X509Certificate(c);return {fingerprint:x.fingerprint256,subject:x.subject,issuer:x.issuer};})}));
  assert.ok(certificates.length>0);assert.equal(new X509Certificate(certificates[0]).fingerprint256,new X509Certificate(cert).fingerprint256,'Exact fixture peer is required');
  const response=await send({ca});assert.equal(response.status,200);assert.equal(response.body.shop,'proof-shop');assert.equal(response.body.cleanupAuthorized,false);
  assert.equal((await send({ca})).status,403); // Actual TLS mutation replay denied.
 }finally{if(server)await new Promise(resolve=>server.close(resolve));await r.close();}
});
