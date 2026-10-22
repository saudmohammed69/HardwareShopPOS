// Isolated physical-layout experiment. No business/Room schema modification.
import assert from 'node:assert/strict';
import {runtime} from './runtime.mjs';
import {writeFile,mkdir} from 'node:fs/promises';
const label=process.argv.find(x=>x.startsWith('--label='))?.slice(8);assert.match(label??'',/^[a-z0-9-]+$/);
const output=`evidence/phase4j-index-${label}.json`;await mkdir('evidence',{recursive:true});await writeFile(output,'{"status":"RUNNING"}',{flag:'wx'});
const r=await runtime({pooledTransport:true}),result={status:'RUNNING',fixture:r.directory,candidates:[],syntheticOnly:true};
try{
 for(const [name,definition,index,columns,values,where,args]of [
  ['receipts','shop TEXT NOT NULL,device TEXT NOT NULL,request_id TEXT NOT NULL,request_hash TEXT NOT NULL,order_id TEXT NOT NULL,result TEXT NOT NULL CHECK(json_valid(result)),at INTEGER NOT NULL,PRIMARY KEY(device,request_id)','shop,order_id','shop,device,request_id,request_hash,order_id,result,at',i=>['proof-shop','device-'+(i%10),'operation-'+i,'a'.repeat(64),'order-'+i,JSON.stringify({status:200,body:{id:'order-'+i,billId:'bill-'+i}}),i],'device=? AND request_id=?',i=>['device-'+(i%10),'operation-'+i]],
  ['nonces','device TEXT NOT NULL,nonce TEXT NOT NULL,expires_at INTEGER NOT NULL,shop TEXT NOT NULL,PRIMARY KEY(device,nonce)','expires_at','device,nonce,expires_at,shop',i=>['device-'+(i%10),'nonce-'+i,i+1000,'proof-shop'],'device=? AND nonce=?',i=>['device-'+(i%10),'nonce-'+i]],
  ['audit','id TEXT PRIMARY KEY,shop TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,at INTEGER NOT NULL',null,'id,shop,actor,action,target,at',i=>['audit-'+i,'proof-shop','device-'+(i%10),'synthetic-action','target-'+i,i],'id=?',i=>['audit-'+i]]
 ]){
  const variants=[];
  for(const compact of [false,true]){
   const table='probe_'+name+'_'+(compact?'compact':'ordinary');await r.db.prepare(`CREATE TABLE ${table}(${definition})${compact?' WITHOUT ROWID':''}`).run();
   if(index)await r.db.prepare(`CREATE INDEX ${table}_lookup ON ${table}(${index})`).run();
   const before=await r.fixtureSqlMeter(),began=performance.now();
   for(let start=0;start<100;start+=40)await r.db.batch(Array.from({length:Math.min(40,100-start)},(_,j)=>r.db.prepare(`INSERT INTO ${table}(${columns}) VALUES(${values(0).map(()=>'?')})`).bind(...values(start+j))));
   const inserted=await r.fixtureSqlMeter();
   for(let i=0;i<100;i++)assert.deepEqual((await r.db.prepare(`SELECT ${columns} FROM ${table} WHERE ${where}`).bind(...args(i)).first()),Object.fromEntries(columns.split(',').map((c,j)=>[c,values(i)[j]])));
   // Duplicates remain rejected; a key lookup cannot consume or alter a receipt.
   await assert.rejects(()=>r.db.prepare(`INSERT INTO ${table}(${columns}) VALUES(${values(0).map(()=>'?')})`).bind(...values(0)).run());
   const after=await r.fixtureSqlMeter();variants.push({compact,insertionReads:inserted.rowsRead-before.rowsRead,insertionWrites:inserted.rowsWritten-before.rowsWritten,lookupReads:after.rowsRead-inserted.rowsRead,wallMs:performance.now()-began});
  }
  assert.ok(variants[1].insertionWrites<variants[0].insertionWrites);result.candidates.push({name,variants});
 }
 result.status='PASS';result.localSqlMeter=await r.fixtureSqlMeter();
}catch(e){result.status='FAIL';result.error=e.stack;process.exitCode=1;}
finally{await r.close();await writeFile(output,JSON.stringify(result,null,2));console.log(JSON.stringify(result));}
