import {test} from 'node:test';
import assert from 'node:assert/strict';
import {pairedFixture,installation,preparePaired} from '../pairing-fixture.mjs';
import {runtime,call,draft,token} from '../runtime.mjs';
import {abuseFixtureBindings} from '../customer-abuse-fixture.mjs';
import {copyFile} from 'node:fs/promises';
import {exportPairing,restorePairing} from '../pairing-archive.mjs';
import path from 'node:path';
import {recoveryClient} from '../paired-recovery.mjs';
import {submitRecoverably,editRecoverably} from '../customer-recovery.mjs';
async function use(fn){const r=await pairedFixture({bindings:abuseFixtureBindings});try{await fn(r);}finally{await r.close();}}
test('customer lost duplicate/submission response and committed edit recover through original identity and stored revision',()=>use(async r=>{
 const metrics={transportFailures:0,recoveryAttempts:0,unrecoverable:0,transportEvents:[]},body=draft(2),original=r.mf.dispatchFetch;
 let dropSubmit=true,dropEdit=false;
 r.mf.dispatchFetch=async(input,options)=>{const response=await original(input,options),pathname=new URL(input).pathname;
  if((dropSubmit&&pathname.endsWith('/orders'))||(dropEdit&&pathname.endsWith('/edit'))){dropSubmit=false;dropEdit=false;await response.arrayBuffer();throw Object.assign(new Error('Injected lost customer response after commit'),{cause:{code:'UND_ERR_SOCKET'}});}return response;};
 try{
  const made=await submitRecoverably(r,body,{},metrics);assert.equal(made.status,200);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,1);
  // Exact duplicate also uses the immutable submission body.
  dropSubmit=true;assert.equal((await submitRecoverably(r,body,{abuse:false},metrics)).body.id,made.body.id);
  const change={customer:body.customer,items:body.items,note:'Recovered customer edit'};dropEdit=true;
  const edited=await editRecoverably(r,made.body.id,1,change,body.capability,metrics);assert.equal(edited.status,200);assert.equal(edited.body.revision,2);
  const row=await r.db.prepare('SELECT revision,doc FROM orders WHERE id=?').bind(made.body.id).first();assert.equal(row.revision,2);assert.equal(JSON.parse(row.doc).note,change.note);
  assert.equal(metrics.recoveryAttempts,3);assert.equal(metrics.unrecoverable,0);
 }finally{r.mf.dispatchFetch=original;}
}));
test('out-of-band first owner and key-bound one-use shop approval need no Firebase or worker cloud login',()=>use(async r=>{
 assert.equal((await r.send('pair/session',{method:'GET'})).body.role,'OWNER');
 assert.equal((await r.send('pair/session',{device:r.worker,method:'GET'})).body.role,'WORKER');
 const unknown=installation();assert.equal((await r.send('pair/approve',{device:unknown,body:{}})).status,403);
 assert.equal((await r.send('pair/approve',{device:r.worker,body:{}})).status,403);
 const other=installation(),approved=await r.send('pair/approve',{body:{deviceId:other.deviceId,databaseEpoch:other.databaseEpoch,publicKey:other.publicJwk,permissions:['ORDERS_READ']}});
 const body={ticket:approved.body.ticket,publicKey:other.publicJwk};
 assert.equal((await r.send('pair/redeem',{device:other,body})).status,200);
 assert.equal((await r.send('pair/redeem',{device:other,body})).status,403);
 assert.equal((await r.send('catalog/publish-batch',{device:other,body:{products:[]}})).status,403);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_users').first()).n,0);
}));
test('approval rejects forged key shop epoch permissions expiry and shared credentials',()=>use(async r=>{
 const d=installation(),a=await r.send('pair/approve',{body:{deviceId:d.deviceId,databaseEpoch:d.databaseEpoch,publicKey:d.publicJwk,permissions:['ORDERS_READ']}}),ticket=a.body.ticket;
 for(const changed of [{...ticket,shop:'foreign'},{...ticket,epoch:2},{...ticket,permissions:['DEVICE_ADMIN']},{...ticket,expires:ticket.expires+1}])assert.equal((await r.send('pair/redeem',{device:d,body:{ticket:changed,publicKey:d.publicJwk}})).status,403);
 assert.equal((await r.send('pair/redeem',{device:installation(),body:{ticket,publicKey:d.publicJwk}})).status,403);
 const signed=r.prepare('pair/session',{method:'GET',headers:{authorization:'Bearer shared-firebase-uid'}});assert.equal((await call(r,signed.route,signed.options)).status,403);
 await r.db.prepare('UPDATE pair_grants SET expires_at=0 WHERE id=?').bind(ticket.id).run();
 assert.equal((await r.send('pair/redeem',{device:d,body:{ticket,publicKey:d.publicJwk}})).status,403);
}));
test('server revocation and immutable one-shop key binding survive restart and restored authority is quarantined',()=>use(async r=>{
 const p=r.prepare('pair/session',{device:r.worker,method:'GET'});assert.equal((await call(r,p.route,p.options)).status,200);
 assert.equal((await r.send('pair/revoke',{body:{deviceId:r.worker.deviceId}})).status,200);
 assert.equal((await call(r,p.route,p.options)).status,403);
 const substitute={...installation(),deviceId:r.owner.deviceId,databaseEpoch:r.owner.databaseEpoch};
 assert.equal((await r.send('pair/session',{device:substitute,method:'GET'})).status,403);
 // call() targets the configured shop, so a proof signed for another URL
 // must fail signature verification, rather than reaching another shop route.
 assert.equal((await r.send('pair/session',{method:'GET',shop:'foreign-shop'})).status,403);
 const restored=await runtime({directory:r.directory,initialise:false,bindings:{...r.bindings,PAIR_QUARANTINED:'true'},pooledTransport:true});
 try{const q=preparePaired(r.owner,'pair/session',{method:'GET'});assert.equal((await call(restored,q.route,q.options)).status,503);}finally{await restored.close();}
}));
test('mutation proof replay is rejected and durable same-operation receipt recovers lost claim/complete response once',()=>use(async r=>{
 const made=await call(r,'orders',{body:draft(5)});assert.equal(made.status,201);const id=made.body.id;
 const accept=r.prepare(`orders/${id}/accept`,{device:r.worker,body:{expectedRevision:1}});
 const accepted=await call(r,accept.route,accept.options);assert.equal(accepted.status,200);
 assert.equal((await call(r,accept.route,accept.options)).status,403);
 const operationId=crypto.randomUUID(),claimToken=token(),body={expectedRevision:accepted.body.revision,claimToken};
 const claim=await r.send(`orders/${id}/billing-claim`,{device:r.worker,operationId,body});assert.equal(claim.status,200);
 const recovered=await r.send(`orders/${id}/billing-claim`,{device:r.worker,operationId,body});assert.deepEqual(recovered.body,claim.body);
 assert.equal((await r.send(`orders/${id}/billing-claim`,{device:r.worker,operationId,body:{...body,claimToken:token()}})).status,409);
 const completionId=crypto.randomUUID(),complete={expectedRevision:claim.body.revision,claimToken,billId:claim.body.billId,posBillSyncId:'synthetic-existing-pos-sync'};
 const completed=await r.send(`orders/${id}/complete`,{device:r.worker,operationId:completionId,body:complete});assert.equal(completed.status,200);
 assert.deepEqual((await r.send(`orders/${id}/complete`,{device:r.worker,operationId:completionId,body:complete})).body,completed.body);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders WHERE bill_id=?').bind(claim.body.billId).first()).n,1);
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM events WHERE order_id=? AND kind='COMPLETED'").bind(id).first()).n,1);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM pair_operations WHERE order_id=?').bind(id).first()).n,3);
}));
test('encrypted pairing archive restores every table into quarantine and cannot resurrect a revoked installation',()=>use(async r=>{
 await r.send('pair/revoke',{body:{deviceId:r.worker.deviceId}});
 const pass='Phase4G test archive synthetic only',a=await exportPairing(r,{directory:path.join(r.directory,'exports'),passphrase:pass}),copy=a.file+'.copy';await copyFile(a.file,copy);
 const restored=await restorePairing(a,pass,{secondCopy:copy,ownerConfirmed:true,bindings:r.bindings});
 try{assert.equal(restored.report.authority,'QUARANTINED');const p=preparePaired(r.owner,'pair/session',{method:'GET'});assert.equal((await call(restored,p.route,p.options)).status,503);
 assert.equal((await restored.db.prepare("SELECT COUNT(*) n FROM pair_devices WHERE status='ACTIVE'").first()).n,0);
 }finally{await restored.close();}
 await assert.rejects(restorePairing(a,'wrong synthetic password',{secondCopy:copy,ownerConfirmed:true,bindings:r.bindings}));
}));
test('two paired installations race for one bill and revocation never transfers the sticky winning claim',()=>use(async r=>{
 const second=installation();await r.pair(second);
 const made=await call(r,'orders',{body:draft(1)});assert.equal(made.status,201);const id=made.body.id;
 const accepted=await r.send(`orders/${id}/accept`,{device:r.worker,body:{expectedRevision:1}});assert.equal(accepted.status,200);
 const devices=[r.worker,second],operations=devices.map(()=>crypto.randomUUID()),bodies=devices.map(()=>({expectedRevision:accepted.body.revision,claimToken:token()}));
 const claims=await Promise.all(devices.map((device,i)=>r.send(`orders/${id}/billing-claim`,{device,operationId:operations[i],body:bodies[i]})));
 assert.deepEqual(claims.map(x=>x.status).sort(),[200,409]);const winner=claims.findIndex(x=>x.status===200),loser=1-winner;
 const before=await r.db.prepare('SELECT status,revision,lock_device,lock_hash,bill_id FROM orders WHERE id=?').bind(id).first();
 assert.equal(before.lock_device,devices[winner].deviceId);assert.equal(before.bill_id,claims[winner].body.billId);
 assert.equal((await r.send('pair/revoke',{body:{deviceId:devices[winner].deviceId}})).status,200);
 assert.equal((await r.send(`orders/${id}/billing-claim`,{device:devices[winner],operationId:operations[winner],body:bodies[winner]})).status,403);
 assert.equal((await r.send(`orders/${id}/billing-claim`,{device:devices[loser],body:{expectedRevision:before.revision,claimToken:token()}})).status,409);
 assert.deepEqual(await r.db.prepare('SELECT status,revision,lock_device,lock_hash,bill_id FROM orders WHERE id=?').bind(id).first(),before);
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM events WHERE order_id=? AND kind='BILLING_CLAIMED'").bind(id).first()).n,1);
}));
test('revoked owner approval and stale administrator trust cannot authorize pairing',()=>use(async r=>{
 const d=installation(),approval=await r.send('pair/approve',{body:{deviceId:d.deviceId,databaseEpoch:d.databaseEpoch,publicKey:d.publicJwk,permissions:['ORDERS_READ']}});
 await r.db.prepare("UPDATE pair_devices SET status='REVOKED' WHERE id=?").bind(r.owner.deviceId).run();
 assert.equal((await r.send('pair/redeem',{device:d,body:{ticket:approval.body.ticket,publicKey:d.publicJwk}})).status,403);
 const stale=await runtime({directory:r.directory,initialise:false,bindings:{...r.bindings,PAIR_ADMIN_FRESH_UNTIL:'1'}});
 try{const p=preparePaired(r.worker,'pair/session',{method:'GET'});assert.equal((await call(stale,p.route,p.options)).status,403);}finally{await stale.close();}
}));
test('actual lost claim response recovers with fresh proof and the same atomic operation identity',()=>use(async r=>{
 const made=await call(r,'orders',{body:draft()}),id=made.body.id;assert.equal(made.status,201);
 const client=recoveryClient(r);let revision=(await client.send(r.worker,`orders/${id}/accept`,{body:{expectedRevision:1}})).body.revision;
 const original=r.mf.dispatchFetch.bind(r.mf);let dropped=false;
 r.mf.dispatchFetch=async(input,options)=>{const response=await original(input,options);if(String(input).endsWith('/billing-claim')&&!dropped){dropped=true;await response.arrayBuffer();throw Object.assign(Error('Synthetic lost response AFTER commit'),{code:'ECONNRESET'});}return response;};
 const recovered=await client.send(r.worker,`orders/${id}/billing-claim`,{operationId:crypto.randomUUID(),body:{expectedRevision:revision,claimToken:token()}});
 assert.equal(recovered.status,200);assert.equal(client.metrics.recoveryAttempts,1);
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM events WHERE order_id=? AND kind='BILLING_CLAIMED'").bind(id).first()).n,1);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM pair_operations WHERE order_id=?').bind(id).first()).n,2);
}));
