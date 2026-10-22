import {test} from 'node:test';
import assert from 'node:assert/strict';
import {pairedFixture} from '../pairing-fixture.mjs';
import {runtime,call,draft} from '../runtime.mjs';
import {compareArchiveIdentities} from '../archive-identity-verification.mjs';
import {compareArchiveIdentities as tupleCompare} from '../fixtures/phase4j/archive-tuple-verifier.mjs';
import {comparePhase4iTable} from '../fixtures/phase4j/archive-phase4i-verifier.mjs';

test('indexed identity verification compares every field across different physical receipt rowids, shops and pages',async()=>{
 const source=await pairedFixture(),copy=await runtime({bindings:{AUTH_MODE:'PAIRING_LOCAL'}});
 try{
  const order=await call(source,'orders',{body:draft(5)});assert.equal(order.status,201);
  const row=await source.db.prepare('SELECT * FROM orders WHERE id=?').bind(order.body.id).first();
  const columns=Object.keys(row);await copy.db.prepare(`INSERT INTO orders(${columns}) VALUES(${columns.map(()=>'?')})`).bind(...Object.values(row)).run();
  // Deliberately reverse insert order in the restored physical layout.
  const receipts=Array.from({length:105},(_,i)=>({shop:i%7===0?'other-shop':'proof-shop',device:'device-'+i%5,request_id:'request-'+i,request_hash:'a'.repeat(64),order_id:'order-'+i%3,result:JSON.stringify({id:'id-'+i}),at:i}));
  const keys=Object.keys(receipts[0]);
  for(const [r,rows]of [[source,receipts],[copy,[...receipts].reverse()]])for(let i=0;i<rows.length;i+=32)await r.db.batch(rows.slice(i,i+32).map(v=>r.db.prepare(`INSERT INTO pair_operations(${keys}) VALUES(${keys.map(()=>'?')})`).bind(...Object.values(v))));
  const before=await source.fixtureSqlMeter(),copyBefore=await copy.fixtureSqlMeter();
  assert.equal(await compareArchiveIdentities(source,copy,'orders'),1);
  assert.equal(await compareArchiveIdentities(source,copy,'pair_operations'),90);
  const after=await source.fixtureSqlMeter(),copyAfter=await copy.fixtureSqlMeter();
  const costs={sourceRowsRead:after.rowsRead-before.rowsRead,sourceRowsWritten:after.rowsWritten-before.rowsWritten,restoredRowsRead:copyAfter.rowsRead-copyBefore.rowsRead,restoredRowsWritten:copyAfter.rowsWritten-copyBefore.rowsWritten};
  console.log(JSON.stringify({phase4jArchiveVerification:costs,orders:1,receipts:90}));
  assert.ok(costs.sourceRowsRead<500,'Source pages must not repeatedly scan all rows');assert.equal(costs.sourceRowsWritten,0);assert.equal(costs.restoredRowsWritten,0);
  await copy.db.prepare(`INSERT INTO pair_operations(${keys}) VALUES(${keys.map(()=>'?')})`).bind(...Object.values({...receipts[1],request_id:'extra-receipt'})).run();
  await assert.rejects(()=>compareArchiveIdentities(source,copy,'pair_operations'));
  await copy.db.prepare("DELETE FROM pair_operations WHERE request_id='extra-receipt'").run();
  await copy.db.prepare("UPDATE pair_operations SET request_hash=? WHERE request_id='request-1'").bind('b'.repeat(64)).run();
  await assert.rejects(()=>compareArchiveIdentities(source,copy,'pair_operations'));
  await copy.db.prepare("UPDATE pair_operations SET request_hash=? WHERE request_id='request-1'").bind('a'.repeat(64)).run();
  await copy.db.prepare("DELETE FROM pair_operations WHERE request_id='request-2'").run();
  await assert.rejects(()=>compareArchiveIdentities(source,copy,'pair_operations'));
 }finally{await source.close();await copy.close();}
});


test('receipt pagination stays linear when many configuration receipts share an order key',async()=>{
 const source=await runtime({bindings:{AUTH_MODE:'PAIRING_LOCAL'}}),copy=await runtime({bindings:{AUTH_MODE:'PAIRING_LOCAL'}});
 try{
  const count=1024;
  for(const r of [source,copy])for(let start=0;start<count;start+=32)await r.db.batch(Array.from({length:Math.min(32,count-start)},(_,j)=>{
   const i=start+j;
   return r.db.prepare("INSERT INTO pair_operations(shop,device,request_id,request_hash,order_id,result,at) VALUES('proof-shop',?,?,?,?,?,?)").bind('device-'+i%10,'config-'+i,'a'.repeat(64),'shared-config-key',JSON.stringify({i}),i);
  }));
  const comparisons=[];
  for(const [name,verify]of [['phase4i',comparePhase4iTable],['first-tuple',tupleCompare]]){
   const baseline=await source.fixtureSqlMeter(),copyBaseline=await copy.fixtureSqlMeter();assert.equal(await verify(source,copy,'pair_operations'),count);
   const measured=await source.fixtureSqlMeter(),copyMeasured=await copy.fixtureSqlMeter();comparisons.push({name,sourceRowsRead:measured.rowsRead-baseline.rowsRead,restoredRowsRead:copyMeasured.rowsRead-copyBaseline.rowsRead,sourceWrites:measured.rowsWritten-baseline.rowsWritten});
  }
  console.log(JSON.stringify({phase4jBaselineVerification:comparisons}));
  const before=await source.fixtureSqlMeter();assert.equal(await compareArchiveIdentities(source,copy,'pair_operations'),count);
  const after=await source.fixtureSqlMeter(),read=after.rowsRead-before.rowsRead;
  console.log(JSON.stringify({phase4jSharedKeyVerification:{receipts:count,sourceRowsRead:read,sourceRowsWritten:after.rowsWritten-before.rowsWritten}}));
  assert.ok(read<count*1.2,'Repeated receipt prefixes must not cause quadratic scans');assert.equal(after.rowsWritten,before.rowsWritten);
 }finally{await source.close();await copy.close();}
});
