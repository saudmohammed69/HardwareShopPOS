import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {copyFile,mkdir,writeFile,readFile} from 'node:fs/promises';
import path from 'node:path';
import {runtime as unsignedRuntime,seed,draft,call as fixtureCall,token,root} from './runtime.mjs';
import {signedFixture,newDevice,fingerprint} from './security-fixture.mjs';
import {sign} from 'node:crypto';
import {exportSecurity,restoreSecurity} from './security-archive.mjs';
process.env.PROOF_POOLED_TRANSPORT='1'; // Includes isolated archive restore fixtures.
async function runtime(){return signedFixture();}
async function call(rt,route,options={}){
 if(!options.auth){
  // Submission alone has a durable, server-verified idempotency identity. A
  // dropped connection may conceal a commit: retry EXACTLY the same cart/key,
  // never an edit/status mutation and never an application error response.
  for(let attempt=0;;attempt++)try{return await fixtureCall(rt,route,options);}catch(error){
   const code=error.cause?.code??error.code;
   if(route!=='orders'||!options.body?.submissionId||!['UND_ERR_SOCKET','ECONNRESET'].includes(code)||attempt>=2)throw error;
   rt.lostSubmissionTransportAttempts=(rt.lostSubmissionTransportAttempts??0)+1;
   console.log(JSON.stringify({phase:'stable-submission-network-retry',attempt:attempt+1,code}));
  }
 }
 const {auth,...rest}=options;
 const index=auth.startsWith('signed-')?Number(auth.slice(7)):auth.endsWith('-b')?1:0;
 const uid=auth.includes('owner')?'synthetic-owner':(rt.capacityUids?.[index]??'synthetic-worker');
 const device=auth.includes('owner')?rt.owner:(rt.capacityDevices?.[index]??rt.worker);
 return rt.send(route,{...rest,uid,device});
}
import {priceLine} from './pricing.mjs';
import {exportOrders,compactVerified,measureStorage} from './retention.mjs';

const evidence=path.join(root,'evidence');await mkdir(evidence,{recursive:true});
const check=result=>{assert.ok(result.status<300,JSON.stringify({status:result.status,body:result.body}));return result.body;};
async function pool(values,concurrency,fn){let index=0;await Promise.all(Array.from({length:concurrency},async()=>{while(index<values.length){const current=index++;await fn(values[current],current);}}));}
const percentile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))]??0;};
async function dbSize(rt){const result=await rt.db.prepare('SELECT id FROM catalog LIMIT 1').all();assert.ok(Number.isFinite(result.meta.size_after));return result.meta.size_after;}
function workerdCpuMs(){
 if(process.platform!=='win32')return null;
 const script=`$taskChildren=Get-CimInstance Win32_Process -Filter "ParentProcessId=${process.pid}" | Where-Object Name -EQ 'workerd.exe'; $taskCpu=0; foreach($taskChild in $taskChildren){$taskCpu+=(Get-Process -Id $taskChild.ProcessId).TotalProcessorTime.TotalMilliseconds}; [Console]::Write($taskCpu)`;
 const result=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',script],{encoding:'utf8',windowsHide:true,timeout:15000});
 const value=Number(result.stdout?.trim());return result.status===0&&Number.isFinite(value)?value:null;
}
async function publish(rt,products,batchSize=40){let batches=0;for(let start=0;start<products.length;start+=batchSize){const body={products:products.slice(start,start+batchSize).map(product=>({product,published:true}))};check(await call(rt,'catalog/publish-batch',{auth:'fixture-owner-a',body}));batches++;}return batches;}
function maxProducts(count=25){return Array.from({length:count},(_,i)=>({id:`maximum-size-variant-${String(i).padStart(3,'0')}`,
 name:`Hardware product ${String(i).padStart(3,'0')} ${'N'.repeat(160)}`.slice(0,200),category:'C'.repeat(100),type:'T'.repeat(100),
 size:`${i}`+'S'.repeat(100-String(i).length),unit:'Piece'.padEnd(40,'U'),aliases:'A'.repeat(1000),sellingPricePaisa:String(1000+i),gstRateBp:1800,isPipe:false}));}
function maxOrder(products){return {submissionId:crypto.randomUUID(),capability:token(),customer:{name:'C'.repeat(100),phone:'9876543210'},
 items:products.map(product=>({productId:product.id,quantity:'1',note:'L'.repeat(300)})),note:'N'.repeat(1000),missingRequest:'M'.repeat(1000),pickupAt:null};}
