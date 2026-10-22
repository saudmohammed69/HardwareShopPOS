// Customer identity-aware recovery with drain-before-disposal. Original raw
// transport regression failures remain evidence; this does not redefine them.
import assert from 'node:assert/strict';
import {runtime,seed,draft,call} from './runtime.mjs';
import {abuseFixtureBindings,anonymousSession} from './customer-abuse-fixture.mjs';
import {submitRecoverably} from './customer-recovery.mjs';
import {writeFile} from 'node:fs/promises';
const profiles=[];
for(const count of [20,50,100]){
 const r=await runtime({bindings:{...abuseFixtureBindings,PROOF_TRACE:'true'},pooledTransport:true}),metrics={transportFailures:0,recoveryAttempts:0,unrecoverable:0,transportEvents:[]};let result;
 try{await seed(r,30);const bodies=Array.from({length:count},()=>draft(5));
  const created=await Promise.allSettled(bodies.map(body=>submitRecoverably(r,body,{ip:'one-carrier-nat'},metrics)));
  assert.ok(created.every(x=>x.status==='fulfilled'),JSON.stringify(created.filter(x=>x.status==='rejected').map(x=>String(x.reason))));
  assert.ok(created.every(x=>x.value.status===201));
  const duplicates=await Promise.allSettled(bodies.map(body=>submitRecoverably(r,body,{ip:'one-carrier-nat',abuse:false},metrics)));
  assert.ok(duplicates.every((x,i)=>x.status==='fulfilled'&&x.value.status===200&&x.value.body.id===created[i].value.body.id));
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,count);
  const session=anonymousSession();for(let i=0;i<3;i++)assert.equal((await submitRecoverably(r,draft(),{ip:'abuse-ip-'+i,customerSession:session},metrics)).status,201);
  assert.equal((await call(r,'orders',{body:draft(),ip:'different-ip',customerSession:session})).status,429);
  assert.equal((await call(r,'orders',{body:draft(),abuse:false})).status,403);
  result={status:'PASS',customers:count,orders:count,duplicateOrders:0,recovery:metrics,apiMeter:await r.apiMeter(),fixture:r.directory};
 }catch(e){result={status:'FAIL',customers:count,error:e.stack,recovery:metrics,fixture:r.directory};process.exitCode=1;}
 finally{try{await writeFile(`evidence/phase4h-shared-ip-${count}-lifecycle.json`,JSON.stringify(await r.lifecycle()));}catch(e){result.traceError=String(e);}await r.close();profiles.push(result);await writeFile('evidence/phase4h-shared-ip-results.json',JSON.stringify(profiles,null,2));console.log(JSON.stringify(result));}
 if(result.status!=='PASS')break;
}
