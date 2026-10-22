import {test} from 'node:test';
import assert from 'node:assert/strict';
import {copyFile} from 'node:fs/promises';
import path from 'node:path';
import {runtime,seed,draft,call,token,stored} from '../runtime.mjs';
import {exportOrders,compactVerified} from '../retention.mjs';

test('rate limiter preserves the three-per-window limit and reuses one bucket across expired windows',async()=>{
 const r=await runtime();try{
  await seed(r,2);const ip='synthetic-repeat-customer';
  for(let i=0;i<3;i++)assert.equal((await call(r,'orders',{body:draft(),ip})).status,201);
  assert.equal((await call(r,'orders',{body:draft(),ip})).status,429);
  let counters=await r.db.prepare('SELECT COUNT(*) n,MAX(count) maximum FROM admission WHERE shop=?').bind('proof-shop').first();
  assert.equal(counters.n,1);assert.equal(counters.maximum,3);

  const current=Math.floor(Date.now()/600000);
  await r.db.prepare('UPDATE admission SET window=? WHERE shop=?').bind(current-1,'proof-shop').run();
  assert.equal((await call(r,'orders',{body:draft(),ip})).status,201);
  counters=await r.db.prepare('SELECT COUNT(*) n,MAX(count) maximum,MAX(window) window FROM admission WHERE shop=?').bind('proof-shop').first();
  assert.equal(counters.n,1);assert.equal(counters.maximum,1);assert.equal(counters.window,current);
 }finally{await r.close();}
});

test('verified owner archive cleanup prunes only expired admission metadata and preserves order, bill, and current limits',async()=>{
 const r=await runtime();try{
  await seed(r,2);const submission=draft(),made=await call(r,'orders',{body:submission});assert.equal(made.status,201);
  const id=made.body.id,accepted=await call(r,`orders/${id}/accept`,{auth:'fixture-worker-a',body:{expectedRevision:1}});
  const claimToken=token(),claim=await call(r,`orders/${id}/billing-claim`,{auth:'fixture-worker-a',body:{expectedRevision:accepted.body.revision,claimToken}});
  await call(r,`orders/${id}/complete`,{auth:'fixture-worker-a',body:{expectedRevision:claim.body.revision,claimToken,billId:claim.body.billId}});
  const currentWindow=Math.floor(Date.now()/600000),currentDay=Math.floor(Date.now()/86400000);
  await r.db.prepare('UPDATE admission SET window=? WHERE shop=?').bind(currentWindow-1,'proof-shop').run();
  await r.db.prepare('INSERT INTO admission(shop,bucket,window,count) VALUES(?,?,?,?)').bind('proof-shop','current-window-fixture',currentWindow,2).run();
  await r.db.prepare('INSERT INTO daily_admission(shop,day,count) VALUES(?,?,?)').bind('proof-shop',currentDay-1,9).run();

  const row=await stored(r,id),attestations={[id]:{owner:'fixture-owner',resolved:true,collected:true,billId:claim.body.billId,reason:'Synthetic bill and collection verified'}};
  const passphrase='Phase4A synthetic archive passphrase',archive=await exportOrders(r,{ids:[id],directory:path.join(r.directory,'exports'),passphrase,attestations});
  const secondCopy=archive.file+'.owner-copy';await copyFile(archive.file,secondCopy);
  const cleanup=await compactVerified(r,archive,passphrase,{ownerConfirmed:true,secondCopy});
  assert.equal(cleanup.expiredAdmission.expiredAdmissionRows,1);
  assert.equal(cleanup.expiredAdmission.expiredDailyRows,1);
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM admission WHERE shop=?').bind('proof-shop').first()).n,1);
  assert.equal((await r.db.prepare('SELECT count,window FROM admission WHERE bucket=?').bind('current-window-fixture').first()).count,2);
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM daily_admission WHERE shop=? AND day=?').bind('proof-shop',currentDay).first()).n,1);
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM daily_admission WHERE shop=? AND day=?').bind('proof-shop',currentDay-1).first()).n,0);
  const after=await stored(r,id);assert.equal(after.bill_id,row.bill_id);assert.equal(after.submission,row.submission);
  assert.equal(after.status,'COMPLETED');assert.ok(JSON.parse(after.doc).archiveRef===archive.sha256);
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM events WHERE order_id=?').bind(id).first()).n,1);
 }finally{await r.close();}
});
