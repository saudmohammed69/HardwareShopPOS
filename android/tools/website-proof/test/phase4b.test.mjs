import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sign,createHash} from 'node:crypto';
import {signedFixture,newDevice,fingerprint} from '../security-fixture.mjs';
import {draft,call,token,runtime} from '../runtime.mjs';
import {authorityGuard} from '../security.mjs';
const ok=r=>{assert.ok(r.status<300,JSON.stringify(r.body));return r.body;};
const deny=r=>assert.ok([400,403,404,409,503].includes(r.status),'Expected fail-closed response');
async function use(fn,options){const r=await signedFixture(options);try{await fn(r);}finally{await r.close();}}
const ownerOptions=r=>({uid:'synthetic-owner',device:r.owner});
const settings={name:'Synthetic secure shop',phone:'9876543210',address:'Synthetic street',openMinute:0,closeMinute:1440,maintenance:false};

test('signed authentication verifies issuer, audience, RSA signature, algorithm, kid, temporal claims and revocation freshness',()=>use(async r=>{
 ok(await r.send('auth/session',{method:'GET'}));
 for(const claims of [{iss:'https://evil.invalid'},{aud:'other-project'},{exp:1},{iat:Math.floor(Date.now()/1000)+20},{auth_time:Math.floor(Date.now()/1000)+20},{sub:''}])
  deny(await r.send('auth/session',{method:'GET',jwt:r.token('synthetic-worker',claims)}));
 for(const header of [{alg:'none'},{alg:'HS256'},{kid:'untrusted-key'},{jku:'https://evil.invalid/keys'}])deny(await r.send('auth/session',{method:'GET',jwt:r.token('synthetic-worker',{},header)}));
 const forged=r.token().split('.');forged[1]=Buffer.from(JSON.stringify({sub:'synthetic-owner'})).toString('base64url');deny(await r.send('auth/session',{method:'GET',jwt:forged.join('.')}));
 await r.db.prepare('UPDATE auth_users SET checked_until=1 WHERE uid=?').bind('synthetic-worker').run();deny(await r.send('auth/session',{method:'GET'}));
}));

test('trusted-key expiry and missing security adapter fail closed with no fixture-token fallback',async()=>{
 await use(async r=>{assert.equal((await r.send('auth/session',{method:'GET'})).status,503);deny(await call(r,'pos/events',{method:'GET',auth:'fixture-owner'}));},{bindings:{AUTH_KEYS_EXPIRES_AT:'1'}});
 const r=await runtime({bindings:{AUTH_MODE:'missing'}});try{assert.equal((await call(r,'pos/events',{method:'GET',auth:'fixture-owner'})).status,503);}finally{await r.close();}
});

test('signed proof hashing rejects oversized bodies before nonce writes even without a content length',()=>use(async r=>{
 const before=(await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n;
 const response=await r.send('auth/rotate',{raw:'x'.repeat(32769)});
 assert.ok([403,413].includes(response.status));
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n,before);
}));

test('membership is independent of signed client role/shop/device claims and blocks cross-shop identities',()=>use(async r=>{
 const escalation=r.token('synthetic-worker',{role:'OWNER',shop:'proof-shop',device:r.owner.deviceId});
 const session=ok(await r.send('auth/session',{method:'GET',jwt:escalation}));assert.equal(session.role,'WORKER');
 deny(await r.send('owner/public-config',{jwt:escalation,body:settings}));
 deny(await r.send('catalog/publish-batch',{jwt:escalation,body:{products:[]}}));
 deny(await r.send('pos/events',{method:'GET',uid:'synthetic-other',device:r.other}));
 ok(await r.send('owner/public-config',{...ownerOptions(r),body:settings}));
}));

