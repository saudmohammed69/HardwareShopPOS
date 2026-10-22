import assert from 'node:assert/strict';
import {writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {runtime,root} from './runtime.mjs';
import {pruneExpiredAdmission,measureStorage} from './retention.mjs';

const baselineSchema=path.join(root,'evidence','phase4a-schema-before-optimization.sql');
const customers=1000,windows=10,baseWindow=Math.floor(Date.now()/600000);
async function exercise(schemaFile,optimized){
 const r=await runtime({schemaFile});
 try{
  const before=await measureStorage(r),totals={rowsRead:0,rowsWritten:0,statements:0};
  for(let window=0;window<windows;window++){
   for(let start=0;start<customers;start+=100){
    const statements=Array.from({length:100},(_,offset)=>{
     const bucket=`customer-${String(start+offset).padStart(4,'0')}`;
     const sql=optimized
      ?'INSERT INTO admission(shop,bucket,window,count) VALUES(?,?,?,1) ON CONFLICT(shop,bucket) DO UPDATE SET count=CASE WHEN admission.window=excluded.window THEN admission.count+1 ELSE 1 END,window=excluded.window'
      :'INSERT INTO admission(shop,bucket,window,count) VALUES(?,?,?,1) ON CONFLICT(shop,bucket,window) DO UPDATE SET count=count+1';
     return r.db.prepare(sql).bind('proof-shop',bucket,baseWindow+window);
    });
    const results=await r.db.batch(statements);
    for(const result of results){totals.rowsRead+=result.meta.rows_read;totals.rowsWritten+=result.meta.rows_written;totals.statements++;}
   }
  }
  const count=await r.db.prepare('SELECT COUNT(*) n FROM admission').first(),after=await measureStorage(r);
  return {localOnly:true,optimized,customers,distinctTenMinuteWindows:windows,before,after,
    storedAdmissionRows:count.n,rowsRead:totals.rowsRead,rowsWritten:totals.rowsWritten,statements:totals.statements,
    databaseDirectory:r.directory,close:r.close};
 }catch(e){await r.close();throw e;}
}
const baseline=await exercise(baselineSchema,false),optimized=await exercise(path.join(root,'schema.sql'),true);
await baseline.close();await optimized.close();

// Use a separate optimized fixture to measure an owner-triggered stale-counter cleanup.
const cleanup=await runtime();try{
 const now=(baseWindow+windows)*600000,currentDay=Math.floor(now/86400000),currentWindow=baseWindow+windows;
 const stale=Array.from({length:1500},(_,i)=>cleanup.db.prepare('INSERT INTO admission(shop,bucket,window,count) VALUES(?,?,?,1)').bind('proof-shop',`expired-${i}`,currentWindow-1));
 stale.push(cleanup.db.prepare('INSERT INTO admission(shop,bucket,window,count) VALUES(?,?,?,1)').bind('proof-shop','current-window',currentWindow));
 stale.push(cleanup.db.prepare('INSERT INTO daily_admission(shop,day,count) VALUES(?,?,?)').bind('proof-shop',currentDay-1,7));
 stale.push(cleanup.db.prepare('INSERT INTO daily_admission(shop,day,count) VALUES(?,?,?)').bind('proof-shop',currentDay,9));
 const inserted=await cleanup.db.batch(stale),insertMetrics=inserted.reduce((a,x)=>({rowsRead:a.rowsRead+x.meta.rows_read,rowsWritten:a.rowsWritten+x.meta.rows_written}),{rowsRead:0,rowsWritten:0});
 const before=await measureStorage(cleanup),removed=await pruneExpiredAdmission(cleanup,{now}),after=await measureStorage(cleanup);
 const remaining=await cleanup.db.prepare('SELECT COUNT(*) n FROM admission').first();
 assert.equal(removed.expiredAdmissionRows,1500);assert.equal(removed.expiredDailyRows,1);assert.equal(remaining.n,1);
 const output={runtime:'Miniflare 4.20260730.0 / local workerd D1',productionCapacityVerified:false,
  repeatedClientComparison:{baseline:{...baseline,close:undefined},optimized:{...optimized,close:undefined},
   allocatedByteReduction:baseline.after.bytes-optimized.after.bytes,
   allocatedByteReductionPercent:100*(baseline.after.bytes-optimized.after.bytes)/baseline.after.bytes,
   rowReduction:baseline.storedAdmissionRows-optimized.storedAdmissionRows,
   rowReductionPercent:100*(baseline.storedAdmissionRows-optimized.storedAdmissionRows)/baseline.storedAdmissionRows,
   rowWriteReduction:baseline.rowsWritten-optimized.rowsWritten},
  ownerCleanup:{before,insertedSyntheticCounters:1501,syntheticSetupRowsRead:insertMetrics.rowsRead,syntheticSetupRowsWritten:insertMetrics.rowsWritten,
   expiredAdmissionRows:removed.expiredAdmissionRows,expiredDailyRows:removed.expiredDailyRows,
   rowsRead:removed.rowsRead,rowsWritten:removed.rowsWritten,after,remainingAdmissionRows:remaining.n},
  limitations:['The repeated-client fixture uses synthetic windows and direct SQL so it isolates the table key change; it is not a live workload trace.',
   'Allocated size_after is local Miniflare D1 allocation, not production Cloudflare allocation or post-delete reclamation.',
   'New unique client buckets still consume storage until the owner runs verified archive cleanup; cleanup is manual and does not guarantee unlimited storage.']};
 await mkdir(path.join(root,'evidence'),{recursive:true});await writeFile(path.join(root,'evidence','phase4a-admission-storage.json'),JSON.stringify(output,null,2));
 console.log(JSON.stringify(output,null,2));
}finally{await cleanup.close();}