async function maximumInputProbe(){
 const rt=await runtime();try{
  const seeded=await seed(rt,1),large=maxProducts();await publish(rt,seeded,40);await publish(rt,large,10);
  const body=maxOrder(large),json=JSON.stringify(body),bytes=Buffer.byteLength(json);assert.ok(bytes<32768);
  const acceptedRaw=json+' '.repeat(32768-bytes),acceptedBytes=Buffer.byteLength(acceptedRaw);assert.equal(acceptedBytes,32768);
  const accepted=check(await call(rt,'orders',{raw:acceptedRaw,ip:'max-permitted-input'}));
  const retry=check(await call(rt,'orders',{raw:acceptedRaw,ip:'max-permitted-input'}));assert.equal(retry.id,accepted.id);
  const overBody={...body,submissionId:crypto.randomUUID(),capability:token()},overJson=JSON.stringify(overBody);
  const rejectedRaw=overJson+' '.repeat(32769-Buffer.byteLength(overJson));assert.equal(Buffer.byteLength(rejectedRaw),32769);
  assert.equal((await call(rt,'orders',{raw:rejectedRaw,ip:'over-limit-input'})).status,413);
  const pipe=seeded.find(product=>product.id==='pipe20'),pipeOrder=draft(1);pipeOrder.items=[{productId:'pipe20',fullLengths:'999999999',extraFeet:'999999999.99'}];
  const pipeResult=check(await call(rt,'orders',{body:pipeOrder,ip:'max-pipe-values'}));
  assert.equal(pipeResult.totalPaisa,priceLine(pipe,pipeOrder.items[0]).estimatedLineTotalPaisa);
  assert.equal((await rt.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,2);
  const result={requests:rt.metrics.requests,rowsRead:rt.metrics.read,rowsWritten:rt.metrics.written,
   allocatedStorageBytes:await dbSize(rt),customerRequestBytesAccepted:acceptedBytes,customerRequestBytesRejected:32769,
   maxLineCount:25,maxCustomerNameChars:100,maxOrderNoteChars:1000,maxMissingRequestChars:1000,maxLineNoteChars:300,
   maxPipeFullLengths:'999999999',maxPipeExtraFeet:'999999999.99',maxPipeTotalPaisa:pipeResult.totalPaisa,
   duplicateMaxSubmissionReturnsSameOrder:true,overLimitCreatesNoOrder:true};
  return result;
 }finally{await rt.close();}
}
async function maximumAdjustmentProbe(){
 const rt=await runtime();try{
  const products=maxProducts(),catalog=await seed(rt,1);await publish(rt,catalog,40);await publish(rt,products,10);
  const body=maxOrder(products),created=check(await call(rt,'orders',{body,ip:'max-adjustment-order'}));
  let revision=check(await call(rt,`orders/${created.id}/accept`,{auth:'fixture-worker-a',body:{expectedRevision:created.revision}})).revision;
  const items=body.items.map(item=>({...item,quantity:'2'}));let acceptedAdjustments=0,rejection=null;
  for(let i=0;i<21;i++){
   const response=await call(rt,`orders/${created.id}/adjust`,{auth:'fixture-worker-a',body:{expectedRevision:revision,items,
    reason:'Customer approved replacement details '.padEnd(300,'R'),customerConfirmed:true}});
   if(response.status===409){rejection=response.body.error;break;}
   assert.equal(response.status,200,response.body.error);revision=response.body.revision;acceptedAdjustments++;
  }
  const row=await rt.db.prepare('SELECT doc,revision,status FROM orders WHERE id=?').bind(created.id).first(),doc=JSON.parse(row.doc);
  const docBytes=Buffer.byteLength(row.doc);assert.ok(docBytes<=96000);assert.equal(doc.adjustments.length,acceptedAdjustments);assert.ok(rejection);
  return {maxLineCount:25,maxLineNoteChars:300,maxReasonChars:300,documentLimitBytes:96000,
   maxAcceptedConfirmedAdjustments:acceptedAdjustments,finalDocumentBytes:docBytes,finalRevision:row.revision,
   finalStatus:row.status,rejectedReason:rejection,requests:rt.metrics.requests,rowsRead:rt.metrics.read,rowsWritten:rt.metrics.written,
   allocatedStorageBytes:await dbSize(rt)};
 }finally{await rt.close();}
}
function wrapDb(rt,totals){
 const original=rt.db,record=result=>{totals.rowsRead+=result.meta?.rows_read??0;totals.rowsWritten+=result.meta?.rows_written??0;totals.statements++;return result;};
 const wrap=statement=>({sql:statement.sql,args:statement.args,bind:(...values)=>wrap(statement.bind(...values)),
  all:async()=>record(await statement.all()),run:async()=>record(await statement.run()),
  first:async()=>{const result=record(await statement.all());return result.results[0]??null;}});
 rt.db={prepare:sql=>wrap(original.prepare(sql)),batch:async statements=>(await original.batch(statements)).map(record)};
}
async function indexOverhead(){
 const rt=await runtime();try{
  await rt.db.prepare('CREATE TABLE phase4a_probe_plain(id INTEGER PRIMARY KEY,shop TEXT,revision INTEGER,payload TEXT)').run();
  await rt.db.prepare('CREATE TABLE phase4a_probe_indexed(id INTEGER PRIMARY KEY,shop TEXT,revision INTEGER,payload TEXT)').run();
  await rt.db.prepare('CREATE INDEX phase4a_probe_lookup ON phase4a_probe_indexed(shop,revision)').run();
  const results={};for(const table of ['phase4a_probe_plain','phase4a_probe_indexed']){
   let insertRowsWritten=0,updateRowsWritten=0;
   for(let start=0;start<1000;start+=100){const batch=await rt.db.batch(Array.from({length:100},(_,i)=>rt.db.prepare(`INSERT INTO ${table} VALUES(?,?,?,?)`).bind(start+i,'shop',1,'synthetic-index-probe')));
    insertRowsWritten+=batch.reduce((n,x)=>n+x.meta.rows_written,0);}
   updateRowsWritten=(await rt.db.prepare(`UPDATE ${table} SET revision=2 WHERE shop='shop'`).run()).meta.rows_written;
   results[table]={insertRowsWritten,updateRowsWritten};
  }
  const plan=(await rt.db.prepare('EXPLAIN QUERY PLAN SELECT * FROM phase4a_probe_indexed WHERE shop=? AND revision=?').bind('shop',2).all()).results;
  results.indexedQueryPlan=plan;results.indexedRowsWrittenOverhead=(results.phase4a_probe_indexed.insertRowsWritten+results.phase4a_probe_indexed.updateRowsWritten)-
   (results.phase4a_probe_plain.insertRowsWritten+results.phase4a_probe_plain.updateRowsWritten);
  return results;
 }finally{await rt.close();}
}
const summaries=[];
const selectedProfile=process.argv.find(x=>x.startsWith('--profile='))?.slice(10);
if(selectedProfile&&!/^(5|20),(20|100),(5|10)$/.test(selectedProfile))throw Error('Use --profile=lines,concurrency,devices');
for(const lines of [5,20])for(const concurrency of [20,100])for(const deviceCount of [5,10]){
 if(process.argv.includes('--collect')||(selectedProfile&&selectedProfile!==[lines,concurrency,deviceCount].join(',')))continue;
 const rt=await runtime();try{
  const scenarioStarted=performance.now(),scenarioNodeCpuStart=process.cpuUsage(),scenarioWorkerdCpuStart=workerdCpuMs();
  console.log(JSON.stringify({phase:'start',lines,concurrency,deviceCount}));
  rt.capacityDevices=[rt.worker];rt.capacityUids=['synthetic-worker'];let trustedProvisioningWrites=0;
  // Separate administrator fixtures establish individual identities; owner routes
  // approve links and devices. Count out-of-band provisioning separately.
  for(let i=1;i<deviceCount-1;i++){
   const uid='capacity-individual-'+i,until=Math.floor(Date.now()/1000)+3600;
   const provision=await rt.db.batch([rt.db.prepare('INSERT INTO auth_users(uid,checked_until) VALUES(?,?)').bind(uid,until),
    rt.db.prepare("INSERT INTO auth_identity_bindings VALUES(?,?,'INDIVIDUAL',?)").bind(uid,'person-'+uid,until)]);
   trustedProvisioningWrites+=provision.reduce((n,r)=>n+r.meta.rows_written,0);
   check(await rt.send('auth/approve-worker',{uid:'synthetic-owner',device:rt.owner,body:{uid,workerRef:'shop-worker-'+i}}));
   const d=rt.enrollmentDevice(),pending=check(await rt.send('auth/enroll',{uid,device:d,body:{requestId:crypto.randomUUID(),publicKey:d.publicJwk}}));
   check(await rt.send('auth/approve',{uid:'synthetic-owner',device:rt.owner,body:{deviceId:pending.deviceId,expectedVersion:pending.version}}));
   rt.capacityDevices.push({...d,deviceId:pending.deviceId});rt.capacityUids.push(uid);
  }
  rt.capacityDevices.push(rt.owner);rt.capacityUids.push('synthetic-owner');
  const products=await seed(rt,3200),largest=maxProducts(25);let beforeCatalog=await dbSize(rt);
  const publicationStart=rt.metrics.requests;
  const publishedBatches=await publish(rt,products,40)+await publish(rt,largest,10);
  const publishedCount=products.length+largest.length,afterCatalog=await dbSize(rt),catalogPublication={batches:publishedBatches,products:publishedCount,
   requests:rt.metrics.requests-publicationStart,rowsRead:rt.metrics.read,rowsWritten:rt.metrics.written,seedRowsWritten:rt.seedMetrics.written};
  let catalogReadRows=0,catalogReadRequests=0,catalogVisitors=0;
  const browseCohort=concurrency;
  const browseStartRead=rt.metrics.read,browseStartRequests=rt.metrics.requests;
  await pool(Array.from({length:browseCohort},(_,i)=>i),Math.min(concurrency,browseCohort),async()=>{
   let after='',pages=0,count=0;do{const page=check(await call(rt,`catalog?after=${after}`,{method:'GET'}));count+=page.products.length;after=page.next??'';pages++;}while(after);
   assert.equal(count,publishedCount);catalogVisitors++;
  });
  catalogReadRows=rt.metrics.read-browseStartRead;catalogReadRequests=rt.metrics.requests-browseStartRequests;
  console.log(JSON.stringify({phase:'catalog-ready',lines,concurrency,deviceCount,publishedCount}));
  const start=performance.now(),nodeCpuStart=process.cpuUsage(),requestStart=rt.metrics.latencies.length;
  const bodies=Array.from({length:1000},(_,i)=>{const body=draft(lines,(i*lines)%(3200-lines));
   if(i===0)body.items[0]={productId:'pipe20',fullLengths:'1',extraFeet:'2.5'};return body;});
  let completed=0,cancelled=0,customerCancelled=0,adjusted=0,lostResponseRecoveries=0,duplicateChecks=0,billingRetries=0;
  await pool(bodies,concurrency,async(body,i)=>{
   const created=check(await call(rt,'orders',{body}));let revision=created.revision;
   if(i%10===0){const duplicate=check(await call(rt,'orders',{body}));assert.equal(duplicate.id,created.id);duplicateChecks++;}
   if(i%20===0){const recovered=check(await call(rt,'recover',{body:{submissionId:body.submissionId,capability:body.capability}}));assert.equal(recovered.id,created.id);lostResponseRecoveries++;}
   if(i%10===0){const items=body.items.map((item,index)=>index===0&&item.fullLengths!==undefined?{...item,fullLengths:'2'}:
    index===0?{...item,quantity:'2'}:item);
    const edit={customer:body.customer,items,note:'Customer edited before acceptance',missingRequest:body.missingRequest};
    revision=check(await call(rt,`orders/${created.id}/edit`,{body:{expectedRevision:revision,order:edit},capability:body.capability})).revision;}
   if(i>=850&&i<875){check(await call(rt,`orders/${created.id}/customer-cancel`,{body:{expectedRevision:revision},capability:body.capability}));customerCancelled++;cancelled++;return;}
   if(i>=875&&i<900){check(await call(rt,`orders/${created.id}/cancel`,{auth:'fixture-worker-a',body:{expectedRevision:revision,reason:'Synthetic unresolved customer cancellation'}}));cancelled++;return;}
   if(i>=900)return; // Leave 100 NEW orders uncollected and protected.
   const auth='signed-'+(i%deviceCount);
   revision=check(await call(rt,`orders/${created.id}/accept`,{auth,body:{expectedRevision:revision}})).revision;
   if(i%10===1){const items=body.items.map((item,index)=>index===0?{...item,quantity:'2'}:item);
    revision=check(await call(rt,`orders/${created.id}/adjust`,{auth,body:{expectedRevision:revision,items,
     reason:'Customer confirmed the substitute size',customerConfirmed:true}})).revision;adjusted++;}
   for(const action of ['prepare','ready'])revision=check(await call(rt,`orders/${created.id}/${action}`,{auth,body:{expectedRevision:revision}})).revision;
   const claimToken=token(),claim=check(await call(rt,`orders/${created.id}/billing-claim`,{auth,body:{expectedRevision:revision,claimToken}}));
   if(i%50===0){const repeat=check(await call(rt,`orders/${created.id}/billing-claim`,{auth,body:{expectedRevision:claim.revision,claimToken}}));assert.equal(repeat.billId,claim.billId);billingRetries++;}
   check(await call(rt,`orders/${created.id}/complete`,{auth,body:{expectedRevision:claim.revision,claimToken,billId:claim.billId,posBillSyncId:`pos-sync-${claim.billId}`}}));
   if(i%10===0)check(await call(rt,`orders/${created.id}/complete`,{auth,body:{expectedRevision:claim.revision,claimToken,billId:claim.billId}}));
   completed++;if(completed%100===0)console.log(JSON.stringify({phase:'orders',lines,concurrency,deviceCount,completed}));
  });
  // A revoked enrolled installation cannot refresh; fresh reinstall needs owner approval.
  const d=rt.enrollmentDevice(),pending=check(await rt.send('auth/enroll',{device:d,body:{requestId:crypto.randomUUID(),publicKey:d.publicJwk}}));
  check(await rt.send('auth/approve',{uid:'synthetic-owner',device:rt.owner,body:{deviceId:pending.deviceId,expectedVersion:1}}));
  const enrolled={...d,deviceId:pending.deviceId};
  const next=newDevice(),nonce=crypto.randomUUID(),message=['HWPOS-ROTATE-V1',enrolled.deviceId,'2',fingerprint(next.publicJwk),nonce].join('\n');
  check(await rt.send('auth/rotate',{device:enrolled,nonce,body:{publicKey:next.publicJwk,rotationSignature:sign('sha256',Buffer.from(message),{key:next.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url')}}));
  check(await rt.send('auth/session',{method:'GET',device:{...next,deviceId:enrolled.deviceId,version:2}}));
  check(await rt.send('auth/revoke-device',{uid:'synthetic-owner',device:rt.owner,body:{deviceId:enrolled.deviceId}}));
  for(let i=0;i<100;i++)assert.equal((await rt.send('pos/events?cursor=0',{method:'GET',device:{...next,deviceId:enrolled.deviceId,version:2}})).status,403);
  const afterOrders=await dbSize(rt),orderElapsedMs=performance.now()-start,nodeCpu=process.cpuUsage(nodeCpuStart),orderLatencies=rt.metrics.latencies.slice(requestStart);
  let syncEvents=0,syncRequests=0,maxSyncDelayMs=0,cursorMax=0;
  for(const auth of Array.from({length:deviceCount},(_,i)=>'signed-'+i)){let cursor=0;while(true){const began=performance.now(),feed=check(await call(rt,`pos/events?cursor=${cursor}`,{method:'GET',auth}));
   maxSyncDelayMs=Math.max(maxSyncDelayMs,performance.now()-began);syncRequests++;syncEvents+=feed.events.length;const old=cursor;cursor=feed.cursor;cursorMax=Math.max(cursorMax,cursor);
   if(feed.events.length===0)break;if(syncRequests%10===0){const replay=check(await call(rt,`pos/events?cursor=${old}`,{method:'GET',auth}));assert.equal(replay.cursor,cursor);}}
  }
  const pollRequests=deviceCount*960; // 8-hour foreground, one sync/30 seconds/device, compressed locally.
  if(pollRequests){const devices=Array.from({length:deviceCount},(_,i)=>'signed-'+i);
   await pool(Array.from({length:pollRequests},(_,i)=>i),100,async i=>{check(await call(rt,`pos/events?cursor=${cursorMax}`,{method:'GET',auth:devices[i%devices.length]}));});}
  for(let i=0;i<deviceCount;i++){check(await call(rt,'pos/events?cursor=0',{method:'GET',auth:'signed-'+i}));check(await call(rt,`pos/events?cursor=${cursorMax}`,{method:'GET',auth:'signed-'+i}));}
  const ordersCount=await rt.db.prepare("SELECT COUNT(*) n,SUM(status='COMPLETED') completed,SUM(status='CANCELLED') cancelled,SUM(status='NEW') active,COUNT(DISTINCT submission) unique_submissions,COUNT(DISTINCT bill_id) unique_bills FROM orders").first();
  assert.equal(ordersCount.n,1000);assert.equal(ordersCount.unique_submissions,1000);assert.equal(ordersCount.completed,850);assert.equal(ordersCount.cancelled,50);assert.equal(ordersCount.active,100);assert.equal(ordersCount.unique_bills,850);
  const hotStorage=await measureStorage(rt);let archiveResult=null,archiveMetrics=null,archiveSetupRowsWritten=0;
  {
   const futureNow=Date.now()+11*60*1000,futureWindow=Math.floor(futureNow/600000),futureDay=Math.floor(futureNow/86400000);
   const setup=await rt.db.batch([
    rt.db.prepare('INSERT INTO admission(shop,bucket,window,count) VALUES(?,?,?,1)').bind('proof-shop','phase4a-current-window',futureWindow),
    rt.db.prepare('INSERT INTO daily_admission(shop,day,count) VALUES(?,?,?) ON CONFLICT(shop,day) DO UPDATE SET count=excluded.count').bind('proof-shop',futureDay,1),
    rt.db.prepare('INSERT INTO daily_admission(shop,day,count) VALUES(?,?,?) ON CONFLICT(shop,day) DO NOTHING').bind('proof-shop',futureDay-1,1)
   ]);archiveSetupRowsWritten=setup.reduce((n,x)=>n+x.meta.rows_written,0);
   archiveMetrics={rowsRead:0,rowsWritten:0,statements:0};wrapDb(rt,archiveMetrics);
   const completedRows=(await rt.db.prepare("SELECT id,bill_id FROM orders WHERE status='COMPLETED' ORDER BY id").all()).results;
   const allArchiveBytes=[];let compacted=0,expiredAdmissionRows=0,expiredDailyRows=0,storageAfterArchiveBeforeCounters=null;
   for(let offset=0;offset<completedRows.length;offset+=12){const selected=completedRows.slice(offset,offset+12),attestations={};
    for(const row of selected)attestations[row.id]={owner:'synthetic-owner',resolved:true,collected:true,billId:row.bill_id,reason:'Synthetic finalized POS bill and collection verified'};
    const archive=await exportOrders(rt,{ids:selected.map(row=>row.id),directory:path.join(rt.directory,'owner-exports'),
     passphrase:'Phase 4C signed local capacity proof only',attestations}),copy=archive.file+'.second-copy';await copyFile(archive.file,copy);allArchiveBytes.push(archive.encryptedBytes);
    const compact=await compactVerified(rt,archive,'Phase 4C signed local capacity proof only',{ownerConfirmed:true,secondCopy:copy,now:futureNow});
    compacted+=compact.compacted;expiredAdmissionRows+=compact.expiredAdmission.expiredAdmissionRows;expiredDailyRows+=compact.expiredAdmission.expiredDailyRows;
    if(offset===0)storageAfterArchiveBeforeCounters=compact.compactedStorage.bytes;
   }
   const securityArchive=await exportSecurity(rt,{directory:path.join(rt.directory,'security-exports'),passphrase:'Phase 4C synthetic security passphrase'}),securityCopy=securityArchive.file+'.copy';await copyFile(securityArchive.file,securityCopy);
   const securityRestore=await restoreSecurity(securityArchive,'Phase 4C synthetic security passphrase',{secondCopy:securityCopy,ownerConfirmed:true,bindings:rt.bindings});await securityRestore.close();
   // Test-only next-window projection: all API calls are finished. Measure the real D1 cost of deleting the remaining proof metadata after its acceptance window, as a next-day mutation would. No application clock/replay policy is changed.
   const expiredProofs=await rt.db.prepare('DELETE FROM auth_nonces WHERE expires_at<?').bind(Math.floor(futureNow/1000)).run();
   const postArchive=await measureStorage(rt),online=await rt.db.prepare("SELECT COUNT(*) orders,COALESCE(SUM(length(CAST(doc AS BLOB))),0) docBytes,SUM(status='COMPLETED' AND json_extract(doc,'$.archiveRef') IS NOT NULL) archived FROM orders").first();
   const remainingEvents=await rt.db.prepare('SELECT COUNT(*) n FROM events').first();
   assert.equal(compacted,850);assert.equal(online.archived,850);assert.equal(online.orders,1000);
   assert.equal((await rt.db.prepare('SELECT COUNT(*) n FROM admission').first()).n,1);
   const hotBytes=hotStorage.bytes,baseBytes=afterCatalog,finalBytes=postArchive.bytes;
   const archivedGrowth=finalBytes-baseBytes;
   archiveResult={securityArchive,nextWindowNonceCleanup:{rows:expiredProofs.meta.changes,rowsRead:expiredProofs.meta.rows_read,rowsWritten:expiredProofs.meta.rows_written,simulation:'Trusted time advances 11 minutes after all API calls; included in source D1 totals to avoid ignoring next-day tail nonce deletion cost'},completedOrdersArchived:compacted,encryptedArchiveBytes:allArchiveBytes.reduce((a,b)=>a+b,0),archiveCopies:allArchiveBytes.length*2,
    expiredAdmissionRowsPruned:expiredAdmissionRows,expiredDailyRowsPruned:expiredDailyRows,
    archiveSetupRowsWritten,sourceD1ArchiveCleanup:{rowsRead:archiveMetrics.rowsRead,rowsWritten:archiveMetrics.rowsWritten,statements:archiveMetrics.statements},
    storageBeforeArchive:hotStorage,storageAfterFirstArchiveBeforeAbusePruneBytes:storageAfterArchiveBeforeCounters,
    storageAfterArchive:postArchive,retainedOnlineOrders:online.orders,retainedArchiveReferences:online.archived,
    unresolvedCancelledOrdersRetained:50,uncollectedNewOrdersRetained:100,remainingEventRows:remainingEvents.n,
    model:{basis:'Linear repetition of this measured one-day cohort with 100 uncollected NEW and 50 unresolved cancelled orders retained per day. Not a physical multi-day API run. Do not infer per-order archive savings: hot-to-final bytes also include expired admission and nonce cleanup.',
     initialCatalogBytes:baseBytes,hotFullOrderBytes:hotBytes,postArchiveBytes:finalBytes,observedGrowthBytes:archivedGrowth,
     repeatedCohort:[30,60,90].map(days=>({days,databaseBytes:baseBytes+days*archivedGrowth,newUncollectedOrders:days*100,unresolvedCancelledOrders:days*50})),
     noArchiveNoExpiryCleanupEnvelope:[30,60,90].map(days=>({days,databaseBytes:baseBytes+days*(hotBytes-baseBytes)})),
     ownerArchiveDiskBytesPerCopy30Days:allArchiveBytes.reduce((a,b)=>a+b,0)/850*850*30,
     ownerArchiveDiskBytesPerCopy90Days:allArchiveBytes.reduce((a,b)=>a+b,0)/850*850*90,
     activeBacklogUnbounded:true}};
  }
  const scenarioElapsedMs=performance.now()-scenarioStarted,scenarioNodeCpu=process.cpuUsage(scenarioNodeCpuStart),scenarioWorkerdCpuEnd=workerdCpuMs();
  const localWorkerdCpuMs=scenarioWorkerdCpuStart!==null&&scenarioWorkerdCpuEnd!==null?scenarioWorkerdCpuEnd-scenarioWorkerdCpuStart:null;
  const metrics={...rt.metrics};delete metrics.latencies;
  const apiRowsRead=metrics.read,apiRowsWritten=metrics.written,apiRequests=metrics.requests;
  const browseFactor=1000/browseCohort,browseDailyRowsRead=apiRowsRead+catalogReadRows*(browseFactor-1)+(archiveMetrics?.rowsRead??0),
   browseDailyRequests=apiRequests+catalogReadRequests*(browseFactor-1),dailyRowsWritten=apiRowsWritten+(archiveMetrics?.rowsWritten??0);
  const finalStorage=archiveResult?.storageAfterArchive??hotStorage;
  const result={fixtureTransport:rt.fixtureTransport,archiveMonitoring:'ALLOCATED_ONLY',lostSubmissionTransportAttempts:rt.lostSubmissionTransportAttempts??0,
   lostAttemptQuotaCaveat:'Lost requests may have committed. Same submission identity/cart is retried at most twice; failed attempts without response metadata have unknown D1 row cost and are excluded from measured totals, never treated as zero-cost capacity evidence.',
   deviceCount,trustedProvisioningWrites,lines,concurrent:concurrency,customers:1000,orders:1000,completed:completed,cancelled,customerCancelled,activeUncollected:ordersCount.active,
   customerConfirmedAdjustments:adjusted,duplicateSubmissionChecks:duplicateChecks,lostResponseRecoveries,billingClaimRetries:billingRetries,
   catalog:{seededSyntheticProducts:products.length,ownerPublishedProducts:publishedCount,ownerPublishBatches:publishedBatches,
    measuredBrowseVisitors:catalogVisitors,measuredBrowsePageRequests:catalogReadRequests,measuredCatalogRowsRead:catalogReadRows,
    modelD1RowsReadFor1000DynamicCatalogVisitors:browseDailyRowsRead,modelWorkerRequestsFor1000DynamicCatalogVisitors:browseDailyRequests},
   orderElapsedMs,requestWallP50Ms:percentile(orderLatencies,.5),requestWallP95Ms:percentile(orderLatencies,.95),requestWallMaxMs:Math.max(...orderLatencies),
   synchronization:{requests:syncRequests,eventsObserved:syncEvents,maxPageWallMs:maxSyncDelayMs,foregroundPollRequests:pollRequests},
   measuredD1ApiRowsRead:apiRowsRead,measuredD1ApiRowsWritten:apiRowsWritten,measuredOwnerArchiveRowsRead:archiveMetrics?.rowsRead??0,
   measuredOwnerArchiveRowsWritten:archiveMetrics?.rowsWritten??0,measuredDailyD1RowsWrittenIncludingArchive:dailyRowsWritten,modeledTrustedHourlyRefresh:{basis:'Two non-indexed freshness column updates per individual per hour; production identity synchronizer is not implemented or measured',individuals:deviceCount,additionalRowsWrittenPer24h:deviceCount*2*24,rowsWrittenIncludingLocalWorkloadAndModeledRefresh:dailyRowsWritten+deviceCount*2*24},measuredWorkerRequests:apiRequests,
   forecastD1RowsReadWith1000DynamicCatalogVisitors:browseDailyRowsRead,forecastWorkerRequestsWith1000DynamicCatalogVisitors:browseDailyRequests,
   freeQuotaComparison:{publishedD1RowsReadDaily:5000000,readUsePercent:100*browseDailyRowsRead/5000000,
    publishedD1RowsWrittenDaily:100000,writeUsePercent:100*dailyRowsWritten/100000,publishedWorkerRequestsDaily:100000,maxObservedQueriesPerInvocation:metrics.maxQueries,queryLimit:50,
    requestUsePercent:100*browseDailyRequests/100000,storageSafetyCutoffBytes:450000000,storageAfterHotCohortBytes:hotStorage.bytes,
    storageAfterSafeArchiveBytes:finalStorage.bytes,
   localWorkerCpuLimitVerified:false},
   catalogSeedMetrics:rt.seedMetrics,catalogPublication,beforeCatalogBytes:beforeCatalog,afterCatalogBytes:afterCatalog,
   orderStorageBeforeBytes:afterCatalog,orderStorageHotBytes:hotStorage.bytes,storageGrowthBeforeArchiveBytes:hotStorage.bytes-afterCatalog,
   sourceArchiveResult:archiveResult,scenarioElapsedMs,localNodeProcessCpuMs:(scenarioNodeCpu.user+scenarioNodeCpu.system)/1000,
   localWorkerdCpuMs,localAggregateCpuMs:localWorkerdCpuMs===null?null:localWorkerdCpuMs+(scenarioNodeCpu.user+scenarioNodeCpu.system)/1000,
   localPersistencePath:rt.directory};
  const wrapped={runtime:'Miniflare 4.20260730.0 / local workerd D1',productionCapacityVerified:false,createdAt:new Date().toISOString(),result};
  summaries.push(result);await writeFile(path.join(evidence,`phase4c-capacity-${lines}-lines-${concurrency}-concurrent-${deviceCount}-devices.json`),JSON.stringify(wrapped,null,2));
  console.log(JSON.stringify({deviceCount,lines,concurrent:concurrency,orders:1000,completed,cancelled,active:ordersCount.active,adjusted,
   requests:apiRequests,rowsRead:apiRowsRead,rowsWritten:apiRowsWritten,storageGrowth:hotStorage.bytes-afterCatalog,
   postArchive:archiveResult?.storageAfterArchive.bytes,elapsedMs:Math.round(orderElapsedMs)}));
 }finally{await rt.close();}
}
if(selectedProfile)process.exit(0);
if(process.argv.includes('--collect'))for(const lines of [5,20])for(const concurrency of [20,100])for(const devices of [5,10]){
 const profile=JSON.parse(await readFile(path.join(evidence,`phase4c-capacity-${lines}-lines-${concurrency}-concurrent-${devices}-devices.json`),'utf8')).result;
 summaries.push(profile);
}
const maximumInput=await maximumInputProbe(),maximumAdjustments=await maximumAdjustmentProbe(),indexMetrics=await indexOverhead();
const report={runtime:'Miniflare 4.20260730.0 / local workerd D1',localOnly:true,productionCapacityVerified:false,
 officialLimits:{d1RowsReadPerDay:5000000,d1RowsWrittenPerDay:100000,freeDatabaseBytes:500000000,freeAccountStorageBytes:5000000000,
  workersRequestsPerDay:100000,workersCpuMsPerInvocation:10},maximumInput,maximumAdjustments,indexMetrics,scenarios:summaries,
 limitations:['Local rows_read, rows_written, and size_after are real Miniflare/workerd D1 measurements, not Cloudflare production account metrics.',
  'Worker CPU is measured only as local process CPU/wall time; the Cloudflare Workers Free 10 ms CPU limit cannot be proven by Miniflare.',
  'The 100-visitor catalog test is measured; the 1,000-visitor direct-D1 reading figure is a linear projection from that cohort. A static Pages catalog may avoid those D1 reads, but production caching/routing is unverified.',
  'Archive copies are files on one synthetic disk. Independent physical storage, owner capacity, account eligibility, paid-upgrade controls, provider exhaustion, and deployment behavior are unverified.',
  'Use repeated measured-cohort growth, not per-order compaction savings. The first two raw profile models confound expiry cleanup and are superseded; their zero-backlog/backlog-substitution figures are not accepted. A separate final-schema physical history-growth probe measures allocation and monitor scans; it is not a multi-day authenticated API run. Any unresolved backlog can grow without bound.']};
await writeFile(path.join(evidence,'phase4c-capacity-results.json'),JSON.stringify(report,null,2));
console.log('Phase 4C signed workloads complete.');
