// Local signed-token proof only. Public verification keys are provisioned by a
// trusted fixture. This module cannot fetch credentials, keys, or memberships.
import {fail,exact,text,uuid,hash,canonical} from './pricing.mjs';
const encoder=new TextEncoder();
const reject=()=>fail(403,'Authentication or device authorization failed');
function decode(value){
 if(typeof value!=='string'||!value||!/^[-_A-Za-z0-9]+$/.test(value))reject();
 try{return Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0));}catch{reject();}
}
const object=value=>{try{const result=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(decode(value)));if(!result||Array.isArray(result)||typeof result!=='object')reject();return result;}catch{reject();}};
export async function verifyIdToken(request,env){
 // No production issuer is allowed in this phase. Never infer a trust domain
 // from unverified token fields, jku, x5u, headers, or client role claims.
 if(env.AUTH_MODE!=='SIGNED_LOCAL'||env.LOCAL_PROOF_ONLY!=='true'||env.AUTH_PROJECT!=='demo-hardwareshop-phase4b')fail(503,'Signed local authentication is not configured');
 const now=Math.floor(Date.now()/1000);let keys;
 try{keys=JSON.parse(env.LOCAL_AUTH_JWKS).keys;}catch{fail(503,'Trusted verification keys unavailable');}
 if(!Array.isArray(keys)||keys.length>5||!Number.isSafeInteger(Number(env.AUTH_KEYS_EXPIRES_AT))||Number(env.AUTH_KEYS_EXPIRES_AT)<=now)fail(503,'Trusted verification keys unavailable');
 const authorization=request.headers.get('authorization')??'';
 if(!authorization.startsWith('Bearer ')||authorization.length>8192)reject();
 const token=authorization.slice(7),parts=token.split('.');if(parts.length!==3)reject();
 const header=object(parts[0]),claims=object(parts[1]);
 if(header.alg!=='RS256'||typeof header.kid!=='string'||header.kid.length>100||Object.keys(header).some(k=>!['alg','typ','kid'].includes(k))||(header.typ!==undefined&&header.typ!=='JWT'))reject();
 const key=keys.find(key=>key.kid===header.kid);
 if(!key||key.kty!=='RSA'||key.alg!=='RS256'||key.use!=='sig'||key.d)reject();
 let valid=false;try{
  const imported=await crypto.subtle.importKey('jwk',key,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
  valid=await crypto.subtle.verify('RSASSA-PKCS1-v1_5',imported,decode(parts[2]),encoder.encode(parts[0]+'.'+parts[1]));
 }catch{reject();}
 if(!valid||claims.iss!==`https://securetoken.google.com/${env.AUTH_PROJECT}`||claims.aud!==env.AUTH_PROJECT||
  typeof claims.sub!=='string'||claims.sub.length<1||claims.sub.length>128||/[\r\n\u0000]/.test(claims.sub)||
  !['iat','exp','auth_time'].every(k=>Number.isSafeInteger(claims[k]))||claims.iat>now||claims.auth_time>claims.iat||claims.auth_time<0||
  claims.exp<=now||claims.exp<=claims.iat||claims.exp-claims.iat>3600||(claims.nbf!==undefined&&(!Number.isSafeInteger(claims.nbf)||claims.nbf>now)))reject();
 return {uid:claims.sub,issued:claims.iat,authenticated:claims.auth_time,expires:claims.exp,token};
}
async function membership(db,shop,identity){
 const row=await db.first("SELECT m.role,m.version,u.disabled,MAX(u.valid_after,m.valid_after) valid_after,MIN(u.checked_until,i.checked_until) checked_until,i.person_id,(SELECT worker_ref FROM auth_worker_links w WHERE w.shop=m.shop AND w.uid=m.uid) worker_ref FROM auth_memberships m JOIN auth_users u ON u.uid=m.uid JOIN auth_identity_bindings i ON i.uid=u.uid AND i.kind='INDIVIDUAL' WHERE m.shop=? AND m.uid=? AND m.active=1 AND (m.role='OWNER' OR EXISTS(SELECT 1 FROM auth_worker_links w WHERE w.shop=m.shop AND w.uid=m.uid))",[shop,identity.uid]);
 const now=Math.floor(Date.now()/1000);
 if(!row||row.disabled||row.checked_until<=now||identity.authenticated<row.valid_after)reject();
 return {...identity,shop,role:row.role,membershipVersion:row.version,validAfter:row.valid_after,personId:row.person_id,workerRef:row.worker_ref};
}
export function publicKey(jwk){
 exact(jwk,['kty','crv','x','y']);if(jwk.kty!=='EC'||jwk.crv!=='P-256'||typeof jwk.x!=='string'||typeof jwk.y!=='string'||decode(jwk.x).length!==32||decode(jwk.y).length!==32)reject();
 return {kty:'EC',crv:'P-256',x:jwk.x,y:jwk.y};
}
export async function proofMessage(request,token,device,keyVersion,timestamp,nonce){
 // Bound buffering before hashing, including bodies without Content-Length.
 if(Number(request.headers.get('content-length')??0)>32768)fail(413,'Request is too large');
 const reader=request.clone().body?.getReader(),chunks=[];let length=0;
 if(reader)while(true){const {done,value}=await reader.read();if(done)break;length+=value.byteLength;
  if(length>32768){void reader.cancel().catch(()=>{});fail(413,'Request is too large');}chunks.push(value);}
 const bytes=new Uint8Array(length);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}
 const bodyHash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
 return ['HWPOS-DEVICE-V1',request.method,new URL(request.url).href,bodyHash,await hash(token),device,String(keyVersion),String(timestamp),nonce].join('\n');
}
async function verifyProof(request,identity,key,device,version){
 const timestamp=request.headers.get('x-device-time'),nonce=request.headers.get('x-device-nonce'),signature=request.headers.get('x-device-signature');
 const now=Math.floor(Date.now()/1000);
 if(request.headers.get('x-device-id')!==device||request.headers.get('x-device-key-version')!==String(version)||!/^\d{10}$/.test(timestamp??'')||Math.abs(now-Number(timestamp))>60)reject();
 uuid(nonce);let valid=false;
 try{const imported=await crypto.subtle.importKey('jwk',publicKey(key),{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
  valid=await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},imported,decode(signature),encoder.encode(await proofMessage(request,identity.token,device,version,Number(timestamp),nonce)));}catch{reject();}
 if(!valid)reject();return {device,nonce,expires:Number(timestamp)+61};
}
// Rechecked inside every protected SQL batch, so revocation committed after
// signature verification but before an order/settings write aborts the write.
export function authorityGuard(db,a){
 const now=Math.floor(Date.now()/1000),deviceClause=a.deviceVersion===undefined?'':" AND EXISTS(SELECT 1 FROM auth_devices d WHERE d.id=? AND d.shop=m.shop AND d.uid=m.uid AND d.status='ACTIVE' AND d.version=? AND d.key_version=? AND d.expires_at>?)";
 const args=[a.shop,a.uid,a.role,a.membershipVersion,a.personId,a.expires,now,a.authenticated,a.authenticated,now,a.workerRef??'',...(a.deviceVersion===undefined?[]:[a.device,a.deviceVersion,a.keyVersion,now])];
 return db.statement(`INSERT INTO auth_guard(ok) SELECT CASE WHEN EXISTS(SELECT 1 FROM auth_memberships m JOIN auth_users u ON u.uid=m.uid JOIN auth_identity_bindings i ON i.uid=u.uid AND i.kind='INDIVIDUAL' WHERE m.shop=? AND m.uid=? AND m.role=? AND m.version=? AND i.person_id=? AND m.active=1 AND u.disabled=0 AND ?>? AND u.valid_after<=? AND m.valid_after<=? AND MIN(u.checked_until,i.checked_until)>? AND (m.role='OWNER' OR EXISTS(SELECT 1 FROM auth_worker_links w WHERE w.shop=m.shop AND w.uid=m.uid AND w.worker_ref=?))${deviceClause}) THEN 1 ELSE 0 END`,args);
}
async function consume(db,a,proof){
 db.setAuthority(a);
 // The database wrapper consumes this proof atomically with the first successful
 // mutation, or before returning an idempotent POST result. It never drops a
 // successful mutation's nonce or commits a business effect ahead of its proof.
 db.setProof({...proof,shop:a.shop});
}
export async function authorizeSigned(request,env,shop,db,{owner=false,enrollmentKey}={}){
 const identity=await membership(db,shop,await verifyIdToken(request,env));
 if(owner&&(identity.role!=='OWNER'||Math.floor(Date.now()/1000)-identity.authenticated>300))reject();
 if(enrollmentKey){const key=publicKey(enrollmentKey),device='enroll:'+await hash(canonical(key));
  const proof=await verifyProof(request,identity,key,device,1);await consume(db,identity,proof);return identity;}
 const device=request.headers.get('x-device-id');if(!/^[a-f0-9-]{36}$/.test(device??''))reject();
 const row=await db.first("SELECT * FROM auth_devices WHERE id=? AND shop=? AND uid=? AND status='ACTIVE'",[device,shop,identity.uid]);
 if(!row||row.expires_at<=Math.floor(Date.now()/1000))reject();
 const proof=await verifyProof(request,identity,JSON.parse(row.public_key),device,row.key_version);
 const principal={...identity,device,deviceVersion:row.version,keyVersion:row.key_version};
 // These exact GET routes have no mutations, acknowledgements, nonce pruning or
 // sliding expiry. Identical reads may replay within the signed 60-second window;
 // every replay still verifies JWT, signature, current revocation and membership.
 const route=new URL(request.url).pathname.split('/').slice(3).join('/');
 if(request.method==='GET'&&['pos/events','owner/storage','auth/session'].includes(route))db.setAuthority(principal);
 else await consume(db,principal,proof);
 return principal;
}
function audit(db,a,action,target){return db.statement('INSERT INTO auth_audit(id,shop,actor,device,action,target,at) VALUES(?,?,?,?,?,?,?)',[
 crypto.randomUUID(),a.shop,a.uid,a.device??'pending-key',action,target,Date.now()]);}
