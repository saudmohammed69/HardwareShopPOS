// Bounded controlled comparison. All requests settle before fixture teardown.
import {signedFixture} from './security-fixture.mjs';
import {runtime,seed,draft,call} from './runtime.mjs';
import {abuseFixtureBindings} from './customer-abuse-fixture.mjs';
import {writeFile,mkdir} from 'node:fs/promises';
import {monitorEventLoopDelay} from 'node:perf_hooks';
import path from 'node:path';
const label=process.argv.find(x=>x.startsWith('--label='))?.split('=')[1];
if(!label||!/^[a-z0-9-]+$/.test(label))throw Error('Unique safe evidence label required');
await mkdir('evidence',{recursive:true});
const report={label,node:process.version,platform:process.platform,directWorkerd:process.env.PROOF_4J_DIRECT==='1',noReuse:process.env.PROOF_4J_NO_REUSE==='1',attempts:[],resources:[],localOnly:true};
const delay=monitorEventLoopDelay({resolution:20});delay.enable();const start=performance.now();
const sample=setInterval(()=>report.resources.push({at:performance.now()-start,memory:process.memoryUsage(),cpu:process.cpuUsage(),eventLoopMaxMs:delay.max/1e6}),1000);sample.unref();
const output=path.join('evidence',`phase4j-transport-${label}.json`);
// Reserve output now; never overwrite an earlier failed attempt.
await writeFile(output,JSON.stringify({status:'RUNNING',...report}),{encoding:'utf8',flag:'wx'});
async function stage(r,name,fn,count=100){const began=performance.now();const settled=await Promise.allSettled(Array.from({length:count},(_,i)=>fn(i)));
 const errors=settled.flatMap((x,i)=>x.status==='rejected'?[{index:i,message:String(x.reason),code:x.reason.code??x.reason.cause?.code,socket:x.reason.cause?.socket}]:x.value.status>=400?[{index:i,status:x.value.status}]:[]);
 report.attempts.push({name,requests:count,errors,wallMs:performance.now()-began});console.log(JSON.stringify(report.attempts.at(-1)));
}
try{
 const signed=await signedFixture({bindings:{PROOF_TRACE:'true'}});
 try{for(let round=0;round<3;round++)await stage(signed,'signed-read-'+round,()=>signed.send('pos/events?cursor=0',{method:'GET'}));
 report.signed={directory:signed.directory,transport:signed.fixtureTransport,lifecycle:await signed.lifecycle(),apiMeter:await signed.apiMeter()};}finally{await signed.close();}
 const customers=await runtime({pooledTransport:true,bindings:{...abuseFixtureBindings,PROOF_TRACE:'true'}});
 try{await seed(customers,30);for(let round=0;round<3;round++){
 const bodies=Array.from({length:50},()=>draft(5));await stage(customers,'shared-ip-create-'+round,i=>call(customers,'orders',{body:bodies[i],ip:'synthetic-shared-ip'}),50);
 await stage(customers,'shared-ip-duplicate-'+round,i=>call(customers,'orders',{body:bodies[i],ip:'synthetic-shared-ip',abuse:false}),50);}
 report.customers={directory:customers.directory,transport:customers.fixtureTransport,lifecycle:await customers.lifecycle(),apiMeter:await customers.apiMeter(),orders:await customers.db.prepare('SELECT COUNT(*) n FROM orders').first()};}finally{await customers.close();}
 report.status=report.attempts.every(x=>!x.errors.length)?'PASS':'FAIL';
}catch(e){report.status='ERROR';report.error={message:e.stack,code:e.code??e.cause?.code};}
finally{clearInterval(sample);delay.disable();report.wallMs=performance.now()-start;await writeFile(output,JSON.stringify(report));console.log(JSON.stringify({status:report.status,output,wallMs:report.wallMs}));}
process.exitCode=report.status==='PASS'?0:1;
