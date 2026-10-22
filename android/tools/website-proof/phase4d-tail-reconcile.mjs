// Supplement earlier harness runs without changing their original metrics/files.
// Only CURRENTLY EXPIRED proof metadata in disposable fixtures may be removed.
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
import assert from 'node:assert/strict';
import {runtime,root} from './runtime.mjs';
export async function reconcileTails(){
 const evidence=path.join(root,'evidence'),results=[];
 for(const lines of [5,20])for(const customers of [20,100])for(const devices of [5,10]){
  const file=`phase4d-capacity-${lines}-lines-${customers}-concurrent-${devices}-devices.json`;
  const bytes=await readFile(path.join(evidence,file)),s=JSON.parse(bytes).result;
  const directory=path.resolve(s.localPersistencePath);assert.ok(directory.startsWith(path.join(root,'.local-state')+path.sep));
  assert.match(path.basename(directory),/^[a-f0-9-]{36}$/);
  const priorFile=path.join(evidence,file.replace('.json','-tail-reconciliation.json'));
  try{const prior=JSON.parse(await readFile(priorFile,'utf8'));assert.equal(prior.originalProfileSha256,createHash('sha256').update(bytes).digest('hex'));results.push(prior);continue;}catch(e){if(e.code!=='ENOENT')throw e;}
  const r=await runtime({directory,initialise:false});let rowsRead=0,rowsWritten=0;
  const query=async(sql,args=[])=>{const result=await r.db.prepare(sql).bind(...args).all();rowsRead+=result.meta.rows_read;rowsWritten+=result.meta.rows_written;return result;};
  const snapshot=async()=>{const data={};for(const t of ['orders','events','auth_audit','auth_claim_reviews','auth_local_worker_bindings'])data[t]=(await query(`SELECT * FROM ${t} ORDER BY rowid`)).results;return createHash('sha256').update(JSON.stringify(data)).digest('hex');};
  try{
   const original=await snapshot(),proofs=(await query('SELECT device,nonce,expires_at,shop FROM auth_nonces')).results;
   const now=Math.floor(Date.now()/1000);assert.ok(proofs.every(p=>p.expires_at<now),'Never delete a currently valid proof; wait for real expiry');
   const removed=await query('DELETE FROM auth_nonces WHERE expires_at<?',[now]);
   assert.equal(await snapshot(),original,'Order/event/audit/claim/binding evidence changed');
   const size=(await query('SELECT id FROM orders LIMIT 1')).meta.size_after;
   const row={profile:[lines,customers,devices],originalProfileSha256:createHash('sha256').update(bytes).digest('hex'),
    originalSnapshotBeforeFutureMaintenance:s.tenantArchive.snapshotBeforeFutureMaintenance===true,
    correction:'Earlier profiles placed the final read-only archive POST after simulated future maintenance. Raw metrics are retained; this supplement measures the remaining proof cleanup only after REAL expiry, with no source signed API calls afterward. It does not retroactively claim a clock-consistent full workflow repeat.',
    currentlyExpiredProofsRemoved:proofs.length,cleanupRowsWritten:removed.meta.rows_written,totalSupplementRowsRead:rowsRead,totalSupplementRowsWritten:rowsWritten,
    actualTimeSeconds:now,latestRemovedExpirySeconds:Math.max(0,...proofs.map(p=>p.expires_at)),businessEvidenceSha256BeforeAndAfter:original,
    businessEvidenceUnchanged:true,allocatedBytesAfterRealExpiry:size};
   await writeFile(priorFile,JSON.stringify(row,null,2));results.push(row);
  }finally{await r.close();}
 }
 await writeFile(path.join(evidence,'phase4d-tail-reconciliation.json'),JSON.stringify({localOnly:true,productionCapacityVerified:false,results},null,2));return results;
}
