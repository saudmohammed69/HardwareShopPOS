// Shop-device prototype. Never treats Firebase UID or a local POS role as trust.
import {fail,hash,canonical,uuid,exact,text} from './pricing.mjs';
const enc=new TextEncoder(),deny=()=>fail(403,'Shop device authorization failed');
export const ownerPermissions=['ORDERS_READ','ORDERS_WRITE','BILLING','CATALOG_ADMIN','DEVICE_ADMIN'];
export const workerPermissions=['ORDERS_READ','ORDERS_WRITE','BILLING'];
const decode=s=>{try{if(typeof s!=='string'||!/^[-_A-Za-z0-9]+$/.test(s))deny();return Uint8Array.from(atob(s.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));}catch{deny();}};
export function pairingPublicKey(k){
 if(!k||Object.keys(k).sort().join(',')!=='crv,kty,x,y'||k.kty!=='EC'||k.crv!=='P-256'||decode(k.x).length!==32||decode(k.y).length!==32)deny();
 return k;
}
async function verify(key,message,signature){
 try{const k=await crypto.subtle.importKey('jwk',pairingPublicKey(key),{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
  if(!await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},k,decode(signature),enc.encode(message)))deny();}catch{deny();}
}
async function configured(env,shop,db){
 if(env.AUTH_MODE!=='PAIRING_LOCAL'||env.LOCAL_PROOF_ONLY!=='true'||env.PAIR_QUARANTINED==='true')fail(503,'Local pairing authority unavailable');
 const now=Math.floor(Date.now()/1000);if(Number(env.PAIR_ADMIN_FRESH_UNTIL)<=now||!Number.isSafeInteger(Number(env.PAIR_AUTHORITY_EPOCH)))deny();
 let admin;try{admin=pairingPublicKey(JSON.parse(env.PAIR_ADMIN_PUBLIC_KEY));}catch{deny();}
 const fingerprint=await hash(canonical(admin)),row=await db.first('SELECT * FROM pair_authority WHERE shop=?',[shop]);
 if(!row||row.status!=='ACTIVE'||row.admin_fingerprint!==fingerprint||row.epoch!==Number(env.PAIR_AUTHORITY_EPOCH))deny();
 return {epoch:row.epoch,admin,fingerprint};
}
export async function pairingMessage(request){
 const reader=request.clone().body?.getReader();let count=0,parts=[];
 if(reader)while(true){const {done,value}=await reader.read();if(done)break;count+=value.length;if(count>32768){void reader.cancel();fail(413,'Request is too large');}parts.push(value);}
 const body=new Uint8Array(count);let offset=0;for(const p of parts){body.set(p,offset);offset+=p.length;}
 const bodyHash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',body)),x=>x.toString(16).padStart(2,'0')).join('');
 const h=name=>request.headers.get(name)??'';
 return ['HWPOS-SHOP-DEVICE-V1',request.method,new URL(request.url).href,bodyHash,h('x-device-id'),h('x-database-epoch'),h('x-device-time'),h('x-device-nonce'),h('x-operation-id')].join('\n');
}
async function proof(request,key,id,databaseEpoch,trace=()=>{}){
 const timestamp=request.headers.get('x-device-time'),nonce=request.headers.get('x-device-nonce'),now=Math.floor(Date.now()/1000);
 const ageSeconds=now-Number(timestamp);
 if(request.headers.has('authorization')||request.headers.get('x-device-id')!==id||request.headers.get('x-database-epoch')!==databaseEpoch||!/^\d{10}$/.test(timestamp??'')){trace('auth-denied',{reason:'PROOF_BINDING'});deny();}
 if(Math.abs(ageSeconds)>60){trace('auth-denied',{reason:'PROOF_EXPIRED',ageSeconds});deny();}
 uuid(nonce);await verify(key,await pairingMessage(request),request.headers.get('x-device-signature'));
 trace('auth-verified',{ageSeconds});
 return {device:id,nonce,expires:Number(timestamp)+61};
}
function permission(route){
 if(route==='pair/rotate')return 'ORDERS_READ';
 if(route.startsWith('pair/')&&route!=='pair/session')return 'DEVICE_ADMIN';
 if(route.startsWith('catalog/')||route==='owner/public-config')return 'CATALOG_ADMIN';
 if(/\/billing-claim$|\/complete$/.test(route))return 'BILLING';
 if(route.startsWith('orders/'))return 'ORDERS_WRITE';
 return 'ORDERS_READ';
}
export function pairingGuard(db,a){
 return db.statement(`INSERT INTO auth_guard(ok) SELECT CASE WHEN EXISTS(SELECT 1 FROM pair_devices d JOIN pair_authority p ON p.shop=d.shop WHERE d.id=? AND d.shop=? AND d.status='ACTIVE' AND d.version=? AND d.role=? AND d.permissions=? AND d.database_epoch=? AND d.expires_at>? AND d.authority_epoch=? AND p.epoch=d.authority_epoch AND p.status='ACTIVE' AND p.admin_fingerprint=?) THEN 1 ELSE 0 END`,[a.device,a.shop,a.deviceVersion,a.role,a.permissionsJson,a.databaseEpoch,Math.floor(Date.now()/1000),a.epoch,a.adminFingerprint]);
}
export async function authorizePaired(request,env,shop,db,{owner=false}={}){
 let authority;try{authority=await configured(env,shop,db);}catch(e){db.trace?.('auth-denied',{reason:'AUTHORITY'});throw e;}const id=uuid(request.headers.get('x-device-id'));
 const row=await db.first('SELECT * FROM pair_devices WHERE id=? AND shop=?',[id,shop]);
 if(!row||row.status!=='ACTIVE'||row.authority_epoch!==authority.epoch||row.expires_at<=Math.floor(Date.now()/1000)||(owner&&row.role!=='OWNER')){db.trace?.('auth-denied',{reason:'DEVICE_STATUS_EPOCH_EXPIRY_OR_ROLE'});deny();}
 const route=new URL(request.url).pathname.split('/').slice(3).join('/'),permissions=JSON.parse(row.permissions);
 if(!Array.isArray(permissions)||!permissions.includes(permission(route))){db.trace?.('auth-denied',{reason:'CAPABILITY'});deny();}
 let p;try{p=await proof(request,JSON.parse(row.public_key),id,row.database_epoch,db.trace);}catch(e){db.trace?.('auth-denied',{reason:'PROOF_REJECTED'});throw e;}
 const a={kind:'SHOP_DEVICE',uid:'installation:'+id,device:id,shop,role:row.role,deviceVersion:row.version,permissionsJson:row.permissions,databaseEpoch:row.database_epoch,epoch:authority.epoch,adminFingerprint:authority.fingerprint};
 db.setAuthority(a);
 if(request.method!=='GET')db.setProof({...p,shop});
 if(request.method==='POST'&&(/^orders\/[a-f0-9-]{36}\//.test(route)||['catalog/publish','catalog/publish-batch','owner/public-config'].includes(route))){
  const operation=uuid(request.headers.get('x-operation-id'));
  db.setOrderOperation({device:id,id:operation,shop,route,requestHash:await hash(await pairingMessage(request).then(s=>s.split('\n').slice(0,6).join('\n')))});
 }
 return a;
}
export function orderOperationStatements(db,context,orderId,operation){
 if(!context)return [];
 return [db.statement(`INSERT INTO pair_operations(shop,device,request_id,request_hash,order_id,result,at) SELECT ?,?,?,?,id,json_object('confirmed',json('true'),'id',id,'orderNumber','WO-'||UPPER(id),'status',status,'revision',revision,'totalPaisa',json_extract(doc,'$.estimatedSubtotalPaisa'),'estimateOnly',json('true'),'billId',bill_id),? FROM orders WHERE id=? AND operation=?`,[context.shop,context.device,context.id,context.requestHash,Date.now(),orderId,operation])];
}
function audit(db,shop,actor,action,target){return db.statement('INSERT INTO pair_audit VALUES(?,?,?,?,?,?)',[crypto.randomUUID(),shop,actor,action,target,Date.now()]);}
export async function pairingRoute(request,env,shop,route,db,readJson){
 const authority=await configured(env,shop,db);
 if(route==='pair/redeem'&&request.method==='POST'){
  const body=await readJson(request.clone());exact(body,['ticket','publicKey']);const key=pairingPublicKey(body.publicKey),ticket=body.ticket;
  if(!ticket||typeof ticket!=='object')deny();exact(ticket,['id','shop','epoch','deviceId','fingerprint','databaseEpoch','role','permissions','expires','secret','adminSignature'],['id','shop','epoch','deviceId','fingerprint','databaseEpoch','role','permissions','expires','secret']);
  uuid(ticket.id);uuid(ticket.deviceId);uuid(ticket.databaseEpoch);text(ticket.secret,64,64);
  const unsigned={...ticket};delete unsigned.adminSignature;
  const grant=await db.first('SELECT * FROM pair_grants WHERE id=? AND shop=?',[ticket.id,shop]),now=Math.floor(Date.now()/1000);
  if(!grant||ticket.shop!==shop||ticket.epoch!==authority.epoch||grant.authority_epoch!==authority.epoch||grant.ticket_hash!==await hash(canonical(unsigned))||grant.consumed!==0||grant.expires_at<=now||ticket.expires!==grant.expires_at||ticket.fingerprint!==await hash(canonical(key)))deny();
  if(grant.role==='OWNER'){await verify(authority.admin,canonical(unsigned),ticket.adminSignature);if(grant.approved_by!=='OUT_OF_BAND_ADMIN')deny();}
  else if(ticket.adminSignature!==undefined)deny();
  const p=await proof(request,key,grant.device_id,grant.database_epoch);db.setProof({...p,shop});
  // Redeem is not yet an active principal. Guard the grant/root atomically;
  // one-use consumption and installation creation share this same transaction.
  try{await db.batch([
   db.statement(`INSERT INTO auth_guard(ok) SELECT CASE WHEN EXISTS(SELECT 1 FROM pair_grants g JOIN pair_authority p ON p.shop=g.shop WHERE g.id=? AND g.shop=? AND g.ticket_hash=? AND g.consumed=0 AND g.expires_at>? AND p.status='ACTIVE' AND p.epoch=? AND p.admin_fingerprint=? AND ((g.role='OWNER' AND NOT EXISTS(SELECT 1 FROM pair_devices d WHERE d.shop=g.shop AND d.role='OWNER')) OR (g.role='WORKER' AND EXISTS(SELECT 1 FROM pair_devices d WHERE d.id=g.approved_by AND d.shop=g.shop AND d.role='OWNER' AND d.status='ACTIVE' AND d.expires_at>? AND d.authority_epoch=p.epoch)))) THEN 1 ELSE 0 END`,[grant.id,shop,grant.ticket_hash,now,authority.epoch,authority.fingerprint,now]),
   db.statement('UPDATE pair_grants SET consumed=1 WHERE id=? AND consumed=0',[grant.id]),
   db.statement("INSERT INTO pair_devices(id,shop,fingerprint,public_key,database_epoch,authority_epoch,role,permissions,status,expires_at,approved_by) VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,?)",[grant.device_id,shop,grant.fingerprint,canonical(key),grant.database_epoch,authority.epoch,grant.role,grant.permissions,now+86400,grant.approved_by]),
   ...db.proofStatements(),audit(db,shop,grant.approved_by,'PAIR',grant.device_id),db.statement('DELETE FROM auth_guard')
  ]);}finally{db.clearProof();}
  return {deviceId:grant.device_id,shop,role:grant.role,permissions:JSON.parse(grant.permissions),paired:true};
 }
 const a=await authorizePaired(request,env,shop,db,{owner:!['pair/session','pair/rotate'].includes(route)});
 if(route==='pair/session'&&request.method==='GET')return {deviceId:a.device,shop,role:a.role,permissions:JSON.parse(a.permissionsJson),databaseEpoch:a.databaseEpoch,authorityEpoch:a.epoch,expiresAt:(await db.first('SELECT expires_at FROM pair_devices WHERE id=?',[a.device])).expires_at};
 if(route==='pair/rotate'&&request.method==='POST'){
  const b=await readJson(request);exact(b,['rotationId','publicKey','newKeySignature']);uuid(b.rotationId);const key=pairingPublicKey(b.publicKey),fingerprint=await hash(canonical(key));
  await verify(key,['HWPOS-PAIR-ROTATE-V1',shop,a.device,a.databaseEpoch,String(a.epoch),b.rotationId,fingerprint].join('\n'),b.newKeySignature);
  // The current key authorizes, the staged key proves possession, and the same
  // installation/claims/expiry remain. No implicit renewal or ownership transfer.
  await db.batch([db.statement('UPDATE pair_devices SET fingerprint=?,public_key=?,version=version+1 WHERE id=? AND shop=? AND version=?',[fingerprint,canonical(key),a.device,shop,a.deviceVersion]),audit(db,shop,a.device,'ROTATE',b.rotationId)]);
  return {rotated:true,deviceId:a.device,fingerprint,rotationId:b.rotationId};
 }
 if(route==='pair/approve'&&request.method==='POST'){
  const body=await readJson(request);exact(body,['deviceId','databaseEpoch','publicKey','permissions']);uuid(body.deviceId);uuid(body.databaseEpoch);const key=pairingPublicKey(body.publicKey);
  if(!Array.isArray(body.permissions)||!body.permissions.includes('ORDERS_READ')||new Set(body.permissions).size!==body.permissions.length||body.permissions.some(x=>!workerPermissions.includes(x)))deny();
  const ticket={id:crypto.randomUUID(),shop,epoch:authority.epoch,deviceId:body.deviceId,fingerprint:await hash(canonical(key)),databaseEpoch:body.databaseEpoch,role:'WORKER',permissions:[...body.permissions].sort(),expires:Math.floor(Date.now()/1000)+300,secret:Array.from(crypto.getRandomValues(new Uint8Array(32)),x=>x.toString(16).padStart(2,'0')).join('')};
  await db.batch([db.statement('INSERT INTO pair_grants VALUES(?,?,?,?,?,?,?,?,?,?,?,0)',[ticket.id,shop,ticket.epoch,ticket.deviceId,ticket.fingerprint,ticket.databaseEpoch,ticket.role,JSON.stringify(ticket.permissions),await hash(canonical(ticket)),ticket.expires,a.device]),audit(db,shop,a.device,'APPROVE',ticket.deviceId)]);
  return {ticket};
 }
 if(route==='pair/revoke'&&request.method==='POST'){
  const body=await readJson(request);exact(body,['deviceId']);uuid(body.deviceId);
  if(body.deviceId===a.device)fail(409,'Sole owner recovery requires administrator review');
  await db.batch([db.statement("UPDATE pair_devices SET status='REVOKED',version=version+1 WHERE id=? AND shop=? AND status='ACTIVE'",[body.deviceId,shop]),audit(db,shop,a.device,'REVOKE',body.deviceId)]);return {revoked:true};
 }
 fail(404,'Unknown pairing route');
}