test('device impersonation, body/route/token tampering, stale clocks and replayed request proof are rejected',()=>use(async r=>{
 deny(await r.send('auth/session',{method:'GET',device:{...r.worker,privateKey:r.owner.privateKey}}));
 deny(await r.send('auth/session',{method:'GET',timestamp:Math.floor(Date.now()/1000)-61}));
 const original=r.prepare('owner/public-config',{...ownerOptions(r),body:settings});ok(await call(r,original.route,original.options));deny(await call(r,original.route,original.options));
 const changed=r.prepare('pos/events?cursor=0',{method:'GET'});deny(await call(r,'pos/events?cursor=1',changed.options));
 const c=ok(await call(r,'orders',{body:draft()}));const request=r.prepare(`orders/${c.id}/accept`,{body:{expectedRevision:1}});
 deny(await call(r,request.route,{...request.options,raw:JSON.stringify({expectedRevision:2})}));
 const swapped=r.prepare('auth/session',{method:'GET'});swapped.options.headers.authorization='Bearer '+r.token('synthetic-owner');deny(await call(r,swapped.route,swapped.options));
}));

test('owner-approved enrollment is pending, installation-key-bound, idempotently recoverable and cannot self-approve',()=>use(async r=>{
 const device=r.enrollmentDevice(),body={requestId:crypto.randomUUID(),publicKey:device.publicJwk};
 const enrolled=ok(await r.send('auth/enroll',{device,body}));assert.equal(enrolled.status,'PENDING');
 const retry=ok(await r.send('auth/enroll',{device,body}));assert.equal(retry.deviceId,enrolled.deviceId);
 const registered={...device,deviceId:enrolled.deviceId};deny(await r.send('auth/session',{method:'GET',device:registered}));
 deny(await r.send('auth/approve',{body:{deviceId:enrolled.deviceId,expectedVersion:enrolled.version}}));
 const approved=ok(await r.send('auth/approve',{...ownerOptions(r),body:{deviceId:enrolled.deviceId,expectedVersion:enrolled.version}}));assert.equal(approved.status,'ACTIVE');
 assert.equal(ok(await r.send('auth/session',{method:'GET',device:registered})).deviceId,enrolled.deviceId);
 // Same request with changed key cannot recover another installation's enrollment.
 const different=r.enrollmentDevice();deny(await r.send('auth/enroll',{device:different,body:{...body,publicKey:different.publicJwk}}));
}));

test('expired enrollment and stale owner authentication cannot grant authority; reinstall needs new approval',()=>use(async r=>{
 const device=r.enrollmentDevice(),body={requestId:crypto.randomUUID(),publicKey:device.publicJwk},e=ok(await r.send('auth/enroll',{device,body}));
 await r.db.prepare('UPDATE auth_devices SET expires_at=1 WHERE id=?').bind(e.deviceId).run();
 deny(await r.send('auth/approve',{...ownerOptions(r),body:{deviceId:e.deviceId,expectedVersion:1}}));
 const stale=r.token('synthetic-owner',{auth_time:Math.floor(Date.now()/1000)-301});deny(await r.send('auth/revoke-device',{...ownerOptions(r),jwt:stale,body:{deviceId:r.worker.deviceId}}));
 ok(await r.send('auth/revoke-device',{...ownerOptions(r),body:{deviceId:r.worker.deviceId}}));deny(await r.send('auth/session',{method:'GET'}));
 const reinstalled=r.enrollmentDevice(),next=ok(await r.send('auth/enroll',{device:reinstalled,body:{requestId:crypto.randomUUID(),publicKey:reinstalled.publicJwk}}));assert.equal(next.status,'PENDING');assert.notEqual(next.deviceId,r.worker.deviceId);
 deny(await r.send('auth/session',{method:'GET',device:{...reinstalled,deviceId:r.worker.deviceId}}));
}));

