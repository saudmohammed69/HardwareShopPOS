// Measured local cohorts; missing provider/auth/sender costs remain explicit.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {root,runtime} from './runtime.mjs';
import {abuseFixtureBindings} from './customer-abuse-fixture.mjs';
const load=async name=>JSON.parse((await readFile(path.join(root,'evidence',name),'utf8')).replace(/^\uFEFF/,''));
const runs=[...await load('phase4f-capacity-run-status.json'),await load('phase4f-capacity-500-normal-status.json')];
const profiles=[],failedProfiles=[];
for(const [orders,lines,concurrent,devices] of [[200,5,20,5],[300,5,20,5],[400,20,20,5],[500,20,100,10],[1000,20,100,10],[500,20,20,10]]){
 const run=runs.find(r=>r.orders===orders&&r.profile===[lines,concurrent,devices].join(','));assert.ok(run,'Missing executed workload status');
 if(run.exitCode!==0){
  const log=await readFile(path.join(root,run.log),'utf8'),saved=log.match(/\{"phase":"fixture-preserved"[^\r\n]+/);
  let persisted=null;
  if(saved){
   const directory=JSON.parse(saved[0]).directory,inspect=await runtime({directory,initialise:false,pooledTransport:true,bindings:{AUTH_MODE:'SIGNED_LOCAL',...abuseFixtureBindings}});
   try{persisted={directory,...await inspect.db.prepare("SELECT COUNT(*) total,COUNT(DISTINCT submission) uniqueSubmissions,COUNT(DISTINCT bill_id) uniqueBillIds,SUM(status='COMPLETED') completed,SUM(status='BILLING_IN_PROGRESS') uncertainClaims,SUM(status='NEW') newOrders,SUM(status='CANCELLED') cancelled FROM orders").first()};}
   finally{await inspect.close();}
  }
  const stage=log.includes('phase4f-capacity.mjs:177:')?'CUSTOMER_CATALOG_READ':log.includes('phase4f-capacity.mjs:196:')?'SIGNED_PREPARE_OR_READY':log.includes('phase4f-capacity.mjs:197:')?'SIGNED_BILLING_CLAIM':'SEE_RETAINED_STACK';
  failedProfiles.push({...run,lines,concurrentCustomers:concurrent,devices,persisted,capacityVerified:false,stage,
   failure:log.includes('UND_ERR_SOCKET')?'Local socket closure; no blind signed-mutation retry':log.slice(-1500),
   submissionRetryLogEntries:(log.match(/stable-submission-network-retry/g)??[]).length,
   retryLogCaveat:'Entries after fixture disposal may be cascading teardown errors, not independent initial failures.',quotaTotals:'UNAVAILABLE_FOR_INCOMPLETE_RUN'});continue;
 }
 const file=`phase4f-capacity-${orders}-${lines}-lines-${concurrent}-concurrent-${devices}-devices.json`;
 const bytes=await readFile(path.join(root,'evidence',file)),{result:s}=JSON.parse(bytes),p=s.phase4f;
 const cycles=orders===500?2:1;
 assert.equal(p.cycles,cycles);assert.equal(s.orders,orders*cycles);assert.equal(s.completed,orders*.85*cycles);
 assert.equal(s.cancelled,orders*.05*cycles);assert.equal(s.activeUncollected,orders*.1*cycles);
 assert.equal(s.catalog.ownerPublishedProducts,3226);assert.equal(s.sourceArchiveResult.completedOrdersArchived,s.completed);
 assert.equal(s.tenantArchive.authority,'QUARANTINED');assert.equal(s.tenantArchive.snapshotBeforeFutureMaintenance,true);
 assert.equal(p.notifications.actualFcmDelivered,0);
 const m=s.workerdMeter,sum=key=>p.dailyEvidence.reduce((n,d)=>n+d[key],0);
 assert.ok(m.requests>=s.measuredWorkerRequests&&m.rowsWritten>=s.measuredD1ApiRowsWritten);assert.ok(m.maxQueries<=50);
 const accounting={completedResponsesMissingAtClient:m.requests-s.measuredWorkerRequests,additionalRowsRead:m.rowsRead-s.measuredD1ApiRowsRead,
  additionalRowsWritten:m.rowsWritten-s.measuredD1ApiRowsWritten,recordedTransportRetries:s.lostSubmissionTransportAttempts??0};
 accounting.unmatchedTransportAttempts=Math.max(0,accounting.recordedTransportRetries-accounting.completedResponsesMissingAtClient);
 const overhead={requests:m.requests-sum('requests'),rowsRead:m.rowsRead-sum('rowsRead'),rowsWritten:m.rowsWritten-sum('rowsWritten')};
 assert.ok(Object.values(overhead).every(n=>n>=0));
 const setupWrites=s.catalogSeedMetrics.written+s.trustedProvisioningWrites+s.sourceArchiveResult.archiveSetupRowsWritten;
 const daily=p.dailyEvidence.map(d=>{
  assert.equal(d.orders,orders);assert.equal(d.catalogVisitors,orders);assert.equal(d.foregroundPolls,devices*960);
  assert.equal(d.hintRefreshes,Math.ceil(orders*1.1)*devices);
  return {...d,busyDayEnvelope:{rowsRead:d.rowsRead+overhead.rowsRead+s.measuredOwnerArchiveRowsRead,
   rowsWritten:d.rowsWritten+overhead.rowsWritten+s.measuredOwnerArchiveRowsWritten+setupWrites+devices*48,
   inboundWorkerRequests:d.requests+overhead.requests,
   basis:'Actual cycle plus ALL one-off API overhead and final source archive work (both cycles for 500), known setup writes and modeled legacy hourly freshness. Missing production device auth, FCM/outbox, provider verification CPU, other account usage and unmatched lost attempts are additional.',productionHeadroomVerified:false}};
 });
 const base=s.orderStorageBeforeBytes,after=s.sourceArchiveResult.storageAfterArchive.bytes;
 const inspect=await runtime({directory:s.localPersistencePath,initialise:false,pooledTransport:true,bindings:{AUTH_MODE:'SIGNED_LOCAL',...abuseFixtureBindings}});
 let backlog;
 try {
  const protectedRows=await inspect.db.prepare("SELECT COUNT(*) n,SUM(length(CAST(doc AS BLOB))) bytes,MAX(length(CAST(doc AS BLOB))) maximum FROM orders WHERE status IN ('NEW','CANCELLED')").first();
  assert.equal(protectedRows.n,orders*.15*cycles);
  const average=protectedRows.bytes/protectedRows.n;
  backlog={protectedRecords:protectedRows.n,protectedDocumentBytes:protectedRows.bytes,averageDocumentBytes:average,maximumDocumentBytes:protectedRows.maximum,
   allOrdersUnresolvedDocumentOnly:[30,60,90].map(days=>({days,orders:days*orders,documentBytes:Math.ceil(days*orders*average)})),
   legalMaximumDocumentEnvelope90Days:orders*90*96000,
   warning:'Document-only sensitivity excludes catalog/index/events/security/metadata and is NOT allocated capacity. 96,000-byte bound is a worst-case envelope, not measured average.'};
 } finally {await inspect.close();}
 profiles.push({file,sha256:createHash('sha256').update(bytes).digest('hex'),ordersPerCycle:orders,lines,concurrentCustomers:concurrent,devices,cycles,totalOrders:s.orders,
  daily,oneOffMeasuredApiOverhead:overhead,knownAdditionalSetupWrites:setupWrites,accounting,quotaTotalsAreLowerBounds:accounting.unmatchedTransportAttempts>0,
  totalMeasuredApiAndArchiveReads:m.rowsRead+s.measuredOwnerArchiveRowsRead,totalMeasuredApiAndArchiveWrites:m.rowsWritten+s.measuredOwnerArchiveRowsWritten,
  sourceArchiveRowsRead:s.measuredOwnerArchiveRowsRead,sourceArchiveRowsWritten:s.measuredOwnerArchiveRowsWritten,maxQueries:m.maxQueries,
  initialCatalogBytes:base,hotBytes:s.orderStorageHotBytes,postArchiveBytes:after,encryptedOrderArchiveBytesPerCopy:s.sourceArchiveResult.encryptedArchiveBytes,
  backlog,projectedStorage:[30,60,90].map(days=>({days,bytes:Math.round(base+(after-base)*days/cycles),newUncollectedOrders:days*orders*.1,unresolvedCancelledOrders:days*orders*.05})),
  projectionBasis:'Linear measured-cohort model, 85% completed and explicitly archived, 10% NEW and 5% unresolved cancellation retained every day; not physical multi-day/provider verification.',
  localP95WallMs:s.requestWallP95Ms,maxSyncPageWallMs:s.synchronization.maxPageWallMs,localNodeCpuMs:s.localNodeProcessCpuMs,localWorkerdCpuMs:s.localWorkerdCpuMs,
  cpuLimitVerified:false,actualFcmDelivered:0,modeledFcmSendAttemptsPerCycle:p.notifications.modeledSendAttemptsPerCycle,elapsedMs:s.scenarioElapsedMs,fixture:s.localPersistencePath});
}
const report={createdAt:new Date().toISOString(),localOnly:true,progressPercent:83.5,productionCapacityVerified:false,sustained500PerDayAccepted:false,
 authenticationCompatibleWithV1:false,profiles,failedProfiles,executedWorkloads:runs,limitations:[
 '500/day is two compressed consecutive cycles on one database; others are one cohort. No real days, provider quota resets or sustained hosted operation verified.',
 'Existing INDIVIDUAL synthetic auth is a regression baseline, not V1 device authorization. Trustworthy owner bootstrap remains blocked.',
 'Challenge/session checks use synthetic signed verification. Real Turnstile widget, IP provenance, account eligibility, bot classification and verification CPU are not tested.',
 'FCM sends are modeled only; actual delivered zero. Secure free sender, registration security, retry outbox schema/cost and Android background behavior are unverified.',
 'All signed operations finish before test-only +61-minute maintenance. Owner archive/copy/isolated restore precede eligible compaction; protected orders remain. Copies share one disk.',
 'Local D1 counters include indexes and source archive SQL; setup is separately identified. Unmatched lost attempts, production auth/sender and shared account usage prevent safe-headroom acceptance.',
 'Storage projections are finite cohort models, not physical 90-day allocation. Large inputs, slower cleanup or unresolved backlog can reach 450,000,000 bytes sooner.',
 'Hosted export remains bounded and no hosted cleanup is enabled. No live data or production account was used. No unlimited storage or guaranteed ₹0 capacity claim.'
 ]};
await writeFile(path.join(root,'evidence','phase4f-capacity-analysis.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
