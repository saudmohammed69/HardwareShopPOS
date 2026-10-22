import {runtime,seed,draft,token} from './runtime.mjs';
import {priceLine} from './pricing.mjs';
import {exportOrders,compactVerified,measureStorage,storagePolicy} from './retention.mjs';
import {copyFile,writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
const r=await runtime(),started=performance.now(),cpuStart=process.cpuUsage(),passphrase='Synthetic retention proof only 2026';
const measured={read:0,written:0,statements:0};
const originalDb=r.db;function record(result){measured.read+=result.meta?.rows_read??0;measured.written+=result.meta?.rows_written??0;measured.statements++;return result;}
function wrap(s){return {sql:s.sql,args:s.args,bind:(...v)=>wrap(s.bind(...v)),all:async()=>record(await s.all()),run:async()=>record(await s.run()),first:async()=>record(await s.all()).results[0]??null};}
r.db={prepare:sql=>wrap(originalDb.prepare(sql)),batch:async statements=>(await originalDb.batch(statements)).map(record)};
try{
 const catalog=await seed(r,3200),initial=await measureStorage(r),ids=[],attestations={};let written=0,read=0,archiveBytes=0;
 const stageMetrics={initial:{...measured}};
 const doc=JSON.stringify({customer:{name:'Synthetic customer',phone:'9876543210'},items:draft(20).items.map((l,i)=>priceLine(catalog[i],l)),note:'Synthetic 20-line local storage workload',missingRequest:'',pickupAt:null,estimatedSubtotalPaisa:'201900',source:'IN_SHOP_QR',taxMode:'FINAL_POS_CONFIRMATION'});
 async function append(day){
  for(let start=0;start<1000;start+=25){const statements=[];
   for(let j=start;j<start+25;j++){
    const id=crypto.randomUUID(),bill=crypto.randomUUID(),status=j<950?'COMPLETED':'CANCELLED',now=Date.now()+day*86400000;
    ids.push(id);attestations[id]={owner:'fixture-owner',resolved:true,collected:true,billId:status==='COMPLETED'?bill:null,reason:'Synthetic finalized/collected or resolved cancellation'};
    statements.push(r.db.prepare("INSERT INTO orders(id,shop,submission,capability_hash,payload_hash,doc,status,revision,operation,bill_id,updated_at) VALUES(?,'proof-shop',?,?,?,?,?,?,?,?,?)").bind(id,crypto.randomUUID(),token(),token(),doc,status,6,id,status==='COMPLETED'?bill:null,now));
    statements.push(r.db.prepare("INSERT INTO admission(shop,bucket,window,count) VALUES('proof-shop',?,?,1)").bind(token(),Math.floor(now/600000)));
    statements.push(r.db.prepare("INSERT INTO daily_admission(shop,day,count) VALUES('proof-shop',?,1) ON CONFLICT(shop,day) DO UPDATE SET count=count+1").bind(Math.floor(now/86400000)));
    for(let rev=1;rev<=6;rev++)statements.push(r.db.prepare("INSERT INTO events(shop,order_id,revision,kind,created_at) VALUES('proof-shop',?,?,?,?)").bind(id,rev,rev===6?status:'SYNTHETIC_STATUS',now));
   }
   for(const x of await r.db.batch(statements)){written+=x.meta.rows_written;read+=x.meta.rows_read;}
  }
 }
 await append(0);const hot=await measureStorage(r);stageMetrics.hot={...measured};console.log('Day 1 inserted:',hot.bytes);
 // Exclude 50 simulated uncollected completed records from cleanup even though terminal.
 const eligible=ids.slice(50);const beforeCleanup=await r.db.prepare('SELECT COUNT(*) n FROM events').first();
 for(let start=0;start<eligible.length;start+=100){
  const a=await exportOrders(r,{ids:eligible.slice(start,start+100),directory:path.join(r.directory,'owner-exports'),passphrase,attestations});
  const copy=a.file+'.independent-copy';await copyFile(a.file,copy);archiveBytes+=a.encryptedBytes;
  await compactVerified(r,a,passphrase,{ownerConfirmed:true,secondCopy:copy});
  console.log('Verified/compacted first day',Math.min(start+100,eligible.length));
 }
 const compacted=await measureStorage(r);stageMetrics.compacted={...measured};await append(1);const secondDay=await measureStorage(r);stageMetrics.secondDay={...measured};console.log('Day 2 inserted:',secondDay.bytes);
 const secondEligible=ids.slice(1050);for(let start=0;start<secondEligible.length;start+=100){const a=await exportOrders(r,{ids:secondEligible.slice(start,start+100),directory:path.join(r.directory,'owner-exports'),passphrase,attestations}),copy=a.file+'.independent-copy';await copyFile(a.file,copy);archiveBytes+=a.encryptedBytes;await compactVerified(r,a,passphrase,{ownerConfirmed:true,secondCopy:copy});console.log('Verified/compacted second day',Math.min(start+100,secondEligible.length));}
 const secondCompacted=await measureStorage(r);stageMetrics.secondCompacted={...measured};await append(2);const thirdDay=await measureStorage(r);stageMetrics.thirdDay={...measured};
 const delta=hot.bytes-initial.bytes,residual=Math.max(secondDay.bytes-hot.bytes,thirdDay.bytes-secondDay.bytes),cpu=process.cpuUsage(cpuStart);
  const result={localOnly:true,description:'Native local D1, 3201 catalog variants, three 1000-order days, 20 lines, six events/order archived to one checkpoint; 50 uncollected/day never compacted; per-order abuse counters included',initial,hot,compacted,secondDay,secondCompacted,thirdDay,archiveBytes,compactedOrders:eligible.length+secondEligible.length,eventsBefore:beforeCleanup.n,eventsAfter:(await r.db.prepare('SELECT COUNT(*) n FROM events').first()).n,
  insertMetrics:{read,written},allSourceD1Metrics:measured,stageMetrics,driverCpuMs:(cpu.user+cpu.system)/1000,elapsedMs:performance.now()-started,projection:{basis:'Maximum of two measured allocated daily growth increments after owner cleanup, not measured 90 days or production capacity',unarchivedDailyGrowth:delta,residualAllocatedDailyGrowth:residual,day90ConservativeBytes:hot.bytes+89*residual,archive90DaysBytes:archiveBytes/2*90,activeBacklogUnbounded:true},
  safety:storagePolicy(thirdDay.bytes),limitations:['Two copies are distinct files on same synthetic disk, not independent physical disaster recovery.','Production disk space, operator cadence, account totals, provider CPU and provider allocation/reuse are unverified.','Tombstones/events persist and eventually fill storage. Active backlog and missed owner cleanup stop admission; no unlimited capacity.']};
 await mkdir('evidence',{recursive:true});await writeFile('evidence/phase3a-retention-capacity.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
}finally{await r.close();}
