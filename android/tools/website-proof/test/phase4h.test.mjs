import {test} from 'node:test';
import assert from 'node:assert/strict';
import {pairedFixture,installation,preparePaired} from '../pairing-fixture.mjs';
import {recoveryClient} from '../paired-recovery.mjs';
import {call,draft} from '../runtime.mjs';
import {sign,createHash} from 'node:crypto';
import {canonical} from '../pricing.mjs';
import {startSnapshot,exportChunks,copyChunks,restoreChunks,cleanupSnapshot} from '../phase4h-archive.mjs';
import {writeFile,readFile} from 'node:fs/promises';
import path from 'node:path';
import {settledSummary,preserveTransportEvidence} from '../transport-evidence.mjs';
test('100 concurrent signed operations trace actual send, fresh authentication and complete response without forced recycle',async()=>{
 const r=await pairedFixture({bindings:{PROOF_TRACE:'true'}});let outcomes=[],trace=null,client=null;try{await r.lifecycle();const clientStart=r.clientTraces.length;client=recoveryClient(r);outcomes=await Promise.allSettled(Array.from({length:100},async()=>{for(let i=0;i<10;i++)assert.equal((await client.send(r.worker,'pos/events?cursor=0',{method:'GET'})).status,200);}));
 assert.ok(outcomes.every(x=>x.status==='fulfilled'),JSON.stringify(outcomes.filter(x=>x.status==='rejected').map(x=>String(x.reason))));
 trace=await r.lifecycle();const verified=trace.server.filter(x=>x.stage==='auth-verified'),sent=trace.client.slice(clientStart).filter(x=>x.stage==='network-send');
 // A recovered socket loss is another actual send of the SAME logical read.
 // Count every attempt, while requiring all 1,000 identities and zero denials.
 assert.equal(new Set(verified.map(x=>x.operation)).size,1000);assert.equal(trace.server.filter(x=>x.stage==='auth-denied').length,0);assert.equal(new Set(sent.map(x=>x.operation)).size,1000);assert.equal(sent.length,1000+client.metrics.recoveryAttempts);assert.equal(client.metrics.unrecoverable,0);assert.equal(r.fixtureTransport.maxRequestsPerConnection,null);
 await writeFile(path.join(r.directory,'signed-read-result.json'),JSON.stringify({status:'PASS',logicalReads:1000,actualSends:sent.length,verifiedEvents:verified.length,recovery:client.metrics,authorizationDenials:0}));
 await writeFile(path.join(r.directory,'lifecycle.json'),JSON.stringify(trace));
 }finally{
  try{await preserveTransportEvidence(r,'signed-read',{outcomes:settledSummary(outcomes),trace,recovery:client?.metrics??null});}
  catch{console.error('Local signed-read evidence could not be fully saved; workload assertions remain unchanged.');}
  finally{await r.close();}
 }
});
test('staged rotation response loss recovers via new-key session and rejects old key without releasing claims',async()=>{
 const r=await pairedFixture();try{const next={...installation(),deviceId:r.worker.deviceId,databaseEpoch:r.worker.databaseEpoch},rotationId=crypto.randomUUID(),fingerprint=createHash('sha256').update(canonical(next.publicJwk)).digest('hex');
 const message=['HWPOS-PAIR-ROTATE-V1','proof-shop',next.deviceId,next.databaseEpoch,'1',rotationId,fingerprint].join('\n');
 const result=await r.send('pair/rotate',{device:r.worker,body:{rotationId,publicKey:next.publicJwk,newKeySignature:sign('sha256',Buffer.from(message),{key:next.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url')}});assert.equal(result.status,200);
 assert.equal((await r.send('pair/session',{device:r.worker,method:'GET'})).status,403);assert.equal((await r.send('pair/session',{device:next,method:'GET'})).status,200);
 assert.equal((await r.db.prepare("SELECT COUNT(*) n FROM pair_audit WHERE action='ROTATE'").first()).n,1);
 }finally{await r.close();}
});
test('expired proofs still fail the unchanged 60-second limit with a server-side denial reason',async()=>{
 const r=await pairedFixture({bindings:{PROOF_TRACE:'true'}});try{await r.lifecycle();const p=preparePaired(r.worker,'pos/events?cursor=0',{method:'GET',timestamp:Math.floor(Date.now()/1000)-62});assert.equal((await call(r,p.route,p.options)).status,403);const trace=await r.lifecycle();assert.ok(trace.server.some(x=>x.reason==='PROOF_EXPIRED'&&x.ageSeconds>=62));}finally{await r.close();}
});
test('tenant chunk archive exceeds 16 MiB, resumes stable snapshot, authenticates two copies and restores quarantined',async()=>{
 const r=await pairedFixture();try{
 const made=await call(r,'orders',{body:draft()});assert.equal(made.status,201);
 // Synthetic permanent receipts alone exceed the old whole-ledger ceiling.
 for(let i=0;i<170;i++)await r.db.batch(Array.from({length:32},(_,j)=>r.db.prepare('INSERT INTO pair_operations VALUES(?,?,?,?,?,?,?)').bind('proof-shop',r.worker.deviceId,crypto.randomUUID(),'synthetic-hash',made.body.id,JSON.stringify({padding:'x'.repeat(3300),n:i*32+j}),Date.now())));
 await r.db.prepare("INSERT INTO public_config VALUES('foreign-shop','{}')").run();
 const snapshot=await startSnapshot(r,'proof-shop'),directory=path.join(r.directory,'chunk-archive'),passphrase='Phase4H synthetic encrypted archive only';
 const first=await exportChunks(r,{snapshot,directory,passphrase,stopAfter:3});assert.equal(first.complete,false);
 await r.db.prepare("UPDATE orders SET revision=revision+1 WHERE id=?").bind(made.body.id).run();
 const completed=await exportChunks(r,{snapshot,directory,passphrase});assert.ok(completed.complete&&completed.plainBytes>16*1024*1024);
 const secondCopy=path.join(r.directory,'second-copy');await copyChunks(directory,secondCopy,passphrase);
 const restored=await restoreChunks({directory,secondCopy,passphrase,bindings:r.bindings,ownerConfirmed:true});try{assert.equal((await restored.db.prepare('SELECT revision FROM orders WHERE id=?').bind(made.body.id).first()).revision,1);assert.equal((await restored.db.prepare("SELECT COUNT(*) n FROM public_config WHERE shop='foreign-shop'").first()).n,0);assert.equal(restored.report.counts.pair_operations,5440);assert.equal((await restored.db.prepare("SELECT COUNT(*) n FROM pair_devices WHERE status='ACTIVE'").first()).n,0);
 await assert.rejects(cleanupSnapshot(r,{snapshot,directory,secondCopy,passphrase,restored,ownerConfirmed:false}));
 await cleanupSnapshot(r,{snapshot,directory,secondCopy,passphrase,restored,ownerConfirmed:true});assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM local_export_rows').first()).n,0);assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM pair_operations').first()).n,5440);assert.equal((await r.db.prepare('SELECT revision FROM orders WHERE id=?').bind(made.body.id).first()).revision,2);
 }finally{await restored.close();}
 const manifest=JSON.parse(await readFile(path.join(directory,'manifest.json'))),file=path.join(secondCopy,manifest.chunks[0].file);await writeFile(file,'damaged');await assert.rejects(restoreChunks({directory,secondCopy,passphrase,bindings:r.bindings,ownerConfirmed:true}));
 await writeFile(path.join(r.directory,'archive-test-result.json'),JSON.stringify({status:'PASS',...completed,twoIndependentFileCopies:true,independentPhysicalMedia:false,peakRssBytes:process.resourceUsage().maxRSS*1024}));
 }finally{await r.close();}
});
test('archive cutoff and snapshot persistence failure preserve source orders and permanent receipts',async()=>{
 const r=await pairedFixture();try{const made=await call(r,'orders',{body:draft()}),before=await r.db.prepare('SELECT * FROM orders WHERE id=?').bind(made.body.id).first(),real=r.db;
 r.db={...real,prepare:sql=>sql==='SELECT id FROM orders LIMIT 1'?{all:async()=>({meta:{size_after:450000000},results:[]})}:real.prepare(sql)};
 await assert.rejects(startSnapshot(r,'proof-shop'),/cutoff/);r.db=real;
 await r.db.prepare('CREATE TABLE IF NOT EXISTS local_export_rows(snapshot TEXT,table_name TEXT,seq INTEGER,doc TEXT,PRIMARY KEY(snapshot,table_name,seq))').run();
 await r.db.prepare("CREATE TRIGGER synthetic_quota_failure BEFORE INSERT ON local_export_rows WHEN new.table_name='orders' BEGIN SELECT RAISE(ABORT,'synthetic quota exhausted'); END").run();
 await assert.rejects(startSnapshot(r,'proof-shop'));assert.equal((await r.db.prepare('SELECT COUNT(*) n FROM local_export_rows').first()).n,0);assert.deepEqual(await r.db.prepare('SELECT * FROM orders WHERE id=?').bind(made.body.id).first(),before);
 }finally{await r.close();}
});
