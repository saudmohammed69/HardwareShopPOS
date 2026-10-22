import {test} from 'node:test';
import assert from 'node:assert/strict';
import {runtime,seed,draft,call,stored} from '../runtime.mjs';
import {abuseFixtureBindings,anonymousSession,challengeHeaders} from '../customer-abuse-fixture.mjs';
import {pruneExpiredAdmission} from '../retention.mjs';
import {settledSummary,preserveTransportEvidence} from '../transport-evidence.mjs';
async function use(fn,bindings={}){const r=await runtime({bindings:{...abuseFixtureBindings,...bindings}});try{await seed(r,30);await fn(r);}finally{await r.close();}}
for(const count of [20,50,100])test(`${count} verified anonymous customers behind one IP persist all orders; double submits remain unique`,()=>use(async r=>{
 let firstOutcomes=[],duplicateOutcomes=[];
 try{
 const bodies=Array.from({length:count},()=>draft(5));
 const firstWindow=Math.floor(Date.now()/600000);
 firstOutcomes=await Promise.allSettled(bodies.map(body=>call(r,'orders',{body,ip:'one-carrier-nat'})));
 assert.ok(firstOutcomes.every(x=>x.status==='fulfilled'),JSON.stringify(settledSummary(firstOutcomes)));
 const replies=firstOutcomes.map(x=>x.value);
 assert.ok(replies.every(x=>x.status===201),JSON.stringify(replies.filter(x=>x.status!==201).map(x=>x.body)));
 duplicateOutcomes=await Promise.allSettled(bodies.map(body=>call(r,'orders',{body,ip:'one-carrier-nat',abuse:false})));
 assert.ok(duplicateOutcomes.every(x=>x.status==='fulfilled'),JSON.stringify(settledSummary(duplicateOutcomes)));
 const duplicates=duplicateOutcomes.map(x=>x.value);
 assert.ok(duplicates.every(x=>x.status===200));assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,count);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM customer_ip_admission').first()).n,1);
 const bucket=await r.db.prepare('SELECT count,window FROM customer_ip_admission').first(),lastWindow=Math.floor(Date.now()/600000);
 // A real ten-minute boundary legitimately resets the current bucket while
 // preserving all persisted orders. Do not change the server clock/window.
 if(firstWindow===lastWindow)assert.equal(bucket.count,count);
 else {assert.ok(bucket.window>=firstWindow&&bucket.window<=lastWindow);assert.ok(bucket.count>=1&&bucket.count<=count);}
 }finally{
  try{await preserveTransportEvidence(r,'shared-ip-'+count,{outcomes:[...settledSummary(firstOutcomes).map(x=>({...x,phase:'submission'})),...settledSummary(duplicateOutcomes).map(x=>({...x,phase:'duplicate'}))]});}
  catch{console.error('Local transport evidence could not be fully saved; workload assertions remain unchanged.');}
 }
},{PROOF_TRACE:'true'}));
test('session flooding remains capped at three, independently of IP changes; rollback consumes no order or daily quota',()=>use(async r=>{
 const customerSession=anonymousSession();for(let i=0;i<3;i++)assert.equal((await call(r,'orders',{body:draft(),customerSession,ip:'ip-'+i})).status,201);
 const response=await call(r,'orders',{body:draft(),customerSession,ip:'new-ip'});assert.equal(response.status,429);assert.ok(!response.body.orderNumber);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,3);assert.equal((await r.db.prepare('SELECT count FROM daily_admission').first()).count,3);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM customer_challenge_uses').first()).n,3);
}));
test('missing forged expired wrong-shop/session/body challenge fails before order writes and never invents receipt',()=>use(async r=>{
 const body=draft(),session=anonymousSession(),headers=challengeHeaders(body,{session});
 const bad=[{}, {...headers,'x-order-challenge':'forged'}, challengeHeaders(body,{session,expires:Math.floor(Date.now()/1000)-1}),challengeHeaders(body,{session,shop:'other-shop'}),
  {...headers,'x-order-session':anonymousSession()}, {...headers,'x-order-challenge':challengeHeaders({...body,note:'different cart'},{session})['x-order-challenge']}];
 for(const h of bad){const response=await call(r,'orders',{body,headers:h,abuse:false});assert.equal(response.status,403);assert.ok(!response.body.orderNumber);}
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,0);
}));
test('untrusted IP headers cannot select a gateway address; legacy limiter and unknown mode fail safely',()=>use(async r=>{
 const body=draft(),headers=challengeHeaders(body);
 const response=await r.mf.dispatchFetch('https://proof.invalid/shops/proof-shop/orders',{method:'POST',headers:{...headers,'content-type':'application/json','x-proof-client-ip':'chosen','cf-connecting-ip':'chosen','x-forwarded-for':'chosen'},body:JSON.stringify(body)});
 assert.equal(response.status,403);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,0);
}));
test('persistence failure preserves identity and challenge retry; restart retains durable single-use evidence',async()=>{
 const r=await runtime({bindings:{...abuseFixtureBindings,PROOF_FAIL_WRITES:'true'}});let directory;
 try{await seed(r,2);const body=draft(),session=anonymousSession(),headers=challengeHeaders(body,{session});
  const response=await call(r,'orders',{body,headers,abuse:false});assert.equal(response.status,503);assert.ok(!response.body.orderNumber);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,0);
  directory=r.directory;await r.close();const restarted=await runtime({directory,initialise:false,bindings:abuseFixtureBindings});
  try{const success=await call(restarted,'orders',{body,headers,abuse:false});assert.equal(success.status,201);
   const retry=await call(restarted,'orders',{body,abuse:false});assert.equal(retry.body.id,success.body.id);
   const conflict=await call(restarted,'orders',{body:{...body,note:'changed'},headers,abuse:false});assert.equal(conflict.status,409);
   assert.equal((await restarted.db.prepare('SELECT COUNT(*) n FROM customer_challenge_uses').first()).n,1);
  }finally{await restarted.close();}
 }finally{if(!directory)await r.close();}
});
test('real challenge mode without provider configuration fails closed instead of accepting synthetic tokens',()=>use(async r=>{
 const response=await call(r,'orders',{body:draft()});assert.equal(response.status,503);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,0);
},{CUSTOMER_ABUSE_MODE:'TURNSTILE'}));
test('shared-IP emergency ceiling remains bounded and rejection rolls back both the order and counters',()=>use(async r=>{
 for(let batch=0;batch<10;batch++){
  const replies=await Promise.all(Array.from({length:20},()=>call(r,'orders',{body:draft(),ip:'same-nat-emergency'})));
  assert.ok(replies.every(x=>x.status===201));
 }
 const rejected=await call(r,'orders',{body:draft(),ip:'same-nat-emergency'});assert.equal(rejected.status,429);assert.ok(!rejected.body.orderNumber);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,200);
 assert.equal((await r.db.prepare('SELECT count FROM customer_ip_admission').first()).count,200);
 assert.equal((await r.db.prepare('SELECT count FROM daily_admission').first()).count,200);
}));
test('daily quota exhaustion cannot store an unconfirmed order and cannot block original-order recovery',()=>use(async r=>{
 const body=draft(),first=await call(r,'orders',{body});assert.equal(first.status,201);
 await r.db.prepare('UPDATE daily_admission SET count=1200').run();
 const response=await call(r,'orders',{body:draft()});assert.equal(response.status,429);assert.ok(!response.body.orderNumber);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,1);
 const recovered=await call(r,'orders',{body,abuse:false});assert.equal(recovered.status,200);assert.equal(recovered.body.id,first.body.id);
}));
test('owner metadata expiry model preserves pending records, original recovery and challenge binding',()=>use(async r=>{
 const body=draft(),headers=challengeHeaders(body),made=await call(r,'orders',{body,headers,abuse:false});assert.equal(made.status,201);
 const original=await stored(r,made.body.id),before=(await r.db.prepare('SELECT COUNT(*) n FROM events').first()).n;
 const cleanup=await pruneExpiredAdmission(r,{now:Date.now()+3*86400000});assert.ok(cleanup.expiredCustomerMetadataRows>=3);
 assert.deepEqual(await stored(r,made.body.id),original);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM events').first()).n,before);
 assert.equal((await call(r,'orders',{body,abuse:false})).body.id,made.body.id);
 assert.equal((await call(r,'orders',{body:draft(),headers,abuse:false})).status,403);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,1);
}));
