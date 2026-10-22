import {test} from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {writeFile,readFile} from 'node:fs/promises';
import {pairedFixture} from '../pairing-fixture.mjs';
import {call,draft,runtime} from '../runtime.mjs';
import {startIncrement,resumeIncrement,exportChunks,copyChunks,restoreChain,acknowledgeIncrement} from '../phase4i-archive.mjs';
import {recoveryClient} from '../paired-recovery.mjs';
import {product,root} from '../runtime.mjs';
const passphrase='Phase4I local synthetic archive passphrase';
test('catalog/settings receipts bind uncertain retries while later equal intents and revocation remain distinct',async()=>{
 const r=await pairedFixture();try{const client=recoveryClient(r),p=product(0),route='catalog/publish',operationId=crypto.randomUUID(),body={product:p,published:true};
  const before=(await r.db.prepare('SELECT revision FROM catalog WHERE id=?').bind(p.id).first()).revision;
  assert.equal((await client.send(r.owner,route,{operationId,body})).status,200);
  assert.equal((await client.send(r.owner,route,{operationId,body})).status,200);
  assert.equal((await r.db.prepare('SELECT revision FROM catalog WHERE id=?').bind(p.id).first()).revision,before+1);
  assert.equal((await client.send(r.owner,route,{operationId,body:{product:p,published:false}})).status,409);
  assert.equal((await client.send(r.owner,route,{body:{product:p,published:false}})).status,200);
  assert.equal((await client.send(r.owner,route,{body})).status,200);
  assert.equal((await r.db.prepare('SELECT revision,published FROM catalog WHERE id=?').bind(p.id).first()).revision,before+3);
  assert.equal((await client.send(r.worker,route,{operationId,body})).status,403);
  const config={name:'Synthetic owner',phone:'',address:'',openMinute:0,closeMinute:1440,maintenance:false},configOp=crypto.randomUUID();
  assert.equal((await client.send(r.owner,'owner/public-config',{body:config,operationId:configOp})).status,200);
  assert.equal((await client.send(r.owner,'owner/public-config',{body:config,operationId:configOp})).status,200);
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM pair_operations WHERE device=?').bind(r.owner.deviceId).first()).n,4);
  const original=r.mf.dispatchFetch;let drop=true;
  r.mf.dispatchFetch=async(input,options)=>{const response=await original(input,options);if(drop&&String(input).endsWith('/catalog/publish')){drop=false;const error=new Error('Synthetic catalog response lost after commit');error.code='ECONNRESET';throw error;}return response;};
  const beforeLoss=(await r.db.prepare('SELECT revision FROM catalog WHERE id=?').bind(p.id).first()).revision;
  assert.equal((await client.send(r.owner,route,{body})).status,200);assert.equal(client.metrics.recoveryAttempts,1);
  assert.equal((await r.db.prepare('SELECT revision FROM catalog WHERE id=?').bind(p.id).first()).revision,beforeLoss+1);
  r.mf.dispatchFetch=original;
  const usage=await r.apiMeter();assert.equal(Object.values(usage.byTable).reduce((n,t)=>n+t.rowsWritten,0),usage.rowsWritten);
  assert.ok((await r.apiMeter()).maxQueries<=50);
 }finally{await r.close();}
});
async function capture(r,chain,label,{pause=false}={}){
 const snapshot=await startIncrement(r,'proof-shop'),directory=path.join(r.directory,label),secondCopy=directory+'-copy';
 if(pause){assert.equal((await exportChunks(r,{snapshot,directory,passphrase,stopAfter:1})).complete,false);}
 const result=await exportChunks(r,{snapshot,directory,passphrase});await copyChunks(directory,secondCopy,passphrase);chain.push({directory,secondCopy});
 const restored=await restoreChain({chain,passphrase,bindings:r.bindings,ownerConfirmed:true});
 const close=restored.close;let closed=false;restored.close=async()=>{if(!closed){closed=true;await close();}};(r.archiveRestores??=[]).push(restored);
 return {snapshot,result,restored,chain,directory,secondCopy};
}
test('incremental baseline and changed-only chain resumes capture/cache advancement, restores all fields and quarantines authority',async()=>{
 let r=await pairedFixture();const chain=[];let sourceSqlBeforeRestart;try{
  await r.db.prepare("INSERT INTO public_config VALUES('foreign-shop','{}')").run();
  const first=await capture(r,chain,'baseline',{pause:true});
  await assert.rejects(startIncrement(r,'proof-shop'),/pending/);
  const partial=await acknowledgeIncrement(r,{...first,passphrase,ownerConfirmed:true,stopAfterPages:1});assert.equal(partial.complete,false);
  sourceSqlBeforeRestart=await r.fixtureSqlMeter();await first.restored.close();await r.close();
  // A new workerd runtime opens the same disposable D1 after the partial cache
  // transaction. Owner restore verification is repeated, never serialized as a
  // client-provided boolean or fabricated receipt.
  r={...r,...await runtime({directory:r.directory,initialise:false,bindings:r.bindings,pooledTransport:true})};
  assert.deepEqual(await resumeIncrement(r,'proof-shop'),first.snapshot);
  const reverified=await restoreChain({chain,passphrase,bindings:r.bindings,ownerConfirmed:true});
  try{await acknowledgeIncrement(r,{...first,restored:reverified,passphrase,ownerConfirmed:true});}finally{await reverified.close();}
  await assert.rejects(r.db.prepare("UPDATE catalog SET doc='{}' WHERE id='p00000'").run(),/revision/);
  const made=await call(r,'orders',{body:draft()});assert.equal(made.status,201);
  // Permanent receipts, replay metadata and a nonce cannot be silently omitted.
  await r.db.prepare('INSERT INTO pair_operations VALUES(?,?,?,?,?,?,?)').bind('proof-shop',r.worker.deviceId,crypto.randomUUID(),'hash',made.body.id,'{"retained":true}',Date.now()).run();
  await assert.rejects(r.db.prepare("UPDATE pair_operations SET result='{}'").run(),/append-only/);
  await assert.rejects(r.db.prepare('DELETE FROM pair_operations').run(),/Permanent/);
  await assert.rejects(r.db.prepare("UPDATE orders SET doc='{}' WHERE id=?").bind(made.body.id).run(),/revision/);
  await r.db.prepare('INSERT INTO auth_nonces VALUES(?,?,?,?)').bind(r.worker.deviceId,crypto.randomUUID(),4102444800,'proof-shop').run();
  const nonceCount=(await r.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n;
  const second=await capture(r,chain,'increment-1',{pause:true});
  const m=JSON.parse(await readFile(path.join(second.directory,'manifest.json')));assert.equal(m.chain.generation,2);assert.equal(m.chunks.filter(c=>c.table==='catalog').length,0);
  await r.db.prepare('UPDATE orders SET revision=revision+1 WHERE id=?').bind(made.body.id).run();
  assert.equal((await second.restored.db.prepare('SELECT revision FROM orders WHERE id=?').bind(made.body.id).first()).revision,1);
  assert.equal((await second.restored.db.prepare('SELECT COUNT(*) n FROM pair_operations').first()).n,1);
  assert.equal((await second.restored.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n,nonceCount);
  assert.equal((await second.restored.db.prepare("SELECT COUNT(*) n FROM pair_devices WHERE status='ACTIVE'").first()).n,0);
  assert.equal((await second.restored.db.prepare("SELECT COUNT(*) n FROM public_config WHERE shop='foreign-shop'").first()).n,0);
  await acknowledgeIncrement(r,{...second,passphrase,ownerConfirmed:true});await second.restored.close();
  const third=await capture(r,chain,'increment-2');assert.equal((await third.restored.db.prepare('SELECT revision FROM orders WHERE id=?').bind(made.body.id).first()).revision,2);
  await assert.rejects(acknowledgeIncrement(r,{...third,passphrase,ownerConfirmed:false}));
  await assert.rejects(restoreChain({chain:chain.slice(1),passphrase,bindings:r.bindings,ownerConfirmed:true}));
  await acknowledgeIncrement(r,{...third,passphrase,ownerConfirmed:true});await third.restored.close();
  assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM local_export_rows').first()).n,0);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,1);
  const damaged=JSON.parse(await readFile(path.join(third.directory,'manifest.json')));await writeFile(path.join(third.secondCopy,damaged.chunks[0].file),'corrupt');
  await assert.rejects(restoreChain({chain,passphrase,bindings:r.bindings,ownerConfirmed:true}));
  const cache=(await r.db.prepare('SELECT table_name,COUNT(*) rows,SUM(length(CAST(doc AS BLOB))) markerBytes FROM archive_i_cache GROUP BY table_name').all()).results;
  const proof={status:'PASS',baseline:first.result,increment:second.result,changed:third.result,sourceSqlBeforeRestart,sourceSqlAfterRestart:await r.fixtureSqlMeter(),cache,production:false,independentPhysicalMedia:false};
  await writeFile(path.join(root,'evidence','phase4i-archive-proof-'+path.basename(r.directory)+'.json'),JSON.stringify(proof,null,2),{flag:'wx'});
 }finally{for(const restored of r.archiveRestores??[])await restored.close();await r.close();}
});
test('native local D1 metadata measures composite primary-key index amplification without rowid',async()=>{
 const r=await pairedFixture();try{const results={};
  for(const [name,suffix] of [['regular',''],['clustered',' WITHOUT ROWID']]){
   await r.db.prepare(`CREATE TABLE synthetic_${name}(shop TEXT,id TEXT,doc TEXT,PRIMARY KEY(shop,id))${suffix}`).run();let written=0;
   for(let start=0;start<100;start+=25){const rows=await r.db.batch(Array.from({length:25},(_,i)=>r.db.prepare(`INSERT INTO synthetic_${name} VALUES(?,?,?)`).bind('proof-shop',String(start+i),'synthetic')));written+=rows.reduce((n,x)=>n+x.meta.rows_written,0);}
   const whole=await r.db.prepare(`DELETE FROM synthetic_${name}`).run();
   for(let start=0;start<100;start+=25)await r.db.batch(Array.from({length:25},(_,i)=>r.db.prepare(`INSERT INTO synthetic_${name} VALUES(?,?,?)`).bind('proof-shop',String(start+i),'synthetic')));
   const deleted=await r.db.prepare(`DELETE FROM synthetic_${name} WHERE shop=?`).bind('proof-shop').run();results[name]={insertWrites:written,wholeTableDeleteWrites:whole.meta.rows_written,filteredDeleteWrites:deleted.meta.rows_written};
  }
  await writeFile(path.join(root,'evidence','phase4i-index-proof-'+path.basename(r.directory)+'.json'),JSON.stringify({status:'MEASURED',results,providerMeasurement:false},null,2),{flag:'wx'});
  assert.ok(results.clustered.insertWrites<results.regular.insertWrites,JSON.stringify(results));
  assert.ok(results.clustered.filteredDeleteWrites<=results.regular.filteredDeleteWrites,JSON.stringify(results));
  assert.ok(results.clustered.insertWrites+results.clustered.filteredDeleteWrites<results.regular.insertWrites+results.regular.filteredDeleteWrites,JSON.stringify(results));
 }finally{await r.close();}
});
test('incremental capture is atomic on quota error and refuses a missing protected order',async()=>{
 const r=await pairedFixture(),chain=[];try{
  const made=await call(r,'orders',{body:draft()});assert.equal(made.status,201);
  const first=await capture(r,chain,'baseline');await acknowledgeIncrement(r,{...first,passphrase,ownerConfirmed:true});await first.restored.close();
  const before=await r.db.prepare('SELECT * FROM orders WHERE id=?').bind(made.body.id).first();
  await r.db.prepare("CREATE TRIGGER synthetic_i_quota BEFORE INSERT ON local_export_rows BEGIN SELECT RAISE(ABORT,'synthetic quota'); END").run();
  await r.db.prepare('UPDATE orders SET revision=revision+1 WHERE id=?').bind(made.body.id).run();
  await assert.rejects(startIncrement(r,'proof-shop'));assert.equal((await r.db.prepare('SELECT pending FROM archive_i_head').first()).pending,null);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM local_export_rows').first()).n,0);
  await r.db.prepare('DROP TRIGGER synthetic_i_quota').run();
  // A disposable fixture-only deletion proves the export refuses to bless lost
  // protected source data. No API or automatic cleanup can perform this delete.
  await r.db.prepare('DELETE FROM events WHERE order_id=?').bind(before.id).run();await r.db.prepare('DELETE FROM orders WHERE id=?').bind(before.id).run();
  await assert.rejects(startIncrement(r,'proof-shop'));assert.equal((await r.db.prepare('SELECT pending FROM archive_i_head').first()).pending,null);
 }finally{for(const restored of r.archiveRestores??[])await restored.close();await r.close();}
});
