import {test} from 'node:test';
import assert from 'node:assert/strict';
import {runtime,seed,draft,call,token,stored} from '../runtime.mjs';
import {projectProduct} from '../pricing.mjs';
test('native public catalog batches are owner-only, bounded and atomically exclude costs/stock/private fields',async()=>{
 const r=await runtime();try{
 const products=Array.from({length:40},(_,i)=>({product:{id:'native-'+i,name:'Native elbow',category:'Plumbing',type:'Fitting',size:i+' mm',unit:'Piece',sellingPricePaisa:'12345',currentStock:20,purchasePricePaisa:'SECRET',passwordHash:'SECRET'},published:true}));
 assert.equal((await call(r,'catalog/publish-batch',{auth:'fixture-worker-a',body:{products}})).status,403);
 assert.equal((await call(r,'catalog/publish-batch',{auth:'fixture-owner-a',body:{products:[...products,products[0]]}})).status,400);
 assert.equal((await call(r,'catalog/publish-batch',{auth:'fixture-owner-a',body:{products}})).body.publishedCount,40);
 const catalog=await call(r,'catalog',{method:'GET'});assert.equal(catalog.body.products.length,40);assert.ok(!JSON.stringify(catalog.body).includes('SECRET'));assert.ok(!JSON.stringify(catalog.body).includes('currentStock'));
 const bad=[{...products[0],product:{...products[0].product,sellingPricePaisa:'999'}},{...products[1],product:{...products[1].product,sellingPricePaisa:'-1'}}];assert.equal((await call(r,'catalog/publish-batch',{auth:'fixture-owner-a',body:{products:bad}})).status,400);
 assert.equal((await call(r,'catalog',{method:'GET'})).body.products[0].sellingPricePaisa,'12345');
 }finally{await r.close();}
});
test('native owner publishes only public config; worker cannot configure; persisted hours control customer admission after restart',async()=>{
 let r=await runtime();try{await seed(r);const body={name:'Synthetic native shop',phone:'9876543210',address:'Synthetic street',openMinute:0,closeMinute:1440,maintenance:true};
 assert.equal((await call(r,'owner/public-config',{auth:'fixture-worker-a',body})).status,403);assert.equal((await call(r,'owner/public-config',{auth:'fixture-owner-a',body:{...body,passwordHash:'PRIVATE'}})).status,400);
 assert.equal((await call(r,'owner/public-config',{auth:'fixture-owner-a',body})).status,200);assert.equal((await call(r,'public-config',{method:'GET'})).body.name,body.name);assert.equal((await call(r,'orders',{body:draft()})).status,409);
 const directory=r.directory;await r.close();r=await runtime({directory,initialise:false});assert.equal((await call(r,'public-config',{method:'GET'})).body.maintenance,true);
 }finally{await r.close();}
});
test('worker adjustment requires confirmed customer, records before/after/actor, is CAS protected and cannot change a claimed order',async()=>{
 const r=await runtime();try{await seed(r,4);const made=await call(r,'orders',{body:draft()}),id=made.body.id;await call(r,`orders/${id}/accept`,{auth:'fixture-worker-a',body:{expectedRevision:1}});
 const edit={expectedRevision:2,items:[{productId:'p00000',quantity:'2'}],reason:'Customer confirmed two pieces',customerConfirmed:false};
 assert.equal((await call(r,`orders/${id}/adjust`,{auth:'fixture-worker-a',body:edit})).status,400);
 const result=await call(r,`orders/${id}/adjust`,{auth:'fixture-worker-a',body:{...edit,customerConfirmed:true}});assert.equal(result.status,200);assert.equal(result.body.totalPaisa,'2000');
 const doc=JSON.parse((await stored(r,id)).doc);assert.equal(doc.adjustments[0].beforeItems[0].quantity,'1');assert.equal(doc.adjustments[0].afterItems[0].quantity,'2');assert.equal(doc.adjustments[0].actor,'shared-staff-uid');
 assert.equal((await call(r,`orders/${id}/adjust`,{auth:'fixture-worker-b',body:{...edit,customerConfirmed:true}})).status,409);
 const claimToken=token();assert.equal((await call(r,`orders/${id}/billing-claim`,{auth:'fixture-worker-a',body:{expectedRevision:3,claimToken}})).status,200);
 assert.equal((await call(r,`orders/${id}/adjust`,{auth:'fixture-worker-a',body:{...edit,expectedRevision:4,customerConfirmed:true}})).status,409);
 }finally{await r.close();}
});
test('same installation owner/worker fixtures retain sticky claim; existing POS sync identity is linked without replacing it',async()=>{
 const r=await runtime();try{await seed(r);const made=await call(r,'orders',{body:draft()}),id=made.body.id;await call(r,`orders/${id}/accept`,{auth:'fixture-owner-a',body:{expectedRevision:1}});
 const claimToken=token(),claim=await call(r,`orders/${id}/billing-claim`,{auth:'fixture-owner-a',body:{expectedRevision:2,claimToken}});
 assert.equal((await call(r,`orders/${id}/billing-claim`,{auth:'fixture-worker-a',body:{expectedRevision:2,claimToken}})).body.billId,claim.body.billId);
 assert.equal((await call(r,`orders/${id}/billing-claim`,{auth:'fixture-owner-b',body:{expectedRevision:3,claimToken:token()}})).status,409);
 const completed=await call(r,`orders/${id}/complete`,{auth:'fixture-worker-a',body:{expectedRevision:3,claimToken,billId:claim.body.billId,posBillSyncId:'existing-pos-sync-id-0123456789'}});assert.equal(completed.status,200);
 const feed=await call(r,'pos/events',{method:'GET',auth:'fixture-worker-b'});assert.equal(feed.body.events.at(-1).doc.finalizedPosBillSyncId,'existing-pos-sync-id-0123456789');
 }finally{await r.close();}
});
test('large protected order documents synchronize in bounded pages without skipping cursor events',async()=>{
 const r=await runtime();try{const doc=JSON.stringify({customer:{name:'Synthetic',phone:'9876543210'},items:[{name:'Elbow',productId:'fixture'}],note:'x'.repeat(90000),estimatedSubtotalPaisa:'12345'});const statements=[];
 for(let i=0;i<20;i++){const id=crypto.randomUUID();statements.push(r.db.prepare("INSERT INTO orders(id,shop,submission,capability_hash,payload_hash,doc,status,revision,operation,updated_at) VALUES(?,'proof-shop',?,?,?,?, 'NEW',1,?,1)").bind(id,crypto.randomUUID(),token(),token(),doc,id));statements.push(r.db.prepare("INSERT INTO events(shop,order_id,revision,kind,created_at) VALUES('proof-shop',?,1,'NEW',1)").bind(id));}await r.db.batch(statements);
 const first=await call(r,'pos/events?cursor=0',{method:'GET',auth:'fixture-worker-a'});assert.equal(first.body.hasMore,true);assert.ok(JSON.stringify(first.body).length<1010000);
 const second=await call(r,'pos/events?cursor='+first.body.cursor,{method:'GET',auth:'fixture-worker-a'});assert.equal(first.body.events.length+second.body.events.length,20);assert.equal(new Set([...first.body.events,...second.body.events].map(e=>e.id)).size,20);
 }finally{await r.close();}
});
