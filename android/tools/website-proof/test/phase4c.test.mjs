import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {copyFile,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {signedFixture,newDevice,fingerprint} from '../security-fixture.mjs';
import {call,draft,token,product,runtime} from '../runtime.mjs';
import {exportSecurity,restoreSecurity} from '../security-archive.mjs';
import {bootstrapGuard} from '../security.mjs';
const ok=r=>{assert.ok(r.status<300,JSON.stringify({status:r.status,body:r.body}));return r.body;};
const deny=r=>assert.ok([400,403,404,409,503].includes(r.status),JSON.stringify(r.body));
const owner=r=>({uid:'synthetic-owner',device:r.owner});
async function use(fn){const r=await signedFixture();try{await fn(r);}finally{await r.close();}}
async function individual(r,uid,kind='INDIVIDUAL'){
 const until=Math.floor(Date.now()/1000)+3600;
 await r.db.prepare('INSERT INTO auth_users(uid,checked_until) VALUES(?,?)').bind(uid,until).run();
 await r.db.prepare('INSERT INTO auth_identity_bindings VALUES(?,?,?,?)').bind(uid,'person-'+uid,kind,until).run();
}
test('individual identity and trusted worker links reject shared UID, impersonation, stale binding and role injection',()=>use(async r=>{
 await r.db.prepare("UPDATE auth_identity_bindings SET kind='SHARED' WHERE uid='synthetic-worker'").run();
 deny(await r.send('auth/session',{method:'GET',jwt:r.token('synthetic-worker',{workerId:'owner',role:'OWNER'})}));
 await r.db.prepare("UPDATE auth_identity_bindings SET kind='INDIVIDUAL' WHERE uid='synthetic-worker'").run();
 await r.db.prepare("DELETE FROM auth_worker_links WHERE uid='synthetic-worker'").run();deny(await r.send('auth/session',{method:'GET'}));
 await r.db.prepare("INSERT INTO auth_worker_links VALUES('proof-shop','synthetic-worker','worker-synthetic-worker')").run();
 const session=ok(await r.send('auth/session',{method:'GET'}));assert.equal(session.personId,'person-synthetic-worker');assert.equal(session.role,'WORKER');
 await r.db.prepare("UPDATE auth_identity_bindings SET checked_until=0 WHERE uid='synthetic-worker'").run();deny(await r.send('auth/session',{method:'GET'}));
}));
test('only recently authenticated owner can approve a preverified individual worker; links are unique and removal is not reversible by approval',()=>use(async r=>{
 await individual(r,'new-individual');await individual(r,'shared-login','SHARED');
 deny(await r.send('auth/approve-worker',{body:{uid:'new-individual',workerRef:'local-worker-42'}}));
 deny(await r.send('auth/approve-worker',{...owner(r),jwt:r.token('synthetic-owner',{auth_time:Math.floor(Date.now()/1000)-301}),body:{uid:'new-individual',workerRef:'local-worker-42'}}));
 deny(await r.send('auth/approve-worker',{...owner(r),body:{uid:'shared-login',workerRef:'local-worker-42'}}));
 ok(await r.send('auth/approve-worker',{...owner(r),body:{uid:'new-individual',workerRef:'local-worker-42'}}));
 const device=r.enrollmentDevice(),b={requestId:crypto.randomUUID(),publicKey:device.publicJwk};
 const pending=ok(await r.send('auth/enroll',{uid:'new-individual',device,body:b}));
 deny(await r.send('auth/session',{method:'GET',uid:'new-individual',device:{...device,deviceId:pending.deviceId}}));
 ok(await r.send('auth/approve',{...owner(r),body:{deviceId:pending.deviceId,expectedVersion:1}}));
 assert.equal(ok(await r.send('auth/session',{method:'GET',uid:'new-individual',device:{...device,deviceId:pending.deviceId}})).role,'WORKER');
 ok(await r.send('auth/remove-worker',{...owner(r),body:{uid:'new-individual',expectedVersion:1}}));
 deny(await r.send('auth/approve-worker',{...owner(r),body:{uid:'new-individual',workerRef:'local-worker-42'}}));
 await individual(r,'another');deny(await r.send('auth/approve-worker',{...owner(r),body:{uid:'another',workerRef:'local-worker-42'}}));
}));
test('owner bootstrap needs out-of-band shop UID and key-bound one-use grant; lost response recovers same identity without reviving revoked key',()=>use(async r=>{
 // Remove only the synthetic preseed owner; never touches any real DB.
 await r.db.prepare("DELETE FROM auth_devices WHERE uid='synthetic-owner'").run();await r.db.prepare("DELETE FROM auth_memberships WHERE uid='synthetic-owner'").run();
 const d=r.enrollmentDevice(),grant=token(),body={requestId:crypto.randomUUID(),publicKey:d.publicJwk,grant};
 deny(await r.send('auth/bootstrap',{uid:'synthetic-owner',device:d,body}));
 await r.db.prepare('INSERT INTO auth_bootstrap(shop,uid,fingerprint,grant_hash,expires_at) VALUES(?,?,?,?,?)').bind('proof-shop','synthetic-owner',fingerprint(d.publicJwk),createHash('sha256').update(grant).digest('hex'),Math.floor(Date.now()/1000)+120).run();
 deny(await r.send('auth/bootstrap',{device:d,body}));
 const wrong=r.enrollmentDevice();deny(await r.send('auth/bootstrap',{uid:'synthetic-owner',device:wrong,body:{...body,publicKey:wrong.publicJwk}}));
 const initial=r.prepare('auth/bootstrap',{uid:'synthetic-owner',device:d,body});
 const first=ok(await call(r,initial.route,initial.options));deny(await call(r,initial.route,initial.options));
 assert.equal(ok(await r.send('auth/bootstrap',{uid:'synthetic-owner',device:d,body})).deviceId,first.deviceId);
 const recovery=r.prepare('auth/bootstrap',{uid:'synthetic-owner',device:d,body});ok(await call(r,recovery.route,recovery.options));deny(await call(r,recovery.route,recovery.options));
 const active={...d,deviceId:first.deviceId};ok(await r.send('auth/session',{uid:'synthetic-owner',device:active,method:'GET'}));
 await r.db.prepare("UPDATE auth_devices SET status='REVOKED' WHERE id=?").bind(first.deviceId).run();
 assert.equal(ok(await r.send('auth/bootstrap',{uid:'synthetic-owner',device:d,body})).status,'REVOKED');
 deny(await r.send('auth/session',{uid:'synthetic-owner',device:active,method:'GET'}));
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM auth_memberships WHERE role='OWNER'").first()).n,1);
}));
test('trusted bootstrap grant replacement after lookup aborts owner creation atomically',()=>use(async r=>{
 await r.db.prepare("DELETE FROM auth_devices WHERE uid='synthetic-owner'").run();await r.db.prepare("DELETE FROM auth_memberships WHERE uid='synthetic-owner'").run();
 const now=Math.floor(Date.now()/1000),identity={uid:'synthetic-owner',authenticated:now,expires:now+3600};
 await r.db.prepare('INSERT INTO auth_bootstrap(shop,uid,fingerprint,grant_hash,expires_at) VALUES(?,?,?,?,?)').bind('proof-shop',identity.uid,fingerprint(r.owner.publicJwk),token(),now+120).run();
 const snapshot=await r.db.prepare("SELECT * FROM auth_bootstrap WHERE shop='proof-shop'").first();
 await r.db.prepare("UPDATE auth_bootstrap SET grant_hash=? WHERE shop='proof-shop'").bind(token()).run();
 const db={statement:(sql,args=[])=>r.db.prepare(sql).bind(...args)};
 await assert.rejects(r.db.batch([bootstrapGuard(db,'proof-shop',identity,snapshot),r.db.prepare("INSERT INTO auth_memberships(shop,uid,role,active) VALUES('proof-shop','synthetic-owner','OWNER',1)"),r.db.prepare('DELETE FROM auth_guard')]));
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM auth_memberships WHERE shop='proof-shop' AND role='OWNER'").first()).n,0);
}));
test('safe signed GET replay writes zero rows but rechecks revocation, membership freshness, proof and route every time',()=>use(async r=>{
 const p=r.prepare('pos/events?cursor=0',{method:'GET'}),before=(await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n;
 for(let i=0;i<3;i++){const result=await call(r,p.route,p.options);ok(result);assert.equal(result.headers.get('x-proof-db-rows-written'),'0');}
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n,before);
 deny(await call(r,'pos/events?cursor=1',p.options));
 deny(await r.send('auth/session',{method:'GET',timestamp:Math.floor(Date.now()/1000)-61}));
 ok(await r.send('auth/revoke-device',{...owner(r),body:{deviceId:r.worker.deviceId}}));deny(await call(r,p.route,p.options));
 // GET to a mutation route cannot run it and cannot opt out via a header.
 deny(await r.send('auth/rotate',{method:'GET',headers:{'x-read-only':'true'}}));
}));
test('mutation nonce replay remains durable across local runtime restart and concurrent proof replay commits once',()=>use(async r=>{
 const body={name:'Fixture',phone:'',address:'',openMinute:0,closeMinute:1440,maintenance:false};
 const p=r.prepare('owner/public-config',{...owner(r),body});
 const results=await Promise.all([call(r,p.route,p.options),call(r,p.route,p.options)]);assert.equal(results.filter(x=>x.status===200).length,1);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces WHERE nonce=?').bind(p.options.headers['x-device-nonce']).first()).n,1);
 deny(await call(r,p.route,p.options));
 await r.close();r.close=async()=>{};const reopened=await runtime({directory:r.directory,bindings:r.bindings,initialise:false});
 try{deny(await call(reopened,p.route,p.options));}finally{await reopened.close();}
}));
test('revoked offline claim accepts owner evidence only and never completes, cancels or reassigns uncertain billing',()=>use(async r=>{
 const made=ok(await call(r,'orders',{body:draft()})),accepted=ok(await r.send(`orders/${made.id}/accept`,{body:{expectedRevision:1}}));
 const claimToken=token(),claim=ok(await r.send(`orders/${made.id}/billing-claim`,{body:{expectedRevision:accepted.revision,claimToken}}));
 ok(await r.send('auth/revoke-device',{...owner(r),body:{deviceId:r.worker.deviceId}}));
 const body={orderId:made.id,expectedRevision:claim.revision,billId:claim.billId,posBillSyncId:'synthetic-bill-sync-identity',evidence:'Owner compared original installation bill history and outbox',decision:'BILLED_EVIDENCE_SUBMITTED'};
 deny(await r.send('auth/reconcile-claim',{body}));
 deny(await r.send('auth/reconcile-claim',{...owner(r),body:{...body,billId:crypto.randomUUID()}}));
 const reviewed=ok(await r.send('auth/reconcile-claim',{...owner(r),body}));assert.equal(reviewed.status,'REVIEW_REQUIRED');
 assert.equal(ok(await r.send('auth/reconcile-claim',{...owner(r),body})).reviewId,reviewed.reviewId);
 deny(await r.send(`orders/${made.id}/billing-claim`,{...owner(r),body:{expectedRevision:claim.revision,claimToken:token()}}));
 deny(await r.send(`orders/${made.id}/cancel`,{...owner(r),body:{expectedRevision:claim.revision,reason:'try unlock'}}));
 const stored=await r.db.prepare('SELECT * FROM orders WHERE id=?').bind(made.id).first();assert.equal(stored.bill_id,claim.billId);assert.equal(stored.lock_device,r.worker.deviceId);assert.equal(stored.status,'BILLING_IN_PROGRESS');
}));
test('encrypted consistent security archive includes all tables; old restore quarantines all authority, retains nonce/audit and cannot revive old keys',()=>use(async r=>{
 const mutation=r.prepare('owner/public-config',{...owner(r),body:{name:'Synthetic',phone:'',address:'',openMinute:0,closeMinute:1440,maintenance:false}});ok(await call(r,mutation.route,mutation.options));
 const pending=r.enrollmentDevice();ok(await r.send('auth/enroll',{device:pending,body:{requestId:crypto.randomUUID(),publicKey:pending.publicJwk}}));
 await r.db.prepare('INSERT INTO auth_bootstrap(shop,uid,fingerprint,grant_hash,expires_at) VALUES(?,?,?,?,?)').bind('proof-shop','synthetic-owner',fingerprint(r.owner.publicJwk),token(),Math.floor(Date.now()/1000)+100).run();
 await r.db.prepare('INSERT INTO auth_claim_reviews VALUES(?,?,?,?,?,?,?,?,?,?,?)').bind(crypto.randomUUID(),'proof-shop',crypto.randomUUID(),crypto.randomUUID(),r.worker.deviceId,1,token(),JSON.stringify({posBillSyncId:'synthetic-original-bill',evidence:'Synthetic owner evidence retained'}),'synthetic-owner','UNRESOLVED',Date.now()).run();
 ok(await r.send('auth/bind-local-worker',{...owner(r),body:{deviceId:r.worker.deviceId,uid:'synthetic-worker',personId:'person-synthetic-worker',workerRef:'worker-synthetic-worker',databaseEpoch:crypto.randomUUID(),accountHash:token(),expectedMembershipVersion:1}}));
 const archive=await exportSecurity(r,{directory:path.join(r.directory,'security-exports'),passphrase:'Synthetic security archive passphrase'}),second=archive.file+'.copy';await copyFile(archive.file,second);
 assert.equal(archive.sourceWritten,0);assert.equal(Object.keys(archive.counts).length,10);assert.ok(Object.values(archive.counts).every(n=>n>0));
 ok(await r.send('auth/revoke-device',{...owner(r),body:{deviceId:r.worker.deviceId}}));
 ok(await r.send('auth/remove-worker',{...owner(r),body:{uid:'synthetic-worker',expectedVersion:1}}));
 await assert.rejects(restoreSecurity(archive,'bad password',{secondCopy:second,ownerConfirmed:true,bindings:r.bindings}));
 const restored=await restoreSecurity(archive,'Synthetic security archive passphrase',{secondCopy:second,ownerConfirmed:true,bindings:r.bindings});
 try{
  assert.equal(restored.report.authority,'QUARANTINED');assert.equal((await restored.db.prepare("SELECT COUNT(*) n FROM auth_devices WHERE status<>'REVOKED'").first()).n,0);
  const request=r.prepare('auth/session',{method:'GET'});deny(await call(restored,request.route,request.options));
  deny(await call(restored,mutation.route,mutation.options));
  assert.equal((await restored.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n,archive.counts.auth_nonces);
  assert.equal((await restored.db.prepare('SELECT COUNT(*) n FROM auth_audit').first()).n,archive.counts.auth_audit);
  assert.equal((await r.db.prepare("SELECT active FROM auth_memberships WHERE uid='synthetic-worker'").first()).active,0);
 }finally{await restored.close();}
 const corrupt=archive.file+'.corrupt';await writeFile(corrupt,Buffer.concat([await readFile(archive.file),Buffer.from('x')]));
 await assert.rejects(restoreSecurity({...archive,file:corrupt},'Synthetic security archive passphrase',{secondCopy:second,ownerConfirmed:true,bindings:r.bindings}));
}));
test('largest signed catalog publication remains within conservative 50-statement invocation boundary',()=>use(async r=>{
 const response=await r.send('catalog/publish-batch',{...owner(r),body:{products:Array.from({length:40},(_,i)=>({product:product(100+i),published:true}))}});ok(response);
 assert.ok(Number(response.headers.get('x-proof-db-queries'))<=50);assert.equal(Number(response.headers.get('x-proof-db-queries')),46);
}));
test('mutation nonce and business write share one guarded commit; rollback has no effects and successful proof cannot replay',()=>use(async r=>{
 const route='owner/public-config',body={name:'Atomic synthetic',phone:'',address:'',openMinute:0,closeMinute:1440,maintenance:false};
 const p=r.prepare(route,{...owner(r),body}),response=await call(r,p.route,p.options);ok(response);
 assert.equal(Number(response.headers.get('x-proof-db-rows-written')),7); // guard 2, nonce/indexes 3, business/PK index 2; previously 9.
 const indexedInsert=await r.db.prepare("INSERT INTO public_config(shop,doc) VALUES('index-cost-probe','{}')").run();assert.equal(indexedInsert.meta.rows_written,2);
 deny(await call(r,p.route,p.options));
 const failed=r.prepare('auth/remove-worker',{...owner(r),body:{uid:'synthetic-worker',expectedVersion:999}});deny(await call(r,failed.route,failed.options));
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces WHERE nonce=?').bind(failed.options.headers['x-device-nonce']).first()).n,1);
 await r.db.prepare("UPDATE auth_memberships SET version=999 WHERE shop='proof-shop' AND uid='synthetic-worker'").run();
 deny(await call(r,failed.route,failed.options));
 assert.equal((await r.db.prepare("SELECT active FROM auth_memberships WHERE shop='proof-shop' AND uid='synthetic-worker'").first()).active,1);
 ok(await r.send('auth/remove-worker',{...owner(r),body:{uid:'synthetic-worker',expectedVersion:999}}));
}));
test('idempotent POST without business writes still consumes a durable proof before returning its successful result',()=>use(async r=>{
 const d=r.enrollmentDevice(),body={requestId:crypto.randomUUID(),publicKey:d.publicJwk};ok(await r.send('auth/enroll',{device:d,body}));
 const p=r.prepare('auth/enroll',{device:d,body});ok(await call(r,p.route,p.options));deny(await call(r,p.route,p.options));
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces WHERE nonce=?').bind(p.options.headers['x-device-nonce']).first()).n,1);
}));
