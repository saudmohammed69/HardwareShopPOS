// Final local pairing schema workload. No provider or actual Android load claim.
import assert from 'node:assert/strict';
import {writeFile,copyFile,access} from 'node:fs/promises';
import {pairedFixture,installation} from './pairing-fixture.mjs';
import {recoveryClient} from './paired-recovery.mjs';
import {submitRecoverably,editRecoverably} from './customer-recovery.mjs';
import {call,seed,draft,token,root} from './runtime.mjs';
import {abuseFixtureBindings} from './customer-abuse-fixture.mjs';
import {exportOrders,compactVerified,measureStorage} from './retention.mjs';
import {startSnapshot,exportChunks,copyChunks,restoreChunks,cleanupSnapshot} from './phase4h-archive.mjs';
import path from 'node:path';
process.env.PROOF_POOLED_TRANSPORT='1';
const count=Number(process.argv.find(a=>a.startsWith('--orders='))?.slice(9)),lines=Number(process.argv.find(a=>a.startsWith('--lines='))?.slice(8)??20),concurrent=Number(process.argv.find(a=>a.startsWith('--concurrent='))?.slice(13)??20),devicesCount=Number(process.argv.find(a=>a.startsWith('--devices='))?.slice(10)??5),cycles=count===500?2:1;
assert.ok([200,300,400,500,1000].includes(count)&&[5,20].includes(lines)&&[20,100].includes(concurrent)&&[5,10].includes(devicesCount));
const label=process.argv.find(a=>a.startsWith('--label='))?.slice(8)??'';assert.ok(!label||/^[a-z0-9-]+$/.test(label));
const output=path.join(root,'evidence',`phase4h-capacity-${count}-${lines}-${concurrent}-${devicesCount}${label?'-'+label:''}.json`);
try{await access(output);throw Error('Preserve previous capacity result: '+output);}catch(e){if(e.code!=='ENOENT')throw e;}
// Scheduling fence BEFORE creating any fixture, grant, signature or DB. This
// permits isolated platform/native verification between unchanged cohorts.
const hold=path.join(root,'evidence',`phase4h-capacity-hold-${count}-${lines}-${concurrent}-${devicesCount}`);let announced=false;
while(true){try{await access(hold);}catch(e){if(e.code==='ENOENT')break;throw e;}if(!announced){console.log(JSON.stringify({phase:'paused-before-fixture',count,lines,concurrent,devicesCount}));announced=true;}await new Promise(resolve=>setTimeout(resolve,1000));}
const r=await pairedFixture({bindings:{...abuseFixtureBindings,PROOF_TRACE:'true'}}),client=recoveryClient(r),started=performance.now(),cpu=process.cpuUsage(),daily=[],completed=[],latencies=[];
const check=x=>{assert.ok(x.status<300,JSON.stringify(x));return x.body;};
async function pool(values,n,fn){let index=0;const results=await Promise.allSettled(Array.from({length:n},async()=>{while(index<values.length){const current=index++;await fn(values[current],current);}}));const failures=results.filter(x=>x.status==='rejected');if(failures.length){const error=failures[0].reason;error.additionalFailures=failures.slice(1).map(x=>({error:x.reason.stack,code:x.reason.code??x.reason.cause?.code}));throw error;}}
const send=async(device,route,options)=>{const t=performance.now(),reply=await client.send(device,route,options);latencies.push(performance.now()-t);return check(reply);};
const originalDb=r.db,archiveCost={rowsRead:0,rowsWritten:0,statements:0,peakAllocatedBytes:0};
function wrapDb(){const record=x=>{archiveCost.rowsRead+=x.meta.rows_read??0;archiveCost.rowsWritten+=x.meta.rows_written??0;archiveCost.peakAllocatedBytes=Math.max(archiveCost.peakAllocatedBytes,x.meta.size_after??0);archiveCost.statements++;return x;};const wrap=s=>({sql:s.sql,args:s.args,bind:(...args)=>wrap(s.bind(...args)),all:async()=>record(await s.all()),run:async()=>record(await s.run()),first:async()=>{const x=record(await s.all());return x.results[0]??null;}});r.db={prepare:sql=>wrap(originalDb.prepare(sql)),batch:async statements=>(await originalDb.batch(statements)).map(record)};}
let result,revocationProbes=0;
try{
 const devices=[r.owner,r.worker];while(devices.length<devicesCount){const d=installation();await r.pair(d);devices.push(d);}
 const products=await seed(r,3200),base=await measureStorage(r);for(let i=0;i<products.length;i+=40)await send(r.owner,'catalog/publish-batch',{body:{products:products.slice(i,i+40).map(product=>({product,published:true}))}});
 console.log(JSON.stringify({phase:'catalog-ready',count,lines,concurrent,devicesCount,products:products.length,directory:r.directory}));
 for(let cycle=0;cycle<cycles;cycle++){
  const before=await r.apiMeter(),at=performance.now();
  await pool(Array.from({length:count}),concurrent,async()=>{let after='',n=0;do{const p=await send(r.worker,'catalog?after='+after,{method:'GET'});n+=p.products.length;after=p.next??'';}while(after);assert.equal(n,3201);});
  await pool(Array.from({length:count}),concurrent,async(_,i)=>{
   const body=draft(lines,(i*lines)%(3200-lines));if(i===0)body.items[0]={productId:'pipe20',fullLengths:'1',extraFeet:'2.5'};
   const created=check(await submitRecoverably(r,body,{ip:'carrier-'+Math.floor(i/100)},client.metrics));
   const id=created.id,device=devices[i%devices.length];let revision=created.revision;
   if(i%10===0)assert.equal(check(await submitRecoverably(r,body,{abuse:false},client.metrics)).id,id);
   if(i>=count*.85&&i<count*.9){await send(device,`orders/${id}/cancel`,{body:{expectedRevision:revision,reason:'Synthetic unresolved cancellation'}});return;}
   if(i>=count*.9)return;
   if(i%10===0){const change={customer:body.customer,items:body.items,note:'Edited before acceptance'};revision=check(await editRecoverably(r,id,revision,change,body.capability,client.metrics)).revision;}
   revision=(await send(device,`orders/${id}/accept`,{body:{expectedRevision:revision}})).revision;
   if(i%10===1)revision=(await send(device,`orders/${id}/adjust`,{body:{expectedRevision:revision,items:body.items,reason:'Synthetic customer confirmed',customerConfirmed:true}})).revision;
   for(const action of ['prepare','ready'])revision=(await send(device,`orders/${id}/${action}`,{body:{expectedRevision:revision}})).revision;
   const claimToken=token(),operationId=crypto.randomUUID(),claimBody={expectedRevision:revision,claimToken};
   const claim=await send(device,`orders/${id}/billing-claim`,{operationId,body:claimBody});
   if(i%10===0)assert.equal((await send(device,`orders/${id}/billing-claim`,{operationId,body:claimBody})).billId,claim.billId);
   const completionId=crypto.randomUUID(),completion={expectedRevision:claim.revision,claimToken,billId:claim.billId,posBillSyncId:'synthetic-pos-sync-'+claim.billId};
   await send(device,`orders/${id}/complete`,{operationId:completionId,body:completion});
   if(i%10===0)await send(device,`orders/${id}/complete`,{operationId:completionId,body:completion});
   completed.push({id,billId:claim.billId});if(completed.length%100===0)console.log(JSON.stringify({phase:'orders',completed:completed.length}));
  });
  let maxCursor=0,maxSyncWall=0;
  for(const d of devices){let cursor=0;do{const t=performance.now(),p=await send(d,'pos/events?cursor='+cursor,{method:'GET'});maxSyncWall=Math.max(maxSyncWall,performance.now()-t);cursor=p.cursor;if(!p.events.length)break;}while(true);maxCursor=Math.max(maxCursor,cursor);}
  const polls=devicesCount*960,hints=Math.ceil(count*1.1)*devicesCount;
  await pool(Array.from({length:polls+hints}),100,async(_,i)=>send(devices[i%devices.length],'pos/events?cursor='+maxCursor,{method:'GET'}));
  const after=await r.apiMeter(),counts=await r.db.prepare("SELECT COUNT(*) n,SUM(status='COMPLETED') completed,SUM(status='CANCELLED') cancelled,SUM(status='NEW') active FROM orders").first();
  assert.equal(counts.n,count*(cycle+1));assert.equal(counts.completed,count*.85*(cycle+1));assert.equal(counts.cancelled,count*.05*(cycle+1));assert.equal(counts.active,count*.1*(cycle+1));
  daily.push({cycle:cycle+1,orders:count,requests:after.requests-before.requests,rowsRead:after.rowsRead-before.rowsRead,rowsWritten:after.rowsWritten-before.rowsWritten,foregroundPolls:polls,hintRefreshes:hints,elapsedMs:performance.now()-at,maxSyncWallMs:maxSyncWall,counts});console.log(JSON.stringify({phase:'cycle',...daily.at(-1)}));
 }
 const rejected=installation();await r.pair(rejected);await send(r.owner,'pair/revoke',{body:{deviceId:rejected.deviceId}});for(let i=0;i<100;i++){assert.equal((await client.send(rejected,'pos/events?cursor=0',{method:'GET'})).status,403);revocationProbes++;}
 const hot=await measureStorage(r),meter=await r.apiMeter();assert.ok(meter.maxQueries<=50);
 const protectedBefore=(await r.db.prepare("SELECT * FROM orders WHERE status IN ('NEW','CANCELLED') ORDER BY id").all()).results;
 assert.equal(protectedBefore.length,count*.15*cycles);
 wrapDb();
 const snapshot=await startSnapshot(r,'proof-shop'),directory=path.join(r.directory,'chunk-archive'),passphrase='Phase4H synthetic encrypted archive only';
 const archive=await exportChunks(r,{snapshot,directory,passphrase}),secondCopy=path.join(r.directory,'second-chunk-copy');await copyChunks(directory,secondCopy,passphrase);const restored=await restoreChunks({directory,secondCopy,passphrase,ownerConfirmed:true,bindings:r.bindings});await cleanupSnapshot(r,{snapshot,directory,secondCopy,passphrase,restored,ownerConfirmed:true});await restored.close();
 let archiveBytes=0;for(let i=0;i<completed.length;i+=12){const rows=completed.slice(i,i+12),attestations=Object.fromEntries(rows.map(x=>[x.id,{owner:'synthetic-owner',resolved:true,collected:true,billId:x.billId,reason:'Synthetic bill and pickup attested'}]));
  const a=await exportOrders(r,{ids:rows.map(x=>x.id),directory:path.join(r.directory,'order-archives'),passphrase:'Phase4G synthetic order archive only',attestations}),second=a.file+'.copy';await copyFile(a.file,second);archiveBytes+=a.encryptedBytes;
  await compactVerified(r,a,'Phase4G synthetic order archive only',{secondCopy:second,ownerConfirmed:true,now:Date.now()+3*86400000});}
 const afterArchive=await measureStorage(r),identities=await r.db.prepare('SELECT COUNT(*) n,COUNT(DISTINCT submission) submissions,COUNT(DISTINCT bill_id) bills FROM orders').first();assert.equal(identities.n,count*cycles);assert.equal(identities.submissions,count*cycles);assert.equal(identities.bills,completed.length);
 assert.deepEqual((await r.db.prepare("SELECT * FROM orders WHERE status IN ('NEW','CANCELLED') ORDER BY id").all()).results,protectedBefore);
 const usage=process.cpuUsage(cpu);latencies.sort((a,b)=>a-b);
 result={status:'PASS',createdAt:new Date().toISOString(),count,cycles,lines,concurrent,devicesCount,daily,base,hot,afterArchive,apiMeter:meter,archiveCost,archiveBytesPerCopy:archiveBytes,pairingArchive:archive,recovery:client.metrics,identities,protectedOrders:count*.15*cycles,
  requestWallP95Ms:latencies[Math.floor(latencies.length*.95)],elapsedMs:performance.now()-started,localNodeCpuMs:(usage.user+usage.system)/1000,providerCpuVerified:false,productionAuthVerified:false,actualFcmDelivered:0,modeledFcmSendsPerCycle:count*devicesCount*2,fixture:r.directory};
}catch(e){result={status:'FAIL',createdAt:new Date().toISOString(),count,cycles,lines,concurrent,devicesCount,daily,archiveCost,recovery:client.metrics,error:e.stack,additionalFailures:e.additionalFailures??[],fixture:r.directory};try{result.partialApiMeter=await r.apiMeter();result.partialStorage=await measureStorage(r);}catch(probe){result.partialProbeError=String(probe);}console.error(e);process.exitCode=1;}
finally{try{const trace=await r.lifecycle();await writeFile(output.replace('.json','-lifecycle.json'),JSON.stringify(trace));const denials=trace.server.filter(x=>x.stage==='auth-denied');result.lifecycle={clientEvents:trace.client.length,serverEvents:trace.server.length,authDenials:denials.length,expectedRevocationDenials:revocationProbes,workloadAuthDenials:denials.length-revocationProbes,expiredProofs:trace.server.filter(x=>x.reason==='PROOF_EXPIRED').length,maxProofAgeSeconds:trace.server.filter(x=>x.stage==='auth-verified').reduce((n,x)=>Math.max(n,x.ageSeconds),0)};}catch(e){result.traceError=String(e);}result.transport=r.fixtureTransport;result.seedMetrics=r.seedMetrics;await writeFile(output,JSON.stringify(result,null,2));console.log(JSON.stringify({phase:'final',status:result.status,fixture:r.directory}));await r.close();}
