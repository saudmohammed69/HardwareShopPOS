// Disposable physical-allocation/read-cost probe, NOT accepted business orders.
// Repeats measured final-schema rows with fresh synthetic identities. Archive
// references are representative bytes, not new verifiable archive attestations.
// No route, production account, billing code, live DB or stock is used here.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {runtime,root} from './runtime.mjs';
import {measureStorage,measureAllocatedStorage} from './retention.mjs';
process.env.PROOF_POOLED_TRANSPORT='1';
const evidence=path.join(root,'evidence'),results=[];
// Fail before allocating any history if a required measured source is missing.
await Promise.all([5,20].map(lines=>readFile(path.join(evidence,`phase4c-capacity-${lines}-lines-100-concurrent-10-devices.json`),'utf8')));
const quoted=name=>'"'+name.replaceAll('"','""')+'"';
async function copyRows(db,table,rows){
 if(!rows.length)return;
 const cols=Object.keys(rows[0]),sql=`INSERT INTO ${quoted(table)}(${cols.map(quoted).join(',')}) VALUES(${cols.map(()=>'?').join(',')})`;
 assert.ok(cols.length<=100);
 for(let start=0;start<rows.length;start+=40)await db.batch(rows.slice(start,start+40).map(row=>db.prepare(sql).bind(...cols.map(col=>row[col]))));
}
async function monitor(rt,allocatedOnly=false){
 let rowsRead=0,rowsWritten=0;
 const wrap=statement=>({all:async()=>{const r=await statement.all();rowsRead+=r.meta.rows_read;rowsWritten+=r.meta.rows_written;return r;},
  first:async()=>{const r=await statement.all();rowsRead+=r.meta.rows_read;rowsWritten+=r.meta.rows_written;return r.results[0]??null;}});
 const measured={db:{prepare:sql=>wrap(rt.db.prepare(sql))}};
 const storage=await (allocatedOnly?measureAllocatedStorage:measureStorage)(measured);
 assert.equal(rowsWritten,0);return {storage,rowsRead,rowsWritten};
}
for(const lines of [5,20]){
 const profile=JSON.parse(await readFile(path.join(evidence,`phase4c-capacity-${lines}-lines-100-concurrent-10-devices.json`),'utf8')).result;
 assert.ok(path.resolve(profile.localPersistencePath).startsWith(path.join(root,'.local-state')+path.sep),'Only disposable proof stores may be inspected');
 const source=await runtime({directory:profile.localPersistencePath,initialise:false});
 let tables,orders,events;
 try{
  const names=(await source.db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%'").all()).results.map(r=>r.name);
  tables=[];
  for(const name of names){
   const rows=(await source.db.prepare(`SELECT * FROM ${quoted(name)}`).all()).results;
   if(name==='orders')orders=rows;
   else if(name==='events')events=rows;
   else tables.push({name,rows});
  }
 }finally{await source.close();}
 assert.equal(orders.length,1000);assert.equal(orders.filter(r=>r.status==='NEW').length,100);
 assert.equal(orders.filter(r=>r.status==='CANCELLED').length,50);
 assert.equal(orders.filter(r=>JSON.parse(r.doc).archiveRef).length,850);
 const rt=await runtime({bindings:{AUTH_MODE:'SIGNED_LOCAL'}});
 try{
  // Allocation fixtures cannot become usable identity restores. No trusted JWT
  // keys/project are configured, and copied authority is independently disabled.
  for(const table of tables){
   const rows=table.rows.map(row=>table.name==='auth_users'?{...row,disabled:1,checked_until:0}:
    table.name==='auth_memberships'?{...row,active:0}:table.name==='auth_devices'?{...row,status:'REVOKED'}:
    table.name==='auth_identity_bindings'?{...row,checked_until:0}:table.name==='auth_bootstrap'?{...row,expires_at:0}:row);
   await copyRows(rt.db,table.name,rows);
  }
  const initial=await monitor(rt),checkpoints=[];
  let dayOneMonitorRead,firstScanQuotaFailureDay=null,stoppedBeforeCutoffDay=null,lastBytes=initial.storage.bytes;
  const stopBudget=450000000-Math.max(20000000,2*profile.storageGrowthBeforeArchiveBytes);
  const archiveBatches=Math.ceil(850/12),monitorCalls=archiveBatches*2;
  for(let day=1;day<=90;day++){
   if(lastBytes>=stopBudget){stoppedBeforeCutoffDay=day;break;}
   const ids=new Map(orders.map(row=>[row.id,crypto.randomUUID()]));
   const cohort=orders.map(row=>({...row,id:ids.get(row.id),submission:crypto.randomUUID(),bill_id:row.bill_id?crypto.randomUUID():null}));
   await copyRows(rt.db,'orders',cohort);
   await copyRows(rt.db,'events',events.map(({seq,...row})=>({...row,order_id:ids.get(row.order_id)})));
   const measured=await monitor(rt);
   const allocated=await monitor(rt,true);
   assert.equal(allocated.storage.bytes,measured.storage.bytes);
   assert.ok(allocated.rowsRead<=5,'Allocated monitor must stay bounded as history grows');
   assert.ok(measured.storage.bytes<450000000,'Synthetic growth must stay below the operational cutoff');lastBytes=measured.storage.bytes;
   assert.equal(measured.storage.orderCount,day*1000);
   if(day===1)dayOneMonitorRead=measured.rowsRead;
   const addBackLegacyDayOneMonitor=profile.archiveMonitoring==='ALLOCATED_ONLY'?(dayOneMonitorRead-allocated.rowsRead)*monitorCalls:0;
   const projectedDailyReads=profile.forecastD1RowsReadWith1000DynamicCatalogVisitors+addBackLegacyDayOneMonitor+(measured.rowsRead-dayOneMonitorRead)*monitorCalls;
   if(projectedDailyReads>5000000&&firstScanQuotaFailureDay===null)firstScanQuotaFailureDay=day;
   const snapshot={day,orders:day*1000,protectedNewOrders:day*100,unresolvedCancelledOrders:day*50,
    archivedIdentityRows:day*850,allocatedBytes:measured.storage.bytes,logicalOrderBytes:measured.storage.logicalOrderBytes,
    singleStorageMonitorRowsRead:measured.rowsRead,archiveMonitorCallsPerDay:monitorCalls,
    optimizedAllocatedMonitorRowsRead:allocated.rowsRead,optimizedDailyMonitorRowsRead:allocated.rowsRead*monitorCalls,
    legacyFullSummaryModelOnly:true,modeledDailyRowsReadReplacingOnlyGrowingArchiveMonitors:projectedDailyReads};
   if(day===1||day===30||day===60||day===90||day===firstScanQuotaFailureDay)checkpoints.push(snapshot);
   if(day%10===0||day===1)console.log(JSON.stringify({lines,...snapshot}));
   if(measured.storage.bytes>=stopBudget){if(checkpoints.at(-1)?.day!==day)checkpoints.push(snapshot);}
  }
  const row={lines,cohort:'850 compacted completed identities, 100 full NEW uncollected, 50 full unresolved cancelled per day',
   physicalFixtureOnly:true,initialAllocatedBytes:initial.storage.bytes,checkpoints,firstModeledReadQuotaFailureDay:firstScanQuotaFailureDay,
   stoppedBeforeOperationalCutoffDay:stoppedBeforeCutoffDay,fixtureStopBudgetBytes:stopBudget,sourceMeasuredApiProfile:`phase4c-capacity-${lines}-lines-100-concurrent-10-devices.json`,
   archiveMonitoringBasis:'Actual full-summary SQL versus repaired allocation-only probe on every history size. Legacy model uses two full summaries in each of 71 daily batches (adding back that day-one cost if source profile is already optimized); all other costs held at day-one levels. Repaired cleanup uses the bounded allocation-only probe. Not a 90-day API replay or a final all-cost daily budget.',
   limits:'Fresh synthetic IDs and repeated row bytes test physical allocation/index/scan cost only. Their copied archive references are not new verified exports, accepted orders or finalized bills. Synthetic SQL fixture insertion is not customer acceptance and cannot demonstrate a bypass of the unchanged application cutoff.'};
  results.push(row);
  await writeFile(path.join(evidence,`phase4c-history-growth-${lines}-lines.json`),JSON.stringify(row,null,2));
 }finally{await rt.close();}
}
await writeFile(path.join(evidence,'phase4c-history-growth.json'),JSON.stringify({localOnly:true,productionCapacityVerified:false,results},null,2));
