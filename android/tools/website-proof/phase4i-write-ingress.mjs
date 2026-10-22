// Diagnostic only: first-attempt writes, no retry or disposition of uncertain
// requests. All clients settle before evidence collection/fixture teardown.
import {pairedFixture} from './pairing-fixture.mjs';
import {abuseFixtureBindings} from './customer-abuse-fixture.mjs';
import {call,draft} from './runtime.mjs';
import {writeFile,access} from 'node:fs/promises';
import {freemem,platform} from 'node:os';
import {monitorEventLoopDelay} from 'node:perf_hooks';
const label=process.argv.find(x=>x.startsWith('--label='))?.slice(8)??platform();if(!/^[a-z0-9-]+$/.test(label))throw Error('label');
const output=`evidence/phase4i-write-ingress-${label}.json`;try{await access(output);throw Error('Preserve previous attempt');}catch(e){if(e.code!=='ENOENT')throw e;}
const results=[];
for(const concurrency of [1,20,100]){
 const r=await pairedFixture({bindings:{...abuseFixtureBindings,PROOF_TRACE:'true'}}),lag=monitorEventLoopDelay({resolution:20}),samples=[],replies=[],failures=[];let index=0;
 const inputs=Array.from({length:100},()=>draft(20));lag.enable();const sample=setInterval(()=>samples.push({at:Date.now(),free:freemem(),...process.memoryUsage()}),1000);
 try{await r.lifecycle();await Promise.allSettled(Array.from({length:concurrency},async()=>{while(index<inputs.length){const i=index++,body=inputs[i];try{const reply=await call(r,'orders',{body,ip:'diagnostic-shared-carrier'});replies.push({submission:body.submissionId,status:reply.status,id:reply.body.id??null});}catch(e){failures.push({submission:body.submissionId,code:e.code??e.cause?.code,error:String(e),socket:e.cause?.socket});}}}));
 const persisted=(await r.db.prepare('SELECT submission,id,status,bill_id FROM orders ORDER BY submission').all()).results,trace=await r.lifecycle();
 results.push({concurrency,firstAttempts:inputs.length,replies,failures:failures.map(f=>({...f,committedTrace:trace.server.some(t=>t.operation===f.submission&&t.stage==='transaction-committed'),observedPersisted:persisted.some(p=>p.submission===f.submission)})),persisted,api:await r.apiMeter(),trace,eventLoopP99Ms:lag.percentile(99)/1e6,eventLoopMaxMs:lag.max/1e6,samples,fixture:r.directory,originalsNotReplayed:true});
 }finally{clearInterval(sample);lag.disable();await r.close();}
 await writeFile(output,JSON.stringify({results,platform:platform(),production:false,diagnosticOnly:true,signatureBoundUnchanged:true},null,2));console.log(JSON.stringify({concurrency,failures:results.at(-1).failures.length,persisted:results.at(-1).persisted.length}));
}
