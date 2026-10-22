// Analyze measured compressed service cycles; never turn them into provider acceptance.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {root} from './runtime.mjs';
const evidence=path.join(root,'evidence');
const profiles=[];
for(const [lines,customers,devices] of [[5,20,5],[20,100,10]]){
 const file=`v1-capacity-${lines}-lines-${customers}-concurrent-${devices}-devices.json`;
 const bytes=await readFile(path.join(evidence,file));
 const {result:s}=JSON.parse(bytes);
 assert.equal(s.v1.cycles,2);assert.equal(s.orders,1000);
 assert.equal(s.completed,850);assert.equal(s.cancelled,50);assert.equal(s.activeUncollected,100);
 assert.equal(s.catalog.ownerPublishedProducts,3226);
 assert.equal(s.sourceArchiveResult.completedOrdersArchived,850);
 assert.equal(s.tenantArchive.authority,'QUARANTINED');
 assert.equal(s.tenantArchive.snapshotBeforeFutureMaintenance,true);
 assert.equal(s.v1.notifications.actualFcmDelivered,0);
 const m=s.workerdMeter;
 assert.ok(m.requests>=s.measuredWorkerRequests&&m.rowsWritten>=s.measuredD1ApiRowsWritten);
 assert.ok(m.maxQueries<=50);
 const sum=key=>s.v1.dailyEvidence.reduce((n,d)=>n+d[key],0);
 const accounting={completedResponsesMissingAtClient:m.requests-s.measuredWorkerRequests,
  additionalRowsRead:m.rowsRead-s.measuredD1ApiRowsRead,
  additionalRowsWritten:m.rowsWritten-s.measuredD1ApiRowsWritten,
  recordedTransportRetries:s.lostSubmissionTransportAttempts??0};
 accounting.unmatchedTransportAttempts=Math.max(0,accounting.recordedTransportRetries-accounting.completedResponsesMissingAtClient);
 const overhead={requests:m.requests-sum('requests'),rowsRead:m.rowsRead-sum('rowsRead'),rowsWritten:m.rowsWritten-sum('rowsWritten')};
 assert.ok(Object.values(overhead).every(n=>n>=0));
 const knownSetupWrites=s.catalogSeedMetrics.written+s.trustedProvisioningWrites+s.sourceArchiveResult.archiveSetupRowsWritten;
 const daily=s.v1.dailyEvidence.map(d=>{
  assert.equal(d.orders,500);assert.equal(d.completed,425);assert.equal(d.cancelled,25);assert.equal(d.newUncollected,50);
  assert.equal(d.catalogVisitors,500);assert.equal(d.foregroundPolls,devices*960);assert.equal(d.hintRefreshes,devices*550);
  return {...d,busyDayEnvelope:{
   rowsRead:d.rowsRead+overhead.rowsRead+s.measuredOwnerArchiveRowsRead,
   rowsWritten:d.rowsWritten+overhead.rowsWritten+s.measuredOwnerArchiveRowsWritten+knownSetupWrites+devices*48,
   inboundWorkerRequests:d.requests+overhead.requests,
   basis:'One actual cycle plus ALL one-off measured API overhead, ALL two-cycle source archive work, known fixture setup writes and modeled legacy hourly freshness. Unmetered foundations, sender/outbox, final V1 device protocol and unknown interrupted attempts remain excluded.',
   productionHeadroomVerified:false}};
 });
 const base=s.orderStorageBeforeBytes,after=s.sourceArchiveResult.storageAfterArchive.bytes;
 profiles.push({file,sha256:createHash('sha256').update(bytes).digest('hex'),lines,concurrentCustomers:customers,devices,
  cycles:2,ordersPerCycle:500,totalOrders:1000,daily,oneOffMeasuredApiOverhead:overhead,knownAdditionalSetupWrites:knownSetupWrites,
  sourceArchiveRowsRead:s.measuredOwnerArchiveRowsRead,sourceArchiveRowsWritten:s.measuredOwnerArchiveRowsWritten,
  totalMeasuredApiAndArchiveWrites:m.rowsWritten+s.measuredOwnerArchiveRowsWritten,
  totalMeasuredApiAndArchiveReads:m.rowsRead+s.measuredOwnerArchiveRowsRead,
  accounting,quotaTotalsAreLowerBounds:accounting.unmatchedTransportAttempts>0,maxQueries:m.maxQueries,
  hotBytes:s.orderStorageHotBytes,postArchiveBytes:after,initialCatalogBytes:base,
  encryptedOrderArchiveBytesPerCopy:s.sourceArchiveResult.encryptedArchiveBytes,
  projectedStorage:[30,60,90].map(days=>({days,bytes:Math.round(base+(after-base)*days/2),
   newUncollectedOrders:days*50,unresolvedCancelledOrders:days*25})),
  projectionBasis:'Linear repetition of the measured TWO-cycle cohort at 500/day, 50 NEW and 25 unresolved cancellations/day; NOT physical long-term allocation or sustained operation.',
  localP95WallMs:s.requestWallP95Ms,localNodeCpuMs:s.localNodeProcessCpuMs,localWorkerdCpuMs:s.localWorkerdCpuMs,
  cpuLimitVerified:false,actualFcmDelivered:0,modeledFcmSendAttemptsPerCycle:s.v1.notifications.modeledSendAttemptsPerCycle,
  elapsedMs:s.scenarioElapsedMs,fixture:s.localPersistencePath});
}
const report={createdAt:new Date().toISOString(),localOnly:true,progressPercent:83.5,productionCapacityVerified:false,
 sustained500PerDayAccepted:false,authenticationCompatibleWithV1:false,profiles,
 limitations:[
  'Two compressed consecutive 500-order service cycles per profile share one disposable database; not 48 elapsed hours, long-term production operation or daily-reset verification.',
  'Unmodified older individual-identity signing fixtures are used for load/replay/revocation regression only. No separate worker login was added. A reviewed V1 device-principal protocol remains blocked.',
  'Notification-triggered and duplicate-hint refreshes are real signed API calls. FCM transport, sender OAuth/CPU, outbox schema/cost, real background delivery and registration-token security are unverified.',
  'Current-time signed operations finish before test-only future maintenance. Final expiry deletion is a modeled maintenance operation, not a business record deletion or production clock change.',
  'All 850 completed orders are encrypted/exported/restored before owner-confirmed compaction; all 100 uncollected and 50 unresolved cancelled records remain. Copies share one synthetic disk; independent media unverified.',
  'Do not use inherited 1,000/day visitor/storage helper forecasts in raw profile JSON for Version 1. Only v1.dailyEvidence and the explicitly two-cycle projections here apply.',
  'The busy-day envelope includes conservatively duplicated two-cycle archive/setup overhead, but missing production auth/sender/account workload and interrupted attempts prevent a safe-headroom claim.',
  'Local D1 counters, allocation and process CPU are measured; provider quota exhaustion, commercial/account eligibility, 10 ms per-invocation CPU, CGNAT abuse behavior and real-device scheduling are unverified.',
  '450,000,000-byte admission cutoff unchanged. Protected backlog is never automatically deleted; maximum-size/unbounded backlog can still exceed free storage at 500/day.'
 ]};
await writeFile(path.join(evidence,'v1-capacity-analysis.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
