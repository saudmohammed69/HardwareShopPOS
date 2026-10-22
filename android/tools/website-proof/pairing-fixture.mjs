// Node-only synthetic administrator. Never imported into Worker/site/APK.
import {generateKeyPairSync,createHash,randomBytes,sign} from 'node:crypto';
import {runtime,seed,call} from './runtime.mjs';
import {canonical} from './pricing.mjs';
import {ownerPermissions,workerPermissions} from './pairing.mjs';
const digest=s=>createHash('sha256').update(s).digest('hex');
export function installation(){const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),k=keys.publicKey.export({format:'jwk'});return {...keys,publicJwk:{kty:k.kty,crv:k.crv,x:k.x,y:k.y},deviceId:crypto.randomUUID(),databaseEpoch:crypto.randomUUID()};}
export function preparePaired(device,route,{method='POST',body,operationId=crypto.randomUUID(),nonce=crypto.randomUUID(),timestamp=Math.floor(Date.now()/1000),shop='proof-shop',headers={}}={}){
 const url=`https://proof.invalid/shops/${shop}/${route}`,raw=body===undefined?'':JSON.stringify(body);
 const message=['HWPOS-SHOP-DEVICE-V1',method,url,digest(raw),device.deviceId,device.databaseEpoch,String(timestamp),nonce,operationId].join('\n');
 return {route,options:{method,raw:raw||undefined,headers:{'x-device-id':device.deviceId,'x-database-epoch':device.databaseEpoch,'x-device-time':String(timestamp),'x-device-nonce':nonce,'x-operation-id':operationId,'x-device-signature':sign('sha256',Buffer.from(message),{key:device.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url'),...headers}}};
}
export async function pairedFixture({directory,initialise=true,bindings={},provision=true,pooledTransport=true}={}){
 const admin=installation(),adminFingerprint=digest(canonical(admin.publicJwk));
 const env={AUTH_MODE:'PAIRING_LOCAL',PAIR_ADMIN_PUBLIC_KEY:JSON.stringify(admin.publicJwk),PAIR_ADMIN_FRESH_UNTIL:String(Math.floor(Date.now()/1000)+3600),PAIR_AUTHORITY_EPOCH:'1',...bindings};
 const rt=await runtime({directory,initialise,bindings:env,pooledTransport});
 const send=async(route,options={})=>{const p=preparePaired(options.device??owner,route,options);return call(rt,p.route,p.options);};
 async function provisionOwner(device){
  // This direct private SQL simulates an independently authenticated operator,
  // after external legal-shop/owner and key-fingerprint verification. It is NOT
  // evidence of a real administrator or a public provisioning route.
  const ticket={id:crypto.randomUUID(),shop:'proof-shop',epoch:1,deviceId:device.deviceId,fingerprint:digest(canonical(device.publicJwk)),databaseEpoch:device.databaseEpoch,role:'OWNER',permissions:ownerPermissions,expires:Math.floor(Date.now()/1000)+300,secret:randomBytes(32).toString('hex')};
  await rt.db.prepare('INSERT INTO pair_authority VALUES(?,1,?,\'ACTIVE\')').bind(ticket.shop,adminFingerprint).run();
  await rt.db.prepare('INSERT INTO pair_grants VALUES(?,?,?,?,?,?,?,?,?,?,?,0)').bind(ticket.id,ticket.shop,1,ticket.deviceId,ticket.fingerprint,ticket.databaseEpoch,ticket.role,JSON.stringify(ticket.permissions),digest(canonical(ticket)),ticket.expires,'OUT_OF_BAND_ADMIN').run();
  return {...ticket,adminSignature:sign('sha256',Buffer.from(canonical(ticket)),{key:admin.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url')};
 }
 const owner=installation(),worker=installation();
 const pair=async(device,permissions=workerPermissions)=>{
  const approval=await send('pair/approve',{body:{deviceId:device.deviceId,databaseEpoch:device.databaseEpoch,publicKey:device.publicJwk,permissions}});
  if(approval.status!==200)throw Error(JSON.stringify(approval.body));
  const reply=await send('pair/redeem',{device,body:{ticket:approval.body.ticket,publicKey:device.publicJwk}});
  if(reply.status!==200)throw Error(JSON.stringify(reply.body));return device;
 };
 try{if(provision){await seed(rt,30);const ticket=await provisionOwner(owner),reply=await send('pair/redeem',{device:owner,body:{ticket,publicKey:owner.publicJwk}});if(reply.status!==200)throw Error(JSON.stringify(reply.body));await pair(worker);}}
 catch(e){await rt.close();throw e;}
 return {...rt,bindings:env,admin,owner,worker,send,pair,provisionOwner,prepare:(route,options={})=>preparePaired(options.device??owner,route,options)};
}
