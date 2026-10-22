import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,copyFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {runtime,seed,draft,call,token,stored} from '../runtime.mjs';
import {exportOrders,compactVerified,verifyRestore,readArchive,storagePolicy} from '../retention.mjs';
import {SubmissionClient} from '../submission-client.mjs';
test('HTTP 200 with a malformed receipt never clears or confirms an unresolved cart',async()=>{
 const memory=new Map(),storage={getItem:k=>memory.get(k)??null,setItem:(k,v)=>memory.set(k,v)};
 const client=new SubmissionClient(storage,async()=>({status:200,body:{confirmed:true,error:'malformed gateway response'}}));client.freeze({items:[{productId:'p00000',quantity:'1'}]});
 await client.submit();assert.equal(client.state.phase,'unknown');assert.ok(client.state.cart);
 assert.equal((await client.submit()).status,503);assert.equal(client.state.phase,'unknown');assert.ok(client.state.cart);
});
test('a cart revised after a recovery 404 retains identity even when an older delayed request subsequently commits',async()=>{
 const r=await runtime();try{await seed(r,2);const memory=new Map(),storage={getItem:k=>memory.get(k)??null,setItem:(k,v)=>memory.set(k,v)};
 const client=new SubmissionClient(storage,(route,body)=>call(r,route,{body}));const original=draft();delete original.submissionId;delete original.capability;client.freeze(original);const identity=client.state.submissionId,cap=client.state.capability,old={...original,submissionId:identity,capability:cap};
 assert.equal((await call(r,'recover',{body:{submissionId:identity,capability:cap}})).status,404);client.state.phase='revising';client.persist();
 client.freeze({...original,note:'Revised after a timeout'});assert.equal(client.state.submissionId,identity);assert.equal(client.state.capability,cap);
 const late=await call(r,'orders',{body:old});assert.equal(late.status,201);
 assert.equal((await client.submit()).status,409);assert.equal((await client.submit()).body.id,late.body.id);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,1);
 }finally{await r.close();}
});
test('owner archive: encrypted verifiable restore, explicit cleanup, retained identities/audit/bill and duplicate protection',async()=>{
 const r=await runtime();try{await seed(r,22);const b=draft(20),made=await call(r,'orders',{body:b}),id=made.body.id;
 assert.equal(made.status,201);await call(r,`orders/${id}/accept`,{auth:'fixture-worker-a',body:{expectedRevision:1}});
 const claimToken=token(),claim=await call(r,`orders/${id}/billing-claim`,{auth:'fixture-worker-a',body:{expectedRevision:2,claimToken}});
 await call(r,`orders/${id}/complete`,{auth:'fixture-worker-a',body:{expectedRevision:3,claimToken,billId:claim.body.billId}});
 const opts={ids:[id],directory:path.join(r.directory,'exports'),passphrase:'private test archive passphrase',attestations:{[id]:{owner:'fixture-owner',resolved:true,collected:true,billId:claim.body.billId,reason:'Synthetic finalized bill and collection verified'}}};
 await assert.rejects(exportOrders(r,{...opts,attestations:{}}),/attestation/);
 const archive=await exportOrders(r,opts),copy=archive.file+'.second-copy';await copyFile(archive.file,copy);
 const before=await stored(r,id),verified=await verifyRestore(archive.file,opts.passphrase,archive.sha256);
 assert.equal(verified.restoredOrders,1);assert.equal(verified.restoredEvents,4);assert.equal(verified.payload.orders[0].doc,before.doc);
 assert.ok(!(await readFile(archive.file,'utf8')).includes('Synthetic customer'));
 await assert.rejects(readArchive(archive.file,'wrong passphrase',archive.sha256));
 await assert.rejects(compactVerified(r,archive,opts.passphrase,{secondCopy:copy}),/confirmation/);
 await assert.rejects(compactVerified(r,archive,opts.passphrase,{ownerConfirmed:true,secondCopy:archive.file}),/Independent/);
 const result=await compactVerified(r,archive,opts.passphrase,{ownerConfirmed:true,secondCopy:copy});assert.equal(result.compacted,1);
 const after=await stored(r,id);assert.equal(after.bill_id,before.bill_id);assert.equal(after.revision,before.revision);assert.equal(after.submission,before.submission);assert.ok(after.doc.length<before.doc.length);
 assert.equal((await call(r,'orders',{body:b})).body.id,id);assert.equal((await call(r,'recover',{body:{submissionId:b.submissionId,capability:b.capability}})).body.totalPaisa,made.body.totalPaisa);
 assert.equal((await call(r,`orders/${id}/complete`,{auth:'fixture-worker-a',body:{expectedRevision:4,claimToken,billId:claim.body.billId}})).status,200);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM events WHERE order_id=?').bind(id).first()).n,1);
 const feed=await call(r,'pos/events?cursor=0',{method:'GET',auth:'fixture-worker-b'});assert.equal(feed.body.events[0].status,'COMPLETED');assert.equal(feed.body.events[0].doc.archiveRef,archive.sha256);assert.equal(feed.body.events[0].seq,verified.payload.events.at(-1).seq);
 await assert.rejects(compactVerified(r,archive,opts.passphrase,{ownerConfirmed:true,secondCopy:copy}));
 await writeFile(copy,'corrupt');await assert.rejects(compactVerified(r,archive,opts.passphrase,{ownerConfirmed:true,secondCopy:copy}),/checksum/);
 }finally{await r.close();}
});
test('archive excludes every active state and uncollected/unresolved terminal orders; stale snapshot aborts whole cleanup batch',async()=>{
 const r=await runtime();try{await seed(r,2);const ids=[];
 for(const status of ['NEW','ACCEPTED','PREPARING','READY_FOR_PICKUP','BILLING_IN_PROGRESS','COMPLETED','CANCELLED']){
  const made=await call(r,'orders',{body:draft()});const id=made.body.id;ids.push(id);await r.db.prepare('UPDATE orders SET status=? WHERE id=?').bind(status,id).run();
  await assert.rejects(exportOrders(r,{ids:[id],directory:r.directory,passphrase:'private test passphrase',attestations:{}}));
 }
 const terminal=ids.slice(-2);await r.db.prepare('UPDATE orders SET bill_id=? WHERE id=?').bind(crypto.randomUUID(),terminal[0]).run();
 const attestations={};for(const id of terminal){const row=await stored(r,id);attestations[id]={owner:'fixture-owner',resolved:true,collected:true,billId:row.bill_id,reason:'fixture resolved'};}
 const passphrase='private test passphrase',a=await exportOrders(r,{ids:terminal,directory:r.directory,passphrase,attestations});const copy=a.file+'.copy';await copyFile(a.file,copy);
 const first=await stored(r,terminal[0]);await r.db.prepare('UPDATE orders SET revision=revision+1 WHERE id=?').bind(terminal[1]).run();
 await assert.rejects(compactVerified(r,a,passphrase,{ownerConfirmed:true,secondCopy:copy}));assert.equal((await stored(r,terminal[0])).doc,first.doc);
 assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,7);
 }finally{await r.close();}
});
test('native allocated storage monitor warns/stops admission before limit, preserves recoveries and has owner-only diagnostics',async()=>{
 const plan=await runtime();try{const query=(await plan.db.prepare('EXPLAIN QUERY PLAN SELECT * FROM events WHERE order_id=? ORDER BY seq').bind('fixture').all()).results;assert.ok(JSON.stringify(query).includes('events_order'));}finally{await plan.close();}
 assert.equal(storagePolicy(325000000).level,'WARNING');assert.equal(storagePolicy(400000000).level,'CRITICAL');assert.equal(storagePolicy(450000000).acceptNew,false);assert.equal(storagePolicy(1,{accountBytes:4500000000}).acceptNew,false);
 const r=await runtime({bindings:{LOCAL_STORAGE_LIMIT:'1'}});try{await seed(r,2);
 assert.equal((await call(r,'owner/storage',{method:'GET'})).status,403);
 assert.equal((await call(r,'owner/storage',{method:'GET',auth:'fixture-owner'})).body.level,'STOP_NEW_ORDERS');
 const result=await call(r,'orders',{body:draft()});assert.equal(result.status,503);assert.equal(result.body.orderNumber,undefined);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,0);
 }finally{await r.close();}
});
test('customer private view, strict browser origin, branding allowlist and maintenance browsing',async()=>{
 const r=await runtime({bindings:{LOCAL_WEB_ORIGIN:'http://127.0.0.1:4317',PUBLIC_SHOP_NAME:'Fixture Hardware',PRIVATE_SECRET:'must-not-appear'}});try{await seed(r,2);const b=draft();
 const c=await call(r,'public-config',{method:'GET'});assert.equal(c.body.name,'Fixture Hardware');assert.ok(!JSON.stringify(c.body).includes('must-not-appear'));
 assert.equal((await call(r,'orders',{body:b,headers:{origin:'https://evil.invalid'}})).status,403);
 const made=await call(r,'orders',{body:b,headers:{origin:'http://127.0.0.1:4317'}});assert.equal(made.status,201);
 assert.equal((await call(r,`orders/${made.body.id}/customer-view`,{body:{},capability:token()})).status,404);
 assert.equal((await call(r,`orders/${made.body.id}/customer-view`,{body:{},capability:b.capability})).body.order.customer.phone,b.customer.phone);
 await call(r,`orders/${made.body.id}/accept`,{auth:'fixture-worker-a',body:{expectedRevision:1}});
 assert.equal((await call(r,`orders/${made.body.id}/customer-view`,{body:{},capability:b.capability})).body.order,null);
 }finally{await r.close();}
 const closed=await runtime({bindings:{PROOF_MAINTENANCE:'true'}});try{await seed(closed);assert.equal((await call(closed,'public-config',{method:'GET'})).body.orderingOpen,false);assert.equal((await call(closed,'catalog',{method:'GET'})).status,200);assert.equal((await call(closed,'orders',{body:draft()})).status,409);}finally{await closed.close();}
});
test('server enforces daily hours and future pickup bounds independently of customer browser',async()=>{
 const closed=await runtime({bindings:{PROOF_OPEN_MINUTE:'1440',PROOF_CLOSE_MINUTE:'1440'}});try{await seed(closed);assert.equal((await call(closed,'public-config',{method:'GET'})).body.orderingOpen,false);assert.equal((await call(closed,'catalog',{method:'GET'})).status,200);assert.equal((await call(closed,'orders',{body:draft()})).status,409);}finally{await closed.close();}
 const open=await runtime();try{await seed(open);for(const pickupAt of ['2020-01-01T10:00:00Z',new Date(Date.now()+8*86400000).toISOString().replace(/\.\d{3}Z$/,'Z'),'2026-10-20T10:00'])assert.equal((await call(open,'orders',{body:{...draft(),pickupAt}})).status,400);
 const pickupAt=new Date(Date.now()+86400000).toISOString().replace(/\.\d{3}Z$/,'Z');const good=await call(open,'orders',{body:{...draft(),pickupAt}});assert.equal(good.status,201);
 }finally{await open.close();}
});
