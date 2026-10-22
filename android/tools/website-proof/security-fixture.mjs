// Synthetic signing authority and test clients. Private keys remain in memory;
// this file is never imported by the Worker or customer site.
import {generateKeyPairSync,sign,createHash} from 'node:crypto';
import {runtime,seed,call} from './runtime.mjs';
import {canonical} from './pricing.mjs';
const b64=value=>Buffer.from(typeof value==='string'?value:JSON.stringify(value)).toString('base64url');
export const fingerprint=key=>createHash('sha256').update(canonical(key)).digest('hex');
export function newDevice(){
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),jwk=keys.publicKey.export({format:'jwk'});
 return {...keys,publicJwk:{kty:jwk.kty,crv:jwk.crv,x:jwk.x,y:jwk.y},deviceId:crypto.randomUUID(),version:1};
}
export async function signedFixture(options={}){
 const rsa=generateKeyPairSync('rsa',{modulusLength:2048}),publicJwk={...rsa.publicKey.export({format:'jwk'}),kid:'synthetic-rsa-1',alg:'RS256',use:'sig'};
 const bindings={AUTH_MODE:'SIGNED_LOCAL',AUTH_PROJECT:'demo-hardwareshop-phase4b',LOCAL_AUTH_JWKS:JSON.stringify({keys:[publicJwk]}),AUTH_KEYS_EXPIRES_AT:String(Math.floor(Date.now()/1000)+3600),...options.bindings};
 const rt=await runtime({bindings});await seed(rt,30);
 for(const [uid,shop,role] of [['synthetic-owner','proof-shop','OWNER'],['synthetic-worker','proof-shop','WORKER'],['synthetic-other','other-shop','WORKER']]){
  await rt.db.prepare('INSERT INTO auth_users(uid,checked_until) VALUES(?,?)').bind(uid,Math.floor(Date.now()/1000)+3600).run();
  await rt.db.prepare("INSERT INTO auth_identity_bindings(uid,person_id,kind,checked_until) VALUES(?,?,'INDIVIDUAL',?)").bind(uid,'person-'+uid,Math.floor(Date.now()/1000)+3600).run();
  await rt.db.prepare('INSERT INTO auth_memberships(shop,uid,role,active) VALUES(?,?,?,1)').bind(shop,uid,role).run();
  if(role==='WORKER')await rt.db.prepare('INSERT INTO auth_worker_links(shop,uid,worker_ref) VALUES(?,?,?)').bind(shop,uid,'worker-'+uid).run();
 }
 const token=(uid='synthetic-worker',overrides={},header={})=>{
  const now=Math.floor(Date.now()/1000),h=b64({alg:'RS256',typ:'JWT',kid:publicJwk.kid,...header}),p=b64({iss:'https://securetoken.google.com/demo-hardwareshop-phase4b',aud:'demo-hardwareshop-phase4b',sub:uid,iat:now,exp:now+3600,auth_time:now,...overrides});
  return h+'.'+p+'.'+sign('RSA-SHA256',Buffer.from(h+'.'+p),rsa.privateKey).toString('base64url');
 };
 const bootstrap=async(uid,device=newDevice(),shop='proof-shop')=>{
  const now=Math.floor(Date.now()/1000),key=canonical(device.publicJwk),fp=fingerprint(device.publicJwk);
  await rt.db.prepare("INSERT INTO auth_devices(id,shop,uid,enrollment_id,enrollment_hash,public_key,fingerprint,status,created_at,expires_at,approved_by) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,?)")
   .bind(device.deviceId,shop,uid,crypto.randomUUID(),fp,key,fp,now,now+86400,'synthetic-bootstrap').run();return device;
 };
 const owner=await bootstrap('synthetic-owner'),worker=await bootstrap('synthetic-worker'),other=await bootstrap('synthetic-other',newDevice(),'other-shop');
 function prepare(route,{uid='synthetic-worker',device=worker,jwt=token(uid),method='POST',body,raw,nonce=crypto.randomUUID(),timestamp=Math.floor(Date.now()/1000),headers={}}={}){
  const url=`https://proof.invalid/shops/proof-shop/${route}`,bytes=raw??(body===undefined?'':JSON.stringify(body));
  const bodyHash=createHash('sha256').update(bytes).digest('hex'),tokenHash=createHash('sha256').update(jwt).digest('hex');
  const message=['HWPOS-DEVICE-V1',method,url,bodyHash,tokenHash,device.deviceId,String(device.version),String(timestamp),nonce].join('\n');
  const signature=sign('sha256',Buffer.from(message),{key:device.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url');
  return {route,options:{method,raw:bytes||undefined,headers:{authorization:'Bearer '+jwt,'x-device-id':device.deviceId,'x-device-key-version':String(device.version),
   'x-device-time':String(timestamp),'x-device-nonce':nonce,'x-device-signature':signature,...headers}}};
 }
 const send=async(route,options)=>{const prepared=prepare(route,options);return call(rt,prepared.route,prepared.options);};
 return {...rt,bindings,token,bootstrap,owner,worker,other,prepare,send,
  enrollmentDevice:()=>{const d=newDevice();return {...d,deviceId:'enroll:'+fingerprint(d.publicJwk)};}};
}
