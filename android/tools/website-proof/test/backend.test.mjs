import {test,before,after} from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {readFile} from "node:fs/promises";
import path from "node:path";
import {runtime,seed,draft,call,stored,token,root,product} from "../runtime.mjs";
import {projectProduct,priceLine} from "../worker.mjs";
import {LocalPosProof} from "../pos-proof.mjs";
import {SubmissionClient} from "../submission-client.mjs";
let rt;
before(async()=>{rt=await runtime();await seed(rt,3000);});
after(async()=>{await rt?.close();});
const success=(result,status=200)=>{assert.equal(result.status,status,JSON.stringify(result.body));return result.body;};
const count=async(table,where="",args=[])=>Number((await rt.db.prepare(`SELECT COUNT(*) AS n FROM ${table} ${where}`).bind(...args).first()).n);
const create=async(body=draft())=>({body,receipt:success(await call(rt,"orders",{body}),201)});
const action=(id,name,body,auth="fixture-worker-a")=>call(rt,`orders/${id}/${name}`,{body,auth});

test("public projection excludes private POS data, and all 3000 default-published variants can be paged",async()=>{
  let after="",seen=[];
  do{const result=success(await call(rt,`catalog?after=${after}`,{method:"GET"}));seen.push(...result.products);after=result.next;}while(after);
  assert.equal(seen.length,3001);assert.equal(new Set(seen.map(p=>p.id)).size,3001);
  for(const p of seen){for(const key of ["currentStock","purchasePricePaisa","supplier","passwordHash","imagePath"])assert.equal(key in p,false);}
  assert.equal(seen.filter(p=>p.name===product(0).name).length,4);
  assert.equal((await call(rt,"catalog/publish",{body:{product:product(),published:false},auth:"fixture-worker-a"})).status,403);
});
test("shared fixtures match pipe pricing/conversions and reject unsupported decimals/overflow",async()=>{
  const fixtures=JSON.parse(await readFile(path.join(root,"fixtures/pipe.json"),"utf8"));
  for(const c of fixtures){const p=projectProduct({id:"pipe",name:"Pipe",category:"Pipes",type:"PVC",size:"1 inch",unit:c.unit,sellingPricePaisa:c.lengthPrice,
    isPipe:true,feetPerLength:c.physical,lengthPricePaisa:c.lengthPrice,footPricePaisa:c.footPrice});
    const f=()=>priceLine(p,{productId:p.id,fullLengths:c.lengths,extraFeet:c.feet});
    if(c.error)assert.throws(f);else{const row=f();assert.equal(row.estimatedLineTotalPaisa,c.total);assert.equal(row.inventoryUnitEquivalent,c.quantity);}}
});
test("checkout accepts zero/negative-stock fixtures and pipe orders without any reservation or stock table",async()=>{
  const {receipt}=await create(draft(2));assert.equal(receipt.totalPaisa,"2001");
  const b=draft();b.items=[{productId:"pipe20",fullLengths:"1",extraFeet:"10"}];assert.equal((await create(b)).receipt.totalPaisa,"75000");
  const tables=await rt.db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all();
  assert.ok(!tables.results.some(x=>/stock|reserv/i.test(x.name)));
});
test("server rejects tampered prices, malformed quantities, duplicate sizes, deep and oversized input",async()=>{
  for(const quantity of ["0","-1","NaN","Infinity","1e3","1.001",1]){const b=draft();b.items[0].quantity=quantity;assert.equal((await call(rt,"orders",{body:b})).status,400);}
  let b=draft();b.items[0].unitPricePaisa="1";assert.equal((await call(rt,"orders",{body:b})).status,400);
  b=draft();b.items.push({...b.items[0]});assert.equal((await call(rt,"orders",{body:b})).status,400);
  b=draft();b.items=[null];assert.equal((await call(rt,"orders",{body:b})).status,400);
  assert.equal((await call(rt,"orders",{raw:'{"x":'+"[".repeat(12)+"0"+"]".repeat(12)+"}"})).status,400);
  assert.equal((await call(rt,"orders",{raw:"x".repeat(32769)})).status,413);
  assert.equal((await call(rt,"orders",{raw:"not json"})).status,400);
});
test("parallel same-key submissions persist one order/event and changed payload conflicts",async()=>{
  const b=draft();const responses=await Promise.all(Array.from({length:20},()=>call(rt,"orders",{body:b})));
  assert.ok(responses.every(r=>[200,201].includes(r.status)),JSON.stringify(responses));
  const ids=new Set(responses.map(r=>r.body.id));assert.equal(ids.size,1);
  const id=[...ids][0];assert.equal(await count("events","WHERE order_id=?",[id]),1);
  assert.equal(await count("orders","WHERE submission=?",[b.submissionId]),1);
  const changed={...b,note:"changed cart"};assert.equal((await call(rt,"orders",{body:changed})).status,409);
  assert.equal((await call(rt,"recover",{body:{submissionId:b.submissionId,capability:token()}})).status,404);
  assert.equal((await call(rt,"recover",{body:{submissionId:randomUUID(),capability:token()}})).status,404);
});
test("customer edit/cancel is revision checked and stops on acceptance",async()=>{
  const {body:b,receipt:r}=await create();
  const changed={customer:b.customer,items:[{productId:product().id,quantity:"2",note:"Customer agreed size"}],note:"edit"};
  const updated=success(await call(rt,`orders/${r.id}/edit`,{body:{expectedRevision:1,order:changed},capability:b.capability}));assert.equal(updated.totalPaisa,"2000");
  assert.equal((await call(rt,`orders/${r.id}/edit`,{body:{expectedRevision:1,order:changed},capability:b.capability})).status,409);
  const accepted=success(await action(r.id,"accept",{expectedRevision:2}));assert.equal(accepted.status,"ACCEPTED");
  assert.equal((await call(rt,`orders/${r.id}/customer-cancel`,{body:{expectedRevision:3},capability:b.capability})).status,409);
  const c=await create();assert.equal(success(await call(rt,`orders/${c.receipt.id}/customer-cancel`,{body:{expectedRevision:1},capability:c.body.capability})).status,"CANCELLED");
});
test("concurrent accept/edit/cancel cannot overwrite the winning revision",async()=>{
  const {body,receipt}=await create();
  const results=await Promise.all([
    action(receipt.id,"accept",{expectedRevision:1}),
    call(rt,`orders/${receipt.id}/customer-cancel`,{body:{expectedRevision:1},capability:body.capability})
  ]);assert.equal(results.filter(r=>r.status===200).length,1);assert.equal(results.filter(r=>r.status===409).length,1);
  assert.equal(await count("events","WHERE order_id=?",[receipt.id]),2);
});
test("one of 20 concurrent workers accepts, with one logical event",async()=>{
  const {receipt:r}=await create();const results=await Promise.all(Array.from({length:20},(_,i)=>action(r.id,"accept",{expectedRevision:1},i%2?"fixture-worker-a":"fixture-worker-b")));
  assert.equal(results.filter(r=>r.status===200).length,1);assert.equal(results.filter(r=>r.status===409).length,19);
  assert.equal(await count("events","WHERE order_id=?",[r.id]),2);
  const prep=success(await action(r.id,"prepare",{expectedRevision:2}));assert.equal(prep.status,"PREPARING");
  const ready=success(await action(r.id,"ready",{expectedRevision:3},"fixture-worker-b"));assert.equal(ready.status,"READY_FOR_PICKUP");
  assert.equal((await stored(rt,r.id)).status,"READY_FOR_PICKUP");
});
test("tenant, revoked/expired identity, unauthorized publication and origin checks fail closed",async()=>{
  for(const auth of [undefined,"bad","fixture-expired","fixture-other-shop"]){assert.equal((await call(rt,"pos/events",{method:"GET",auth})).status,403);}
  assert.equal((await call(rt,"orders",{body:draft(),headers:{origin:"https://malicious.invalid"}})).status,403);
  const response=await rt.mf.dispatchFetch("https://proof.invalid/shops/other-shop/pos/events",{headers:{authorization:"Bearer fixture-owner"}});assert.equal(response.status,404);
  const row=await create();assert.equal((await action(row.receipt.id,"accept",{expectedRevision:1},"bad")).status,403);
});
test("unpublished product is removed from public API and invalidates a cached cart",async()=>{
  const b=draft(1,2700);success(await call(rt,"catalog/publish",{body:{product:product(2700),published:false},auth:"fixture-owner"}));
  assert.equal((await call(rt,"orders",{body:b})).status,400);
  const result=success(await call(rt,"catalog?after=p02699",{method:"GET"}));assert.ok(!result.products.some(p=>p.id===product(2700).id));
  success(await call(rt,"catalog/publish",{body:{product:product(2700),published:true},auth:"fixture-owner"}));
});
test("atomic admission limits roll back a rejected order and never charge known retries as new",async()=>{
  const first=[];for(let i=0;i<3;i++){const b=draft();first.push(b);success(await call(rt,"orders",{body:b,ip:"shared-fixture-network"}),201);}
  const b=draft();const response=await call(rt,"orders",{body:b,ip:"shared-fixture-network"});assert.equal(response.status,429);assert.equal("orderNumber" in response.body,false);
  assert.equal(await count("orders","WHERE submission=?",[b.submissionId]),0);
  assert.equal((await call(rt,"orders",{body:first[0],ip:"shared-fixture-network"})).status,200);
});
test("lost response recovers one committed order after client restart and preserves a failed cart",async()=>{
  const memory=new Map(),storage={getItem:k=>memory.get(k)??null,setItem:(k,v)=>memory.set(k,v)};let drop=true;
  const send=async(route,body)=>{const result=await call(rt,route,{body});if(drop && route==="orders"){drop=false;throw Error("Injected response loss after commit");}return result;};
  let client=new SubmissionClient(storage,send);client.freeze({customer:{name:"Synthetic retry",phone:"9876543210"},items:draft().items});
  const id=client.state.submissionId;await assert.rejects(()=>client.submit());assert.equal(client.state.phase,"unknown");assert.ok(client.state.cart);
  client=new SubmissionClient(storage,send);const recovered=success(await client.submit());assert.ok(recovered.confirmed);assert.equal(client.state.cart,null);
  assert.equal(await count("orders","WHERE submission=?",[id]),1);
});
test("persistent D1 restart retains orders, capability recovery, versions and event cursor",async()=>{
  const {body,receipt}=await create();const directory=rt.directory;
  await rt.close();rt=await runtime({directory,initialise:false});
  assert.equal(success(await call(rt,"recover",{body:{submissionId:body.submissionId,capability:body.capability}})).id,receipt.id);
  assert.ok(success(await call(rt,"pos/events?cursor=0",{method:"GET",auth:"fixture-worker-a"})).events.length>0);
});
test("sticky device claim, rollback before commit, restart after commit, completion retry and single local history/stock effect",async()=>{
  const {receipt:r}=await create();success(await action(r.id,"accept",{expectedRevision:1}));
  const claimToken=token();const claim=success(await action(r.id,"billing-claim",{expectedRevision:2,claimToken}));
  assert.equal((await action(r.id,"billing-claim",{expectedRevision:2,claimToken:token()},"fixture-worker-b")).status,409);
  assert.equal(success(await action(r.id,"billing-claim",{expectedRevision:2,claimToken})).billId,claim.billId);
  assert.equal((await action(r.id,"cancel",{expectedRevision:claim.revision,reason:"Cannot reassign offline claim"})).status,409);
  const file=path.join(rt.directory,"isolated-pos.sqlite");let pos=new LocalPosProof(file,"device-a");
  pos.db.prepare("INSERT INTO stock VALUES(?,?)").run("synthetic",20);pos.rememberClaim(r.id,claim,claimToken);
  assert.throws(()=>pos.commit(r.id,r.totalPaisa,[{productId:"synthetic",quantity:2}],{failBeforeCommit:true}));
  assert.equal(pos.db.prepare("SELECT quantity FROM stock").get().quantity,20);assert.equal(pos.db.prepare("SELECT COUNT(*) AS n FROM bills").get().n,0);
  pos.commit(r.id,r.totalPaisa,[{productId:"synthetic",quantity:2}]);pos.close();pos=new LocalPosProof(file,"device-a");
  pos.commit(r.id,r.totalPaisa,[{productId:"synthetic",quantity:2}]);
  const completion=JSON.parse(pos.db.prepare("SELECT body FROM completion_outbox").get().body);
  const complete=success(await action(r.id,"complete",completion));assert.equal(complete.status,"COMPLETED");
  assert.equal(success(await action(r.id,"complete",completion)).billId,claim.billId);
  assert.equal(pos.db.prepare("SELECT quantity FROM stock").get().quantity,18);
  assert.equal(pos.db.prepare("SELECT COUNT(*) AS n FROM bills").get().n,1);assert.equal(pos.db.prepare("SELECT COUNT(*) AS n FROM payments").get().n,1);
  assert.equal(await count("events","WHERE order_id=? AND kind='COMPLETED'",[r.id]),1);pos.close();
});
test("sync cursor and versioned inbox commit together, survive restart and ignore duplicate/stale delivery",async()=>{
  const file=path.join(rt.directory,"isolated-inbox.sqlite");let pos=new LocalPosProof(file,"device-a");
  const feed=success(await call(rt,"pos/events?cursor=0",{method:"GET",auth:"fixture-worker-a"}));
  assert.throws(()=>pos.applyFeed(feed,{failBeforeCommit:true}));assert.equal(pos.db.prepare("SELECT value FROM cursor").get().value,0);
  pos.applyFeed(feed);const n=pos.db.prepare("SELECT COUNT(*) AS n FROM inbox").get().n;pos.close();pos=new LocalPosProof(file,"device-a");
  pos.applyFeed(feed);assert.equal(pos.db.prepare("SELECT COUNT(*) AS n FROM inbox").get().n,n);assert.equal(pos.db.prepare("SELECT value FROM cursor").get().value,feed.cursor);pos.close();
});
test("persistence/quota failure returns no invented confirmation and preserved cart can retry",async()=>{
  const fault=await runtime({bindings:{PROOF_FAIL_WRITES:"true"}});try{await seed(fault,2);const b=draft();
    const failed=await call(fault,"orders",{body:b});assert.equal(failed.status,503);assert.equal(failed.body.confirmed,undefined);assert.equal(failed.body.orderNumber,undefined);
    assert.equal(Number((await fault.db.prepare("SELECT COUNT(*) n FROM orders").first()).n),0);
  }finally{await fault.close();}
});
test("maintenance blocks new submissions but not browsing or known-submission recovery",async()=>{
  const maintenance=await runtime({bindings:{PROOF_MAINTENANCE:"true"}});try{await seed(maintenance,2);
    assert.equal((await call(maintenance,"orders",{body:draft()})).status,409);assert.equal((await call(maintenance,"catalog",{method:"GET"})).status,200);
  }finally{await maintenance.close();}
});
