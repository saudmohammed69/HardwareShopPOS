import {test} from 'node:test';
import assert from 'node:assert/strict';
import {copyFile,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {signedFixture} from '../security-fixture.mjs';
import {call,draft,token} from '../runtime.mjs';
import {sealTenantSnapshot,restoreTenantArchive,validateTenantSnapshot} from '../tenant-archive-client.mjs';
import {authorityGuard} from '../security.mjs';
const ok=r=>{assert.ok(r.status<300,JSON.stringify(r.body));return r.body;};
const deny=r=>assert.ok([400,403,404,409,413,503].includes(r.status),JSON.stringify(r.body));
const owner=r=>({uid:'synthetic-owner',device:r.owner});
async function use(fn){const r=await signedFixture();try{await fn(r);}finally{await r.close();}}
const binding=r=>({deviceId:r.worker.deviceId,uid:'synthetic-worker',personId:'person-synthetic-worker',workerRef:'worker-synthetic-worker',databaseEpoch:crypto.randomUUID(),accountHash:token(),expectedMembershipVersion:1});
test('owner-approved installation binding rejects worker self-approval, impersonation, stale identity and account replacement',()=>use(async r=>{
 const b=binding(r);deny(await r.send('auth/bind-local-worker',{body:b}));
 deny(await r.send('auth/bind-local-worker',{...owner(r),body:{...b,uid:'synthetic-other'}}));
 deny(await r.send('auth/bind-local-worker',{...owner(r),body:{...b,personId:'person-synthetic-owner'}}));
 deny(await r.send('auth/bind-local-worker',{...owner(r),body:{...b,workerRef:'worker-synthetic-other'}}));
 deny(await r.send('auth/bind-local-worker',{...owner(r),body:{...b,expectedMembershipVersion:2}}));
 ok(await r.send('auth/bind-local-worker',{...owner(r),body:b}));
 ok(await r.send('auth/bind-local-worker',{...owner(r),body:b}));
 const s=ok(await r.send('auth/session',{method:'GET'}));assert.deepEqual(s.localBinding,{databaseEpoch:b.databaseEpoch,accountHash:b.accountHash});
 assert.equal(s.workerRef,b.workerRef);assert.equal(s.personId,b.personId);assert.equal(s.shop,'proof-shop');
 deny(await r.send('auth/bind-local-worker',{...owner(r),body:{...b,accountHash:token()}}));
 await r.db.prepare("UPDATE auth_memberships SET version=2 WHERE shop='proof-shop' AND uid='synthetic-worker'").run();
 assert.equal(ok(await r.send('auth/session',{method:'GET'})).localBinding,null);
 ok(await r.send('auth/revoke-device',{...owner(r),body:{deviceId:r.worker.deviceId}}));deny(await r.send('auth/session',{method:'GET'}));
}));
test('tenant archive requires recent signed owner and HTTPS, excludes every foreign-shop row and does not clean protected orders',()=>use(async r=>{
 const made=ok(await call(r,'orders',{body:draft()})),b=binding(r);ok(await r.send('auth/bind-local-worker',{...owner(r),body:b}));
 const body={orderIds:[made.id]};deny(await r.send('owner/archive-snapshot',{body}));
 deny(await r.send('owner/archive-snapshot',{...owner(r),jwt:r.token('synthetic-owner',{auth_time:Math.floor(Date.now()/1000)-301}),body}));
 deny(await r.send('owner/archive-snapshot',{uid:'synthetic-other',device:r.other,body}));
 const prepared=r.prepare('owner/archive-snapshot',{...owner(r),body});
 const http=await r.mf.dispatchFetch('http://proof.invalid/shops/proof-shop/owner/archive-snapshot',prepared.options);assert.equal(http.status,403);
 const snap=ok(await r.send('owner/archive-snapshot',{...owner(r),body}));validateTenantSnapshot(snap);
 assert.ok(!JSON.stringify(snap).includes('synthetic-other'));assert.equal(snap.orders[0].status,'NEW');assert.equal(snap.cleanupAuthorized,false);
 assert.equal((await r.db.prepare('SELECT doc FROM orders WHERE id=?').bind(made.id).first()).doc,snap.orders[0].doc);
 assert.ok(r.metrics.maxQueries<=50);
 const injected=structuredClone(snap);injected.rows.auth_devices.push({shop:'other-shop'});assert.throws(()=>validateTenantSnapshot(injected));
}));
test('tenant encrypted copies restore every order/event/security field into quarantine; corruption/wrong password fail closed',()=>use(async r=>{
 const made=ok(await call(r,'orders',{body:draft()}));ok(await r.send('auth/bind-local-worker',{...owner(r),body:binding(r)}));
 const mutation=r.prepare('owner/public-config',{...owner(r),body:{name:'Synthetic',phone:'',address:'',openMinute:0,closeMinute:1440,maintenance:false}});ok(await call(r,mutation.route,mutation.options));
 const snap=ok(await r.send('owner/archive-snapshot',{...owner(r),body:{orderIds:[made.id]}}));
 const archive=await sealTenantSnapshot(snap,{directory:path.join(r.directory,'tenant-exports'),passphrase:'Synthetic tenant private passphrase'}),second=archive.file+'.copy';await copyFile(archive.file,second);
 await assert.rejects(restoreTenantArchive(archive,'wrong passphrase',{secondCopy:second,ownerConfirmed:true}));
 await assert.rejects(restoreTenantArchive(archive,'Synthetic tenant private passphrase',{secondCopy:archive.file,ownerConfirmed:true}));
 ok(await r.send('auth/revoke-device',{...owner(r),body:{deviceId:r.worker.deviceId}}));
 const restored=await restoreTenantArchive(archive,'Synthetic tenant private passphrase',{secondCopy:second,ownerConfirmed:true});
 try{assert.equal(restored.report.counts.orders,1);assert.equal(restored.report.counts.auth_local_worker_bindings,1);assert.equal(restored.report.authority,'QUARANTINED');
  assert.equal((await restored.db.prepare("SELECT COUNT(*) n FROM auth_devices WHERE status<>'REVOKED'").first()).n,0);
  deny(await call(restored,mutation.route,mutation.options));
  assert.equal((await restored.db.prepare('SELECT status FROM orders WHERE id=?').bind(made.id).first()).status,'NEW');
 }finally{await restored.close();}
 const corrupt=archive.file+'.bad';await writeFile(corrupt,Buffer.concat([await readFile(archive.file),Buffer.from('x')]));await assert.rejects(restoreTenantArchive({...archive,file:corrupt},'Synthetic tenant private passphrase',{secondCopy:second,ownerConfirmed:true}));
}));
test('archive bounds fail closed without truncation, deletion or paid fallback',()=>use(async r=>{
 const rows=Array.from({length:201},()=>r.db.prepare("INSERT INTO auth_audit VALUES(?, 'proof-shop','synthetic-owner',?,'BOUND_TEST','fixture',?)").bind(crypto.randomUUID(),r.owner.deviceId,Date.now()));
 for(let i=0;i<rows.length;i+=40)await r.db.batch(rows.slice(i,i+40));
 const response=await r.send('owner/archive-snapshot',{...owner(r),body:{orderIds:[]}});assert.equal(response.status,409);
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM auth_audit WHERE action='BOUND_TEST'").first()).n,201);
}));
test('tenant replay export cannot leak another shop enrollment nonces when the same public key is reused',()=>use(async r=>{
 const device='enroll:'+token(),mine=crypto.randomUUID(),foreign=crypto.randomUUID();
 await r.db.batch([
  r.db.prepare('INSERT INTO auth_nonces(device,nonce,expires_at,shop) VALUES(?,?,?,?)').bind(device,mine,Math.floor(Date.now()/1000)+61,'proof-shop'),
  r.db.prepare('INSERT INTO auth_nonces(device,nonce,expires_at,shop) VALUES(?,?,?,?)').bind(device,foreign,Math.floor(Date.now()/1000)+61,'other-shop')
 ]);
 const snap=ok(await r.send('owner/archive-snapshot',{...owner(r),body:{orderIds:[]}}));validateTenantSnapshot(snap);
 assert.ok(snap.rows.auth_nonces.some(n=>n.nonce===mine));assert.ok(!snap.rows.auth_nonces.some(n=>n.nonce===foreign));
}));
test('private in-workerd meter includes a committed response lost before client quota headers',()=>use(async r=>{
 const before=await r.apiMeter(),b=draft();
 // Deliberately discard a real completed response before the normal client can
 // account for its headers. Recover by the unchanged business submission ID.
 const lost=await r.mf.dispatchFetch('https://proof.invalid/shops/proof-shop/orders',{method:'POST',headers:{'content-type':'application/json','x-proof-client-ip':b.submissionId},body:JSON.stringify(b)});
 assert.equal(lost.status,201);await lost.body.cancel();
 const recovered=ok(await call(r,'orders',{body:b})),meter=await r.apiMeter();
 assert.equal(meter.requests-before.requests,2);assert.equal(r.metrics.requests,1);
 assert.ok(meter.rowsWritten-before.rowsWritten>r.metrics.written);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,1);assert.ok(recovered.id);
 const untrusted=await r.mf.dispatchFetch('http://localhost/__local_fixture_meter',{method:'POST'});assert.equal(untrusted.status,403);
}));
test('person or stable worker reassignment after verification aborts writes atomically',()=>use(async r=>{
 const now=Math.floor(Date.now()/1000),a={uid:'synthetic-worker',shop:'proof-shop',role:'WORKER',membershipVersion:1,expires:now+100,authenticated:now,device:r.worker.deviceId,deviceVersion:1,keyVersion:1,personId:'person-synthetic-worker',workerRef:'worker-synthetic-worker'};
 const db={statement:(sql,args=[])=>r.db.prepare(sql).bind(...args)},attempt=()=>r.db.batch([authorityGuard(db,a),r.db.prepare("INSERT INTO public_config(shop,doc) VALUES('proof-shop','{}')"),r.db.prepare('DELETE FROM auth_guard')]);
 await r.db.prepare("UPDATE auth_identity_bindings SET person_id='different-person' WHERE uid='synthetic-worker'").run();await assert.rejects(attempt());
 await r.db.prepare("UPDATE auth_identity_bindings SET person_id='person-synthetic-worker' WHERE uid='synthetic-worker'").run();
 await r.db.prepare("UPDATE auth_worker_links SET worker_ref='different-worker' WHERE shop='proof-shop' AND uid='synthetic-worker'").run();await assert.rejects(attempt());
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM public_config').first()).n,0);
}));
test('revoked claim evidence retry after restart never unlocks or permits a replacement bill',()=>use(async r=>{
 const made=ok(await call(r,'orders',{body:draft()})),accepted=ok(await r.send(`orders/${made.id}/accept`,{body:{expectedRevision:1}}));
 const claim=ok(await r.send(`orders/${made.id}/billing-claim`,{body:{expectedRevision:accepted.revision,claimToken:token()}}));
 ok(await r.send('auth/revoke-device',{...owner(r),body:{deviceId:r.worker.deviceId}}));
 const body={orderId:made.id,expectedRevision:claim.revision,billId:claim.billId,posBillSyncId:'synthetic-bill-sync-review',evidence:'Original device and original bill history/outbox require owner review',decision:'UNRESOLVED'};
 const first=ok(await r.send('auth/reconcile-claim',{...owner(r),body}));
 const {runtime}=await import('../runtime.mjs');await r.close();r.close=async()=>{};const reopened=await runtime({directory:r.directory,bindings:r.bindings,initialise:false});
 try{const p=r.prepare('auth/reconcile-claim',{...owner(r),body});const repeat=ok(await call(reopened,p.route,p.options));assert.equal(repeat.reviewId,first.reviewId);assert.equal(repeat.status,'REVIEW_REQUIRED');
  const replacement=r.prepare(`orders/${made.id}/billing-claim`,{...owner(r),body:{expectedRevision:claim.revision,claimToken:token()}});deny(await call(reopened,replacement.route,replacement.options));
  const row=await reopened.db.prepare('SELECT status,bill_id,lock_device FROM orders WHERE id=?').bind(made.id).first();assert.deepEqual(row,{status:'BILLING_IN_PROGRESS',bill_id:claim.billId,lock_device:r.worker.deviceId});
 }finally{await reopened.close();}
}));
