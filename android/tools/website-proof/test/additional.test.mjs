import {test} from "node:test";
import assert from "node:assert/strict";
import {runtime,seed,draft,call,token,identities} from "../runtime.mjs";
import {SubmissionClient} from "../submission-client.mjs";
import {LocalPosProof} from "../pos-proof.mjs";
import path from "node:path";
test("daily admission exhaustion rolls back creation and retains the customer's cart",async()=>{
  const r=await runtime();try{await seed(r,2);
    const day=Math.floor(Date.now()/86400000);await r.db.prepare("INSERT INTO daily_admission VALUES('proof-shop',?,1200)").bind(day).run();
    const memory=new Map(),storage={getItem:k=>memory.get(k)??null,setItem:(k,v)=>memory.set(k,v)};
    const client=new SubmissionClient(storage,(route,body)=>call(r,route,{body}));
    client.freeze({customer:{name:"Quota customer",phone:"9876543210"},items:draft().items});
    const result=await client.submit();assert.equal(result.status,429);assert.equal(client.state.phase,"unknown");assert.ok(client.state.cart);
    assert.equal(result.body.orderNumber,undefined);assert.equal(result.body.confirmed,undefined);
    assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM orders").first()).n,0);
    assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM events").first()).n,0);
  }finally{await r.close();}
});
test("local proof cannot run with production mode or revoked fixture identity",async()=>{
  let r=await runtime({bindings:{LOCAL_PROOF_ONLY:"false"},initialise:false});try{assert.equal((await call(r,"catalog",{method:"GET"})).status,503);}finally{await r.close();}
  r=await runtime({bindings:{LOCAL_IDENTITIES:JSON.stringify({...identities,"fixture-revoked":{...identities["fixture-worker-a"],revoked:true}})}});
  try{assert.equal((await call(r,"pos/events",{method:"GET",auth:"fixture-revoked"})).status,403);}finally{await r.close();}
});
test("two simultaneous billing installations produce one sticky bill identity, including after restart",async()=>{
  let r=await runtime();let closed=false;try{await seed(r,2);
    const created=await call(r,"orders",{body:draft()});assert.equal(created.status,201);const id=created.body.id;
    assert.equal((await call(r,`orders/${id}/accept`,{auth:"fixture-worker-a",body:{expectedRevision:1}})).status,200);
    const entries=[{auth:"fixture-worker-a",claimToken:token()},{auth:"fixture-worker-b",claimToken:token()}];
    const claims=await Promise.all(entries.map(e=>call(r,`orders/${id}/billing-claim`,{auth:e.auth,body:{expectedRevision:2,claimToken:e.claimToken}})));
    assert.equal(claims.filter(c=>c.status===200).length,1);assert.equal(claims.filter(c=>c.status===409).length,1);
    const winner=claims.findIndex(c=>c.status===200),directory=r.directory;
    await r.db.prepare("UPDATE orders SET updated_at=1 WHERE id=?").bind(id).run();
    await r.close();closed=true;r=await runtime({directory,initialise:false});closed=false;
    const retry=await call(r,`orders/${id}/billing-claim`,{auth:entries[winner].auth,body:{expectedRevision:2,claimToken:entries[winner].claimToken}});
    assert.equal(retry.body.billId,claims[winner].body.billId);
    assert.equal((await call(r,`orders/${id}/billing-claim`,{auth:entries[1-winner].auth,body:{expectedRevision:2,claimToken:entries[1-winner].claimToken}})).status,409);
    assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM events WHERE order_id=? AND kind='BILLING_CLAIMED'").bind(id).first()).n,1);
  }finally{if(!closed)await r.close();}
});
test("SQL-looking/customer markup stays data, no phone lookup exists, fixture SQL route requires its private test key",async()=>{
  const r=await runtime();try{await seed(r,2);const b=draft();b.customer.name="<script>alert('x')</script>";b.note="'); DROP TABLE orders; --";
    const result=await call(r,"orders",{body:b});assert.equal(result.status,201);
    const row=await r.db.prepare("SELECT doc FROM orders WHERE id=?").bind(result.body.id).first();assert.equal(JSON.parse(row.doc).note,b.note);
    const phoneLookup=await call(r,"orders/by-phone/9876543210",{method:"GET"});
    assert.equal(phoneLookup.status,400);assert.equal(phoneLookup.body.error,"Invalid identity");assert.equal(phoneLookup.body.id,undefined);
    const response=await r.mf.dispatchFetch("http://localhost/__local_fixture_sql",{method:"POST",headers:{"content-type":"application/json"},body:'{"sql":"SELECT 1","args":[]}'});assert.equal(response.status,403);
  }finally{await r.close();}
});
test("claim response loss recovers the persisted pre-send intent after POS restart",async()=>{
  const r=await runtime();let pos;try{await seed(r,2);const created=await call(r,"orders",{body:draft()});const id=created.body.id;
    assert.equal((await call(r,`orders/${id}/accept`,{auth:"fixture-worker-a",body:{expectedRevision:1}})).status,200);
    const file=path.join(r.directory,"claim-intent.sqlite");pos=new LocalPosProof(file,"device-a");const claimToken=pos.beginClaim(id,token());
    const lost=await call(r,`orders/${id}/billing-claim`,{auth:"fixture-worker-a",body:{expectedRevision:2,claimToken}});assert.equal(lost.status,200);
    pos.close();pos=new LocalPosProof(file,"device-a");const retryToken=pos.beginClaim(id,token());assert.equal(retryToken,claimToken);
    const recovered=await call(r,`orders/${id}/billing-claim`,{auth:"fixture-worker-a",body:{expectedRevision:2,claimToken:retryToken}});
    assert.equal(recovered.body.billId,lost.body.billId);pos.rememberClaim(id,recovered.body,retryToken);
  }finally{pos?.close();await r.close();}
});
