import {readFile,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
import {root} from './runtime.mjs';
const evidence=path.join(root,'evidence'),read=async name=>JSON.parse(await readFile(path.join(evidence,name),'utf8'));
const input=await read('phase4d-capacity-results.json');assert.equal(input.scenarios.length,8);
const tails=await read('phase4d-tail-reconciliation.json');
const rows=input.scenarios.map(s=>{
 assert.equal(s.orders,1000);assert.equal(s.completed,850);assert.equal(s.cancelled,50);assert.equal(s.activeUncollected,100);
 assert.equal(s.catalog.ownerPublishedProducts,3226);assert.equal(s.tenantArchive.authority,'QUARANTINED');assert.ok([200,409].includes(s.tenantArchive.preCleanupSnapshotStatus));
 const m=s.workerdMeter;assert.ok(m.requests>=s.measuredWorkerRequests);assert.ok(m.rowsWritten>=s.measuredD1ApiRowsWritten);assert.ok(m.maxQueries<=50);
 const lost={completedResponsesMissingAtClient:m.requests-s.measuredWorkerRequests,rowsRead:m.rowsRead-s.measuredD1ApiRowsRead,rowsWritten:m.rowsWritten-s.measuredD1ApiRowsWritten};
 lost.transportAttemptsWithoutCompletedResponseMetadata=Math.max(0,(s.lostSubmissionTransportAttempts??0)-lost.completedResponsesMissingAtClient);
 lost.quotaTotalsAreLowerBounds=lost.transportAttemptsWithoutCompletedResponseMetadata>0;
 const tail=tails.results.find(t=>t.profile.join(',')===[s.lines,s.concurrent,s.deviceCount].join(','));assert.ok(tail?.businessEvidenceUnchanged);
 const reads=s.forecastD1RowsReadWith1000DynamicCatalogVisitors+lost.rowsRead+tail.totalSupplementRowsRead,writes=m.rowsWritten+s.measuredOwnerArchiveRowsWritten+tail.totalSupplementRowsWritten;
 return {lines:s.lines,customers:s.concurrent,devices:s.deviceCount,source:s.localPersistencePath,
  clientApiRead:s.measuredD1ApiRowsRead,clientApiWritten:s.measuredD1ApiRowsWritten,workerdApiRead:m.rowsRead,workerdApiWritten:m.rowsWritten,
  sourceArchiveRead:s.measuredOwnerArchiveRowsRead+tail.totalSupplementRowsRead,sourceArchiveWritten:s.measuredOwnerArchiveRowsWritten+tail.totalSupplementRowsWritten,
  totalWritten:writes,modeledHourlyFreshnessWrites:s.deviceCount*48,totalWithModeledFreshness:writes+s.deviceCount*48,
  modeledReadsWith1000Visitors:reads,modeledRequestsWith1000Visitors:s.forecastWorkerRequestsWith1000DynamicCatalogVisitors+lost.completedResponsesMissingAtClient,
  knownAdditionalSetupWrites:s.catalogSeedMetrics.written+s.trustedProvisioningWrites+s.sourceArchiveResult.archiveSetupRowsWritten,
  lostResponseAccounting:lost,lostTransportRetries:s.lostSubmissionTransportAttempts,maxQueries:m.maxQueries,
  bindingOverhead:s.bindingOverhead,hostedSnapshot:s.tenantArchive,tailReconciliation:tail,
  hotBytes:s.orderStorageHotBytes,rawPostArchiveBytes:s.sourceArchiveResult.storageAfterArchive.bytes,afterArchiveBytes:tail.allocatedBytesAfterRealExpiry,
  localP95WallMs:s.requestWallP95Ms,localNodeCpuMs:s.localNodeProcessCpuMs,localSourceWorkerdCpuMs:s.localWorkerdCpuMs,
  orderArchiveBytesPerCopy:s.sourceArchiveResult.encryptedArchiveBytes,
  storageModel:[30,60,90].map(days=>({days,bytes:s.orderStorageBeforeBytes+days*(tail.allocatedBytesAfterRealExpiry-s.orderStorageBeforeBytes)}))};
});
const baseline=await read('phase4c-capacity-analysis.json'),history=await read('phase4d-history-growth.json');
const report={createdAt:new Date().toISOString(),localOnly:true,productionCapacityVerified:false,progressPercent:83.5,profiles:rows,history,tailReconciliation:tails,
 baselineBacklogSensitivity:baseline.protectedDocumentSensitivity,
 baselineSource:'phase4c-capacity-analysis.json; order/pricing/retention semantics unchanged; this is a labeled prior measured-document model, not a new 90-day API run',
 limitations:['Private in-workerd API counters are volatile; they cover completed backend responses including lost outer-loopback responses only while this workerd isolate survives. An attempt interrupted before response finalization may have committed but lacks exact row metadata; unmatched attempts keep totals LOWER BOUNDS, never assumed zero. Provider accounting is not inferred.',
 'Customer admission uses synthetic per-submission IP identities and compressed polling. Actual shared carrier/shop NAT behavior, trusted provider IP extraction, malicious traffic and Android background scheduling are not verified.',
 'Some original profiles placed the final read-only archive POST after simulated future maintenance. Those raw files are unchanged and are not retroactively a clock-consistent full replay test. A later current-time-only supplement removes their remaining proof metadata only after REAL expiry and verifies unchanged order/event/audit/claim/binding hashes; all supplement D1 reads/writes are included separately. Newer profiles complete all signed APIs before future maintenance.',
 'Local source archive SQL remains separate from API metadata. Hosted bounded snapshot auth/read/write overhead is included, but full hosted cleanup/paginated archive is not implemented or budgeted.',
 'Hosted snapshot returns a complete bounded export (200) or explicit bound failure (409), depending on actual retained security history after normal expiry pruning. Oversized non-expiring audit evidence is separately verified to reject without deletion. Each profile exports/restores 12 selected identities plus bounded tenant security records without authorizing cleanup; the per-profile maintenance-ordering flag distinguishes earlier post-maintenance stub snapshots from corrected pre-maintenance full-order snapshots.',
 'Hourly trusted identity freshness is modeled, not a real provider feed. Initial signing authority/device foundations are separate/unmetered; setup writes shown do not prove a complete production account budget.',
 'Local p95 is wall latency; local aggregate CPU omits terminated isolated restore processes and later tail-reconciliation process CPU, and cannot verify Cloudflare 10 ms HTTP CPU.',
 'Independent physical media, provider quotas/eligibility, production allocation, real background scheduling and unbounded protected backlogs remain release blockers.']};
await writeFile(path.join(evidence,'phase4d-capacity-analysis.json'),JSON.stringify(report,null,2));
const table=['| Lines | Customers | Devices | API R/W (workerd) | Archive R/W | Total writes | + modeled freshness | Modeled reads | Requests | Max SQL | Hot/archive bytes |',
 '| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
 ...rows.map(r=>`| ${r.lines} | ${r.customers} | ${r.devices} | ${r.workerdApiRead}/${r.workerdApiWritten} | ${r.sourceArchiveRead}/${r.sourceArchiveWritten} | ${r.totalWritten} | ${r.totalWithModeledFreshness} | ${r.modeledReadsWith1000Visitors} | ${r.modeledRequestsWith1000Visitors} | ${r.maxQueries} | ${r.hotBytes}/${r.afterArchiveBytes} |`)];
await writeFile(path.join(evidence,'phase4d-capacity-table.md'),table.join('\n')+'\n');console.log(table.join('\n'));