function condition(db,select,args){return db.statement(`INSERT INTO auth_guard(ok) SELECT CASE WHEN EXISTS(${select}) THEN 1 ELSE 0 END`,args);}
export function bootstrapGuard(db,shop,identity,grant){
 const now=Math.floor(Date.now()/1000),recovering=grant.device_id!==null;
 const state=recovering?'b.device_id=? AND b.request_hash=?':"b.device_id IS NULL AND NOT EXISTS(SELECT 1 FROM auth_memberships WHERE shop=b.shop AND role='OWNER')";
 return condition(db,`SELECT 1 FROM auth_users u JOIN auth_identity_bindings i ON i.uid=u.uid WHERE u.uid=? AND u.disabled=0 AND i.kind='INDIVIDUAL' AND MIN(u.checked_until,i.checked_until)>? AND u.valid_after<=? AND ?>? AND EXISTS(SELECT 1 FROM auth_bootstrap b WHERE b.shop=? AND b.uid=? AND b.fingerprint=? AND b.grant_hash=? AND b.expires_at>? AND ${state})`,
  [identity.uid,now,identity.authenticated,identity.expires,now,shop,identity.uid,grant.fingerprint,grant.grant_hash,now,...(recovering?[grant.device_id,grant.request_hash]:[])]);
}
const summary=d=>({deviceId:d.id,status:d.status,keyVersion:d.key_version,version:d.version,expiresAt:d.expires_at});
export async function securityRoute(request,env,shop,route,db,readJson){
 if(env.AUTH_MODE!=='SIGNED_LOCAL')fail(503,'Signed local authentication is required');
 if(route==='auth/bootstrap'&&request.method==='POST'){
  const body=await readJson(request.clone());exact(body,['requestId','publicKey','grant']);uuid(body.requestId);text(body.grant,128,32);
  const identity=await verifyIdToken(request,env),now=Math.floor(Date.now()/1000),key=publicKey(body.publicKey),fingerprint=await hash(canonical(key));
  if(now-identity.authenticated>300)reject();
  const trusted=await db.first("SELECT u.disabled,u.valid_after,MIN(u.checked_until,i.checked_until) checked_until FROM auth_users u JOIN auth_identity_bindings i ON i.uid=u.uid AND i.kind='INDIVIDUAL' WHERE u.uid=?",[identity.uid]);
  if(!trusted||trusted.disabled||trusted.checked_until<=now||identity.authenticated<trusted.valid_after)reject();
  const proof=await verifyProof(request,identity,key,'enroll:'+fingerprint,1),requestHash=await hash(canonical(body));
  const grant=await db.first('SELECT * FROM auth_bootstrap WHERE shop=? AND uid=? AND fingerprint=? AND grant_hash=?',[shop,identity.uid,fingerprint,await hash(body.grant)]);
  if(!grant||grant.expires_at<=now)reject();
  if(grant.device_id){
   if(grant.request_hash!==requestHash)reject();
   // Idempotent recovery still consumes a fresh one-use proof, and rechecks the
   // exact trusted grant atomically. An already-used signed POST cannot replay.
   await db.batch([bootstrapGuard(db,shop,identity,grant),
    db.statement('DELETE FROM auth_nonces WHERE expires_at<?',[Math.floor(Date.now()/1000)]),
    db.statement('INSERT INTO auth_nonces(device,nonce,expires_at,shop) VALUES(?,?,?,?)',[proof.device,proof.nonce,proof.expires,shop]),db.statement('DELETE FROM auth_guard')]);
   // Recovery returns the original record; it never changes its revoked status.
   return summary(await db.first('SELECT * FROM auth_devices WHERE id=?',[grant.device_id]));
  }
  const id=crypto.randomUUID();await db.batch([
   bootstrapGuard(db,shop,identity,grant),
   db.statement('INSERT INTO auth_nonces(device,nonce,expires_at,shop) VALUES(?,?,?,?)',[proof.device,proof.nonce,proof.expires,shop]),
   db.statement("INSERT INTO auth_memberships(shop,uid,role,active) VALUES(?,?,'OWNER',1)",[shop,identity.uid]),
   db.statement("INSERT INTO auth_devices(id,shop,uid,enrollment_id,enrollment_hash,public_key,fingerprint,status,created_at,expires_at,approved_by) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,?)",[id,shop,identity.uid,body.requestId,requestHash,JSON.stringify(key),fingerprint,now,now+30*86400,'trusted-local-bootstrap']),
   db.statement('UPDATE auth_bootstrap SET request_hash=?,device_id=? WHERE shop=?',[requestHash,id,shop]),
   audit(db,{...identity,shop,device:id},'OWNER_BOOTSTRAPPED',id),db.statement('DELETE FROM auth_guard')
  ]);return summary(await db.first('SELECT * FROM auth_devices WHERE id=?',[id]));
 }
 if(route==='auth/enroll'&&request.method==='POST'){
  const body=await readJson(request.clone());exact(body,['requestId','publicKey']);uuid(body.requestId);const key=publicKey(body.publicKey);
  const a=await authorizeSigned(request,env,shop,db,{enrollmentKey:key}),fingerprint=await hash(canonical(key)),enrollmentHash=await hash(canonical(body));
  const existing=await db.first('SELECT * FROM auth_devices WHERE shop=? AND uid=? AND enrollment_id=?',[shop,a.uid,body.requestId]);
  if(existing){if(existing.enrollment_hash!==enrollmentHash)fail(409,'Enrollment identity already used');return summary(existing);}
  const count=await db.first('SELECT COUNT(*) n FROM auth_devices WHERE shop=? AND uid=?',[shop,a.uid]);
  if(count.n>=20)fail(409,'Owner review required before more device enrollments');
  const id=crypto.randomUUID(),now=Math.floor(Date.now()/1000);
  // Failed duplicate key or request is recoverable with the same persisted requestId.
  try{await db.batch([
   condition(db,'SELECT 1 WHERE (SELECT COUNT(*) FROM auth_devices WHERE shop=? AND uid=?)<20',[shop,a.uid]),
   db.statement("INSERT INTO auth_devices(id,shop,uid,enrollment_id,enrollment_hash,public_key,fingerprint,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",[id,shop,a.uid,body.requestId,enrollmentHash,JSON.stringify(key),fingerprint,now,now+86400]),
   audit(db,a,'ENROLLMENT_REQUESTED',id)
  ]);}catch(e){if(String(e).includes('UNIQUE constraint'))fail(409,'Enrollment already exists; recover the original request');throw e;}
  return summary(await db.first('SELECT * FROM auth_devices WHERE id=?',[id]));
 }
 const a=await authorizeSigned(request,env,shop,db,{owner:route!=='auth/session'&&route!=='auth/rotate'});
 if(route==='auth/session'&&request.method==='GET'){
  const binding=await db.first('SELECT database_epoch,account_hash,membership_version FROM auth_local_worker_bindings WHERE device=? AND shop=? AND uid=? AND person_id=? AND worker_ref=? AND membership_version=?',[a.device,shop,a.uid,a.personId,a.workerRef??'',a.membershipVersion]);
  return {uid:a.uid,personId:a.personId,shop,role:a.role,workerRef:a.workerRef,deviceId:a.device,keyVersion:a.keyVersion,membershipVersion:a.membershipVersion,
   localBinding:binding?{databaseEpoch:binding.database_epoch,accountHash:binding.account_hash}:null};
 }
 if(request.method!=='POST')fail(405,'Method not allowed');
 const body=await readJson(request);
 if(route==='auth/bind-local-worker'){
  exact(body,['deviceId','uid','personId','workerRef','databaseEpoch','accountHash','expectedMembershipVersion']);
  uuid(body.deviceId);uuid(body.databaseEpoch);text(body.uid,128,1);text(body.personId,128,1);text(body.workerRef,128,1);
  if(!/^[a-f0-9]{64}$/.test(body.accountHash)||!Number.isSafeInteger(body.expectedMembershipVersion))reject();
  const old=await db.first('SELECT * FROM auth_local_worker_bindings WHERE device=?',[body.deviceId]);
  if(old){if(old.shop!==shop||old.uid!==body.uid||old.person_id!==body.personId||old.worker_ref!==body.workerRef||old.database_epoch!==body.databaseEpoch||old.account_hash!==body.accountHash||old.membership_version!==body.expectedMembershipVersion)fail(409,'Binding is immutable; owner must review a replacement installation');return {bound:true};}
  await db.batch([
   condition(db,"SELECT 1 FROM auth_devices d JOIN auth_memberships m ON m.shop=d.shop AND m.uid=d.uid JOIN auth_identity_bindings i ON i.uid=m.uid JOIN auth_users u ON u.uid=i.uid JOIN auth_worker_links w ON w.shop=m.shop AND w.uid=m.uid WHERE d.id=? AND d.shop=? AND d.uid=? AND d.status='ACTIVE' AND d.expires_at>? AND m.role='WORKER' AND m.active=1 AND m.version=? AND i.person_id=? AND i.kind='INDIVIDUAL' AND MIN(i.checked_until,u.checked_until)>? AND u.disabled=0 AND w.worker_ref=?",[body.deviceId,shop,body.uid,Math.floor(Date.now()/1000),body.expectedMembershipVersion,body.personId,Math.floor(Date.now()/1000),body.workerRef]),
   db.statement('INSERT INTO auth_local_worker_bindings(device,shop,uid,person_id,worker_ref,database_epoch,account_hash,membership_version,owner,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)',[body.deviceId,shop,body.uid,body.personId,body.workerRef,body.databaseEpoch,body.accountHash,body.expectedMembershipVersion,a.uid,Date.now()]),audit(db,a,'LOCAL_WORKER_BINDING_APPROVED',body.deviceId)
  ]);return {bound:true};
 }
 if(route==='auth/approve-worker'){
  exact(body,['uid','workerRef']);const uid=text(body.uid,128,1),worker=text(body.workerRef,128,1),now=Math.floor(Date.now()/1000);
  // The owner approves a preverified individual UID. An existing membership can
  // never be revived through this route; recovery requires a separate review.
  await db.batch([
   condition(db,"SELECT 1 FROM auth_users u JOIN auth_identity_bindings i ON i.uid=u.uid WHERE u.uid=? AND u.disabled=0 AND i.kind='INDIVIDUAL' AND MIN(u.checked_until,i.checked_until)>? AND NOT EXISTS(SELECT 1 FROM auth_memberships WHERE shop=? AND uid=?)",[uid,now,shop,uid]),
   db.statement("INSERT INTO auth_memberships(shop,uid,role,active) VALUES(?,?,'WORKER',1)",[shop,uid]),
   db.statement('INSERT INTO auth_worker_links(shop,uid,worker_ref) VALUES(?,?,?)',[shop,uid,worker]),audit(db,a,'INDIVIDUAL_WORKER_APPROVED',uid)
  ]);return {approved:true};
 }
 if(route==='auth/reconcile-claim'){
  exact(body,['orderId','expectedRevision','billId','posBillSyncId','evidence','decision']);uuid(body.orderId);uuid(body.billId);
  text(body.posBillSyncId,100,16);text(body.evidence,1000,20);
  if(!['BILLED_EVIDENCE_SUBMITTED','UNRESOLVED'].includes(body.decision))reject();
  const row=await db.first("SELECT o.* FROM orders o JOIN auth_devices d ON d.id=o.lock_device AND d.shop=o.shop WHERE o.id=? AND o.shop=? AND d.status='REVOKED'",[body.orderId,shop]);
  if(!row||row.status!=='BILLING_IN_PROGRESS'||row.revision!==body.expectedRevision||row.bill_id!==body.billId)fail(409,'Original sticky claim must remain locked; review its finalized bill evidence');
  const evidenceHash=await hash(canonical(body)),existing=await db.first('SELECT id FROM auth_claim_reviews WHERE shop=? AND order_id=? AND evidence_hash=?',[shop,row.id,evidenceHash]);
  if(existing)return {reviewId:existing.id,status:'REVIEW_REQUIRED',claimReassigned:false};
  const id=crypto.randomUUID();await db.batch([
   condition(db,"SELECT 1 FROM orders o JOIN auth_devices d ON d.id=o.lock_device WHERE o.id=? AND o.shop=? AND o.revision=? AND o.bill_id=? AND o.status='BILLING_IN_PROGRESS' AND d.status='REVOKED'",[row.id,shop,row.revision,row.bill_id]),
   db.statement('INSERT INTO auth_claim_reviews(id,shop,order_id,bill_id,original_device,revision,evidence_hash,evidence_doc,owner,decision,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)',[id,shop,row.id,row.bill_id,row.lock_device,row.revision,evidenceHash,JSON.stringify(body),a.uid,body.decision,Date.now()]),audit(db,a,'REVOKED_CLAIM_REVIEW_RECORDED',id)
  ]);
  return {reviewId:id,status:'REVIEW_REQUIRED',claimReassigned:false,completionBlocked:true};
 }
 if(route==='auth/approve'){
  exact(body,['deviceId','expectedVersion']);uuid(body.deviceId);
  const target=await db.first('SELECT * FROM auth_devices WHERE id=? AND shop=?',[body.deviceId,shop]);
  if(!target||!Number.isSafeInteger(body.expectedVersion))reject();
  const m=await db.first('SELECT active FROM auth_memberships WHERE shop=? AND uid=?',[shop,target.uid]);if(!m?.active)reject();
  if(target.status==='ACTIVE'&&target.approved_by===a.uid&&target.version===body.expectedVersion+1)return summary(target);
  const now=Math.floor(Date.now()/1000);
  const result=await db.batch([
   condition(db,"SELECT 1 FROM auth_devices d JOIN auth_memberships m ON m.shop=d.shop AND m.uid=d.uid JOIN auth_users u ON u.uid=d.uid WHERE d.id=? AND d.shop=? AND d.version=? AND d.status='PENDING' AND d.expires_at>? AND m.active=1 AND u.disabled=0 AND u.checked_until>?",[target.id,shop,body.expectedVersion,now,now]),
   db.statement("UPDATE auth_devices SET status='ACTIVE',version=version+1,approved_by=?,expires_at=? WHERE id=? AND shop=? AND version=? AND status='PENDING' AND expires_at>? AND EXISTS(SELECT 1 FROM auth_memberships m JOIN auth_users u ON u.uid=m.uid WHERE m.shop=? AND m.uid=? AND m.active=1 AND u.disabled=0 AND u.checked_until>?)",[a.uid,now+30*86400,target.id,shop,body.expectedVersion,now,shop,target.uid,now]),
   audit(db,a,'DEVICE_APPROVED',target.id)
  ]);return summary(await db.first('SELECT * FROM auth_devices WHERE id=?',[target.id]));
 }
 if(route==='auth/revoke-device'){
  exact(body,['deviceId']);uuid(body.deviceId);
  const result=await db.batch([
   db.statement("UPDATE auth_devices SET status='REVOKED',version=version+1 WHERE id=? AND shop=? AND status<>'REVOKED'",[body.deviceId,shop]),
   audit(db,a,'DEVICE_REVOKED',body.deviceId)
  ]);return {revoked:true,changed:result[0].meta.changes===1};
 }
 if(route==='auth/remove-worker'){
  exact(body,['uid','expectedVersion']);const uid=text(body.uid,128,1);if(!Number.isSafeInteger(body.expectedVersion)||uid===a.uid)reject();
  const result=await db.batch([
   condition(db,"SELECT 1 FROM auth_memberships WHERE shop=? AND uid=? AND role='WORKER' AND version=? AND active=1",[shop,uid,body.expectedVersion]),
   db.statement("UPDATE auth_memberships SET active=0,version=version+1 WHERE shop=? AND uid=? AND role='WORKER' AND version=? AND active=1",[shop,uid,body.expectedVersion]),
   db.statement("UPDATE auth_devices SET status='REVOKED',version=version+1 WHERE shop=? AND uid=?",[shop,uid]),
   audit(db,a,'WORKER_REMOVED',uid)
  ]);return {removed:true};
 }
 if(route==='auth/revoke-tokens'){
  exact(body,['uid']);const uid=text(body.uid,128,1);
  const target=await db.first('SELECT role FROM auth_memberships WHERE shop=? AND uid=?',[shop,uid]);if(!target||target.role!=='WORKER')reject();
  await db.batch([
   db.statement('UPDATE auth_memberships SET valid_after=MAX(valid_after,?),version=version+1 WHERE shop=? AND uid=? AND role=\'WORKER\'',[Math.floor(Date.now()/1000)+1,shop,uid]),audit(db,a,'SHOP_SESSIONS_REVOKED',uid)
  ]);return {revoked:true};
 }
 if(route==='auth/rotate'){
  exact(body,['publicKey','rotationSignature']);const key=publicKey(body.publicKey),fingerprint=await hash(canonical(key));
  const message=['HWPOS-ROTATE-V1',a.device,String(a.keyVersion+1),fingerprint,request.headers.get('x-device-nonce')].join('\n');
  let valid=false;try{const imported=await crypto.subtle.importKey('jwk',key,{name:'ECDSA',namedCurve:'P-256'},false,['verify']);valid=await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},imported,decode(body.rotationSignature),encoder.encode(message));}catch{reject();}
  if(!valid)reject();
  await db.batch([
   db.statement('UPDATE auth_devices SET public_key=?,fingerprint=?,key_version=key_version+1,version=version+1 WHERE id=? AND key_version=?',[JSON.stringify(key),fingerprint,a.device,a.keyVersion]),audit(db,a,'DEVICE_KEY_ROTATED',a.device)
  ]);return {deviceId:a.device,keyVersion:a.keyVersion+1};
 }
 fail(404,'Unknown security route');
}
