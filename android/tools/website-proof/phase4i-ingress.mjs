// Controlled local ingress diagnostic. Does not replace 100-customer acceptance.
import {pairedFixture} from './pairing-fixture.mjs';
import {preparePaired} from './pairing-fixture.mjs';
import {call} from './runtime.mjs';
import {writeFile,access} from 'node:fs/promises';
import {monitorEventLoopDelay} from 'node:perf_hooks';
import {freemem,platform} from 'node:os';
const label=process.argv.find(x=>x.startsWith('--label='))?.slice(8)??platform();if(!/^[a-z0-9-]+$/.test(label))throw Error('label');
const file=`evidence/phase4i-ingress-${label}.json`;try{await access(file);throw Error('Preserve prior diagnostic');}catch(e){if(e.code!=='ENOENT')throw e;}
const results=[];
for(const concurrency of [1,20,100]){
 const r=await pairedFixture({bindings:{PROOF_TRACE:'true'}}),lag=monitorEventLoopDelay({resolution:20});lag.enable();
 const samples=[],sample=setInterval(()=>samples.push({at:Date.now(),hostFree:freemem(),...process.memoryUsage()}),1000);let index=0;
 try{await r.lifecycle();const before=r.clientTraces.length,failures=[];const settled=await Promise.allSettled(Array.from({length:concurrency},async()=>{while(index<1000){const i=index++,p=preparePaired(r.worker,'pos/events?cursor=0',{method:'GET'});try{
    const reply=await call(r,p.route,{...p.options,beforeTransmit:()=>{const next=preparePaired(r.worker,p.route,{method:'GET',operationId:p.options.headers['x-operation-id']}).options;r.clientTraces.push({operation:next.headers['x-operation-id'],stage:'signed',clientMonoMs:performance.now(),signatureWallSeconds:Number(next.headers['x-device-time'])});return {headers:next.headers,body:next.raw};}});
    if(reply.status!==200)failures.push({i,status:reply.status});
   }catch(e){failures.push({i,error:String(e),code:e.code??e.cause?.code,socket:e.cause?.socket});}
 }}));
 const trace=await r.lifecycle();results.push({concurrency,logicalOperations:index,unhandled:settled.filter(x=>x.status==='rejected').map(x=>String(x.reason)),failures,api:await r.apiMeter(),transport:r.fixtureTransport,eventLoopP99Ms:lag.percentile(99)/1e6,eventLoopMaxMs:lag.max/1e6,memory:samples,client:trace.client.slice(before),server:trace.server});
 }finally{clearInterval(sample);lag.disable();await r.close();}
 await writeFile(file,JSON.stringify({platform:platform(),results,production:false,signatureLimitSeconds:60,diagnosticOnly:true},null,2));
 console.log(JSON.stringify({concurrency,failures:results.at(-1).failures.length,operations:index}));
}