test('device key rotation needs both old and new private keys and never revives revoked installations',()=>use(async r=>{
 const next=newDevice(),nonce=crypto.randomUUID(),fp=fingerprint(next.publicJwk),message=['HWPOS-ROTATE-V1',r.worker.deviceId,'2',fp,nonce].join('\n');
 const body={publicKey:next.publicJwk,rotationSignature:sign('sha256',Buffer.from(message),{key:next.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url')};
 deny(await r.send('auth/rotate',{body:{...body,rotationSignature:'AAAA'}}));
 const rotation=ok(await r.send('auth/rotate',{nonce,body}));assert.equal(rotation.keyVersion,2);
 deny(await r.send('auth/session',{method:'GET'}));
 // After a lost rotation response, the durably staged new key can reconcile
 // through session verification; retrying with the old key cannot rotate again.
 const current={...next,deviceId:r.worker.deviceId,version:2};assert.equal(ok(await r.send('auth/session',{method:'GET',device:current})).keyVersion,2);
 ok(await r.send('auth/revoke-device',{...ownerOptions(r),body:{deviceId:current.deviceId}}));deny(await r.send('auth/session',{method:'GET',device:current}));
}));

test('shop-scoped session revocation cannot revoke another shop membership of the same Firebase UID',()=>use(async r=>{
 await r.db.prepare("INSERT INTO auth_memberships(shop,uid,role,active) VALUES('other-shop','synthetic-worker','WORKER',1)").run();
 const jwt=r.token();ok(await r.send('auth/revoke-tokens',{...ownerOptions(r),body:{uid:'synthetic-worker'}}));
 deny(await r.send('auth/session',{method:'GET',jwt}));
 assert.equal((await r.db.prepare("SELECT valid_after FROM auth_memberships WHERE shop='other-shop' AND uid='synthetic-worker'").first()).valid_after,0);
 assert.equal((await r.db.prepare("SELECT valid_after FROM auth_users WHERE uid='synthetic-worker'").first()).valid_after,0);
}));

test('member removal invalidates all installations and token revocation blocks old sessions',()=>use(async r=>{
 const oldToken=r.token();ok(await r.send('auth/revoke-tokens',{...ownerOptions(r),body:{uid:'synthetic-worker'}}));
 deny(await r.send('auth/session',{method:'GET',jwt:oldToken}));
 await r.db.prepare('UPDATE auth_memberships SET valid_after=0 WHERE uid=?').bind('synthetic-worker').run();
 const second=await r.bootstrap('synthetic-worker');
 ok(await r.send('auth/remove-worker',{...ownerOptions(r),body:{uid:'synthetic-worker',expectedVersion:2}}));
 for(const device of [r.worker,second])deny(await r.send('auth/session',{method:'GET',device}));
 deny(await r.send('auth/enroll',{device:r.enrollmentDevice(),body:{requestId:crypto.randomUUID(),publicKey:newDevice().publicJwk}}));
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM auth_devices WHERE uid=? AND status<>'REVOKED'").bind('synthetic-worker').first()).n,0);
}));

test('stale membership-removal CAS cannot revoke devices or append a misleading security audit',()=>use(async r=>{
 const before=(await r.db.prepare('SELECT COUNT(*) n FROM auth_audit').first()).n;
 deny(await r.send('auth/remove-worker',{...ownerOptions(r),body:{uid:'synthetic-worker',expectedVersion:999}}));
 ok(await r.send('auth/session',{method:'GET'}));assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM auth_audit').first()).n,before);
}));

test('revocation after initial verification aborts protected D1 writes inside the atomic authorization guard',()=>use(async r=>{
 const now=Math.floor(Date.now()/1000),a={uid:'synthetic-worker',shop:'proof-shop',role:'WORKER',membershipVersion:1,expires:now+100,authenticated:now,
  device:r.worker.deviceId,deviceVersion:1,keyVersion:1,personId:'person-synthetic-worker',workerRef:'worker-synthetic-worker'};
 const db={statement:(sql,args=[])=>r.db.prepare(sql).bind(...args)};
 await r.db.prepare("UPDATE auth_devices SET status='REVOKED',version=version+1 WHERE id=?").bind(a.device).run();
 await assert.rejects(r.db.batch([authorityGuard(db,a),r.db.prepare("INSERT INTO public_config(shop,doc) VALUES('proof-shop','{}')"),r.db.prepare('DELETE FROM auth_guard')]));
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM public_config').first()).n,0);
}));

test('concurrent signed devices have one sticky bill identity and revoked active-order devices cannot complete or steal claims',()=>use(async r=>{
 const body=draft(),made=ok(await call(r,'orders',{body}));let revision=ok(await r.send(`orders/${made.id}/accept`,{body:{expectedRevision:1}})).revision;
 const second=await r.bootstrap('synthetic-worker'),claimA=token(),claimB=token();
 const claims=await Promise.all([r.send(`orders/${made.id}/billing-claim`,{body:{expectedRevision:revision,claimToken:claimA}}),r.send(`orders/${made.id}/billing-claim`,{device:second,body:{expectedRevision:revision,claimToken:claimB}})]);
 assert.equal(claims.filter(x=>x.status===200).length,1);const index=claims.findIndex(x=>x.status===200),claim=claims[index].body,device=index===0?r.worker:second,claimToken=index===0?claimA:claimB;
 const repeat=ok(await r.send(`orders/${made.id}/billing-claim`,{device,body:{expectedRevision:revision,claimToken}}));assert.equal(repeat.billId,claim.billId);
 const other=index===0?second:r.worker;deny(await r.send(`orders/${made.id}/billing-claim`,{device:other,body:{expectedRevision:claim.revision,claimToken}}));
 ok(await r.send('auth/revoke-device',{...ownerOptions(r),body:{deviceId:device.deviceId}}));
 deny(await r.send(`orders/${made.id}/complete`,{device,body:{expectedRevision:claim.revision,claimToken,billId:claim.billId}}));
 const row=await r.db.prepare('SELECT status,bill_id FROM orders WHERE id=?').bind(made.id).first();assert.equal(row.status,'BILLING_IN_PROGRESS');assert.equal(row.bill_id,claim.billId);
}));

test('lost completion response recovers exactly one final server identity with fresh signed retries and sync cursors',()=>use(async r=>{
 const made=ok(await call(r,'orders',{body:draft()}));const accepted=ok(await r.send(`orders/${made.id}/accept`,{body:{expectedRevision:1}}));
 const claimToken=token(),claim=ok(await r.send(`orders/${made.id}/billing-claim`,{body:{expectedRevision:accepted.revision,claimToken}}));
 const body={expectedRevision:claim.revision,claimToken,billId:claim.billId,posBillSyncId:'synthetic-final-pos-bill-id'};
 await r.send(`orders/${made.id}/complete`,{body}); // deliberately discard response
 const result=ok(await r.send(`orders/${made.id}/complete`,{body}));assert.equal(result.billId,claim.billId);
 const feed=ok(await r.send('pos/events?cursor=0',{method:'GET'}));assert.equal(feed.events.at(-1).status,'COMPLETED');
 const cursor=feed.cursor;assert.equal(ok(await r.send(`pos/events?cursor=${cursor}`,{method:'GET'})).events.length,0);
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM events WHERE order_id=? AND kind='COMPLETED'").bind(made.id).first()).n,1);
}));

test('customer capabilities cannot cross orders, become staff credentials or edit accepted orders; no token/key leakage',()=>use(async r=>{
 const a=draft(),b=draft(),one=ok(await call(r,'orders',{body:a})),two=ok(await call(r,'orders',{body:b}));
 deny(await call(r,`orders/${two.id}/customer-view`,{body:{},capability:a.capability}));
 deny(await call(r,'pos/events',{method:'GET',auth:a.capability}));
 ok(await call(r,`orders/${one.id}/customer-view`,{body:{},capability:a.capability}));
 ok(await r.send(`orders/${one.id}/accept`,{body:{expectedRevision:1}}));deny(await call(r,`orders/${one.id}/customer-cancel`,{body:{expectedRevision:2},capability:a.capability}));
 const jwt=r.token(),response=await r.send('auth/session',{method:'GET',jwt});
 const encoded=JSON.stringify(response.body);for(const secret of [jwt,a.capability,'PRIVATE KEY','public_key','lock_hash','capability_hash'])assert.ok(!encoded.includes(secret));
 const audit=JSON.stringify((await r.db.prepare('SELECT * FROM auth_audit').all()).results);assert.ok(!audit.includes(jwt));
}));
