// Read-only evidence analysis. Estimates are deliberately separate from measured
// D1 metadata. No account eligibility, production CPU or unlimited storage claim.
import assert from 'node:assert/strict';
import {readFile,writeFile,readdir} from 'node:fs/promises';
import path from 'node:path';
import {root} from './runtime.mjs';
import {readArchive} from './retention.mjs';
const evidence=path.join(root,'evidence');
const input=JSON.parse(await readFile(path.join(evidence,'phase4c-capacity-results.json'),'utf8'));
assert.equal(input.scenarios.length,8);
const rows=input.scenarios.map(s=>{
 assert.equal(s.orders,1000);assert.equal(s.completed,850);assert.equal(s.cancelled,50);assert.equal(s.activeUncollected,100);
 assert.equal(s.catalog.ownerPublishedProducts,3226);assert.ok(s.freeQuotaComparison.maxObservedQueriesPerInvocation<=50);
 const initial=s.orderStorageBeforeBytes,post=s.sourceArchiveResult.storageAfterArchive.bytes;
 return {lines:s.lines,concurrentCustomers:s.concurrent,devices:s.deviceCount,
  archiveMonitoring:s.archiveMonitoring??'LEGACY_FULL_SUMMARY',
  lostSubmissionTransportAttempts:s.lostSubmissionTransportAttempts??0,
  measuredQuotaIsLowerBoundBecauseLostResponses:(s.lostSubmissionTransportAttempts??0)>0,
  lostRequestCaveat:'Exact D1 cost is unavailable for attempts lost before response metadata. Successful-response totals are not a complete quota total when this count is nonzero.',
  measuredApiRequests:s.measuredWorkerRequests,measuredApiRowsRead:s.measuredD1ApiRowsRead,
  maximumApiHttpAttemptsIncludingLost:s.measuredWorkerRequests+(s.lostSubmissionTransportAttempts??0),
  modeledCatalogAndApiHttpAttemptsIncludingLost:s.forecastWorkerRequestsWith1000DynamicCatalogVisitors+(s.lostSubmissionTransportAttempts??0),
  measuredSourceArchiveRowsRead:s.measuredOwnerArchiveRowsRead,measuredApiRowsWritten:s.measuredD1ApiRowsWritten,
  measuredSourceArchiveRowsWritten:s.measuredOwnerArchiveRowsWritten,
  measuredTotalRowsWritten:s.measuredDailyD1RowsWrittenIncludingArchive,
  separatelyMeasuredFixtureCatalogSeedWrites:s.catalogSeedMetrics.written,
  separatelyMeasuredTrustedIndividualProvisioningWrites:s.trustedProvisioningWrites,
  separatelyMeasuredArchiveCounterSetupWrites:s.sourceArchiveResult.archiveSetupRowsWritten,
  knownMeasuredWritesIncludingThoseFixtureSetups:s.measuredDailyD1RowsWrittenIncludingArchive+s.catalogSeedMetrics.written+s.trustedProvisioningWrites+s.sourceArchiveResult.archiveSetupRowsWritten,
  setupCaveat:'Fixture catalog seeding and trusted individual provisioning are measured separately from daily API/archive work. Initial signing-fixture users/memberships/devices and schema creation are not metered here. This sum is not a complete production first-day/account total.',
  modeledRowsWrittenWithTrustedHourlyRefresh:s.modeledTrustedHourlyRefresh.rowsWrittenIncludingLocalWorkloadAndModeledRefresh,
  modeledDailyRowsReadWith1000CatalogVisitors:s.forecastD1RowsReadWith1000DynamicCatalogVisitors,
  modeledApiRequestsWith1000CatalogVisitors:s.forecastWorkerRequestsWith1000DynamicCatalogVisitors,
  illustrativeRequestsIfEveryLocalArchiveSqlStatementWereARequest:s.forecastWorkerRequestsWith1000DynamicCatalogVisitors+s.sourceArchiveResult.sourceD1ArchiveCleanup.statements,
  archiveRequestModelCaveat:'Hosted owner archive authorization/transport is not implemented. One request per measured local SQL statement is an illustrative count, not tested Worker request/CPU/auth overhead.',
  maxQueriesPerInvocation:s.freeQuotaComparison.maxObservedQueriesPerInvocation,
  localRequestWallP95Ms:s.requestWallP95Ms,localSyncPageMaxMs:s.synchronization.maxPageWallMs,
  measuredLocalNodeCpuMs:s.localNodeProcessCpuMs,measuredSourceWorkerdCpuMs:s.localWorkerdCpuMs,
  cpuCaveat:'Node CPU plus surviving source workerd process CPU only; terminated isolated-restore workerd CPU is not in the end snapshot. Not per-invocation CPU and not Cloudflare 10 ms compliance.',
  measuredInitialBytes:initial,measuredHotBytes:s.orderStorageHotBytes,measuredPostArchiveBytes:post,
  repeatedCohortStorageModel:[30,60,90].map(days=>({days,bytes:initial+days*(post-initial),
   protectedNewOrders:days*100,unresolvedCancelledOrders:days*50})),
  noArchiveNoExpiryCleanupEnvelope:[30,60,90].map(days=>({days,bytes:initial+days*(s.orderStorageHotBytes-initial)})),
  storageModelCaveat:'Linear one-day cohort repetition, not physical multi-day allocation. Never subtract hot-to-final cleanup bytes as per-order archive savings. Page reuse, fragmentation, security-history growth, input sizes and unresolved backlog change actual growth.',
  verifiedEncryptedOrderExports:s.sourceArchiveResult.archiveCopies/2,
  archiveBytesPerCopy:s.sourceArchiveResult.encryptedArchiveBytes,
  securityArchiveBytesPerCopy:s.sourceArchiveResult.securityArchive.encryptedBytes,
  modeledArchiveBytesPerCopy90Days:90*s.sourceArchiveResult.encryptedArchiveBytes,
  independentlyStoredMediaVerified:false};
});
let history=null;
try{history=JSON.parse(await readFile(path.join(evidence,'phase4c-history-growth.json'),'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}
const beforeMonitor=JSON.parse(await readFile(path.join(evidence,'phase4c-before-monitor-optimization/phase4c-capacity-20-lines-20-concurrent-10-devices.json'),'utf8')).result;
const afterMonitor=input.scenarios.find(s=>s.lines===20&&s.concurrent===20&&s.deviceCount===10);
assert.equal(afterMonitor.archiveMonitoring,'ALLOCATED_ONLY','Matching post-monitor workload must finish successfully');
const monitorComparison={profile:'20 lines, 20 customers, 10 devices',
 beforeArchiveRowsRead:beforeMonitor.measuredOwnerArchiveRowsRead,afterArchiveRowsRead:afterMonitor.measuredOwnerArchiveRowsRead,
 measuredArchiveReadReduction:beforeMonitor.measuredOwnerArchiveRowsRead-afterMonitor.measuredOwnerArchiveRowsRead,
 beforeTotalWrites:beforeMonitor.measuredDailyD1RowsWrittenIncludingArchive,afterTotalWrites:afterMonitor.measuredDailyD1RowsWrittenIncludingArchive,
 afterMonitoring:afterMonitor.archiveMonitoring??'LEGACY_FULL_SUMMARY',lostSubmissionAttempts:afterMonitor.lostSubmissionTransportAttempts??0,
 preservation:'Same final D1 schema, 1000 orders, 850 verified encrypted archived completions, 100 full uncollected and 50 full unresolved cancellations. Only repeated monitoring queries changed.'};
// Logical-document sensitivity is a lower bound, not allocated D1 storage. Use
// verified actual archives, remove only the final billing-sync field to obtain
// the representative pre-billing document shape, and never modify source data.
const representative=input.scenarios.find(s=>s.lines===20&&s.concurrent===100&&s.deviceCount===10);
const exportDirectory=path.join(representative.localPersistencePath,'owner-exports');
assert.ok(path.resolve(exportDirectory).startsWith(path.join(root,'.local-state')+path.sep));
let fullBytes=0,smallBytes=0,archivedRows=0;
for(const file of (await readdir(exportDirectory)).filter(x=>x.endsWith('.hwpos-archive'))){
 const full=path.join(exportDirectory,file),checksum=(await readFile(full+'.sha256','utf8')).trim();
 const payload=await readArchive(full,'Phase 4C signed local capacity proof only',checksum);
 for(const row of payload.orders){
  const doc=JSON.parse(row.doc);delete doc.finalizedPosBillSyncId;
  fullBytes+=Buffer.byteLength(JSON.stringify(doc));archivedRows++;
  const history=payload.events.filter(e=>e.order_id===row.id),last=history.at(-1);
  smallBytes+=Buffer.byteLength(JSON.stringify({estimatedSubtotalPaisa:doc.estimatedSubtotalPaisa,archiveRef:checksum,archiveVersion:1,source:doc.source,
   archiveEventRange:{first:history[0].seq,last:last.seq,count:history.length}}));
 }
}
assert.equal(archivedRows,850);
const fullMean=fullBytes/archivedRows,smallMean=smallBytes/archivedRows;
const protectedDocumentSensitivity={measuredArchivedRows:archivedRows,meanRepresentativePrebillingDocumentBytes:fullMean,meanCompactedSummaryBytes:smallMean,
 basis:'Repeated verified 20-line sample document mix, with only finalizedPosBillSyncId removed. Full protected orders are never compacted. Hypothetical compacted remainder requires actual owner resolution/collection and verified archives, never automatic cleanup.',
 excludes:'All indexes, identity columns, events, catalog, security tables, page fragmentation and archive disk. These are document-only lower bounds, not allocated capacity forecasts.',
 cases:[0,50,150,250,500,1000].map(protectedPerDay=>({protectedFullOrdersPerDay:protectedPerDay,
  values:[30,60,90].map(days=>({days,logicalDocumentBytes:days*(protectedPerDay*fullMean+(1000-protectedPerDay)*smallMean)}))}))};
const report={localOnly:true,productionCapacityVerified:false,progressPercent:83.5,
 observedProfiles:rows,history,monitorComparison,protectedDocumentSensitivity,
 rejectedModel:'The first two raw per-profile JSON files contain an earlier zero-backlog/per-order-savings formula. It confounds expiry cleanup and order compaction; those estimates are superseded by this report and must not be used as capacity evidence.',
 capacityDecision:'No guaranteed 1000-orders/day free-plan acceptance: protected backlog/input-size sensitivity, unmeasured hosted archive/identity overhead, lost-request metering, provider CPU/account eligibility and independent archive storage remain gates. Repeated full-history cleanup summaries were replaced by bounded allocation probes and are compared separately.',
 maximumInput:input.maximumInput,maximumAdjustments:input.maximumAdjustments,indexMetrics:input.indexMetrics};
await writeFile(path.join(evidence,'phase4c-capacity-analysis.json'),JSON.stringify(report,null,2));
const table=['| Lines | Customers | Devices | Archive monitor | API writes | Archive writes | Measured total writes | With modeled freshness | Modeled reads / 1,000 catalog visitors | Lost submit attempts | Max SQL |',
 '| ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
 ...rows.map(s=>`| ${s.lines} | ${s.concurrentCustomers} | ${s.devices} | ${s.archiveMonitoring} | ${s.measuredApiRowsWritten} | ${s.measuredSourceArchiveRowsWritten} | ${s.measuredTotalRowsWritten} | ${s.modeledRowsWrittenWithTrustedHourlyRefresh} | ${s.modeledDailyRowsReadWith1000CatalogVisitors} | ${s.lostSubmissionTransportAttempts} | ${s.maxQueriesPerInvocation} |`)];
await writeFile(path.join(evidence,'phase4c-capacity-table.md'),table.join('\n')+'\n');
console.log(JSON.stringify({profiles:rows.length,writeRange:[Math.min(...rows.map(r=>r.measuredTotalRowsWritten)),Math.max(...rows.map(r=>r.measuredTotalRowsWritten))],
 readModelRange:[Math.min(...rows.map(r=>r.modeledDailyRowsReadWith1000CatalogVisitors)),Math.max(...rows.map(r=>r.modeledDailyRowsReadWith1000CatalogVisitors))],
 historyMeasured:history!==null,productionCapacityVerified:false}));
