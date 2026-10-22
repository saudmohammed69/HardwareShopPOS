// Transport control ONLY. Not an order service, security test or capacity pass.
import {Miniflare,Log,LogLevel} from 'miniflare';
import {enablePooledFixture} from './pooled-fixture.mjs';
import {draft} from './runtime.mjs';
import {challengeHeaders} from './customer-abuse-fixture.mjs';
import {randomUUID,randomBytes} from 'node:crypto';
import {writeFile,mkdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const label=process.argv.find(x=>x.startsWith('--label='))?.slice(8);assert.match(label??'',/^[a-z0-9-]+$/);
const output=`evidence/phase4j-wire-${label}.json`;await writeFile(output,'{"status":"RUNNING"}',{flag:'wx'});
const controlKey=randomBytes(32).toString('hex'),direct=process.env.PROOF_4J_DIRECT==='1',withD1=process.env.PROOF_4J_CONTROL_D1==='1',lines=Number(process.env.PROOF_4J_CONTROL_LINES??5);assert.ok([5,20].includes(lines));
// Default behavior preserves the old control. Opt-in modes match real GET/D1 scheduling.
const dbMode=process.env.PROOF_4J_CONTROL_DB_MODE??'batch-write';
assert.ok(['batch-write','sequential-read','batch-read'].includes(dbMode));
assert.ok(withD1||dbMode==='batch-write','Read controls require D1');
const getConcurrency=Number(process.env.PROOF_4J_CONTROL_GET_CONCURRENCY??100);
const postConcurrency=Number(process.env.PROOF_4J_CONTROL_POST_CONCURRENCY??50);
assert.ok([20,50,100].includes(getConcurrency)&&[20,50,100].includes(postConcurrency));
const getRounds=Number(process.env.PROOF_4J_CONTROL_GET_ROUNDS??3);
const postRounds=Number(process.env.PROOF_4J_CONTROL_POST_ROUNDS??6);
assert.ok(Number.isInteger(getRounds)&&getRounds>=0&&getRounds<=3);
assert.ok(Number.isInteger(postRounds)&&postRounds>=0&&postRounds<=6&&getRounds+postRounds>0);
const directory=`.local-state/phase4j-wire-${label}`;if(withD1)await mkdir(directory,{recursive:false});
const mf=new Miniflare({modules:true,script:`const seen=[];export default {async fetch(request,env){
 if(request.headers.get('x-control-key')!==env.CONTROL_KEY)return new Response(null,{status:403});
 const url=new URL(request.url);if(url.pathname==='/__readtraces')return Response.json(seen);
 if(url.pathname==='/__setup'){await env.DB.prepare('CREATE TABLE control_rows(id TEXT PRIMARY KEY,attempt TEXT NOT NULL,line INTEGER NOT NULL,bytes INTEGER NOT NULL)').run();if(env.CONTROL_DB_MODE!=='batch-write')await env.DB.prepare('INSERT INTO control_rows VALUES(?,?,0,0)').bind('diagnostic-read-row','setup').run();return Response.json({created:true});}
 const attempt=request.headers.get('x-proof-attempt-id');seen.push({attempt,at:Date.now()});
 const bytes=(await request.arrayBuffer()).byteLength;let written=0,read=0;
 if(env.WITH_D1==='true'&&env.CONTROL_DB_MODE==='batch-write'&&request.method==='POST'){const results=await env.DB.batch(Array.from({length:Number(env.CONTROL_LINES)},(_,i)=>env.DB.prepare('INSERT INTO control_rows(id,attempt,line,bytes) VALUES(?,?,?,?)').bind(attempt+'-'+i,attempt,i,bytes)));written=results.reduce((n,r)=>n+r.meta.rows_written,0);}
 if(env.WITH_D1==='true'&&env.CONTROL_DB_MODE==='sequential-read'){
  for(let i=0;i<Number(env.CONTROL_LINES);i++){
   const result=await env.DB.prepare('SELECT id FROM control_rows WHERE id=? LIMIT 1').bind('diagnostic-read-row').all();
   read+=result.meta.rows_read;
  }
 }
 if(env.WITH_D1==='true'&&env.CONTROL_DB_MODE==='batch-read'){
  const results=await env.DB.batch(Array.from({length:Number(env.CONTROL_LINES)},()=>env.DB.prepare('SELECT id FROM control_rows WHERE id=? LIMIT 1').bind('diagnostic-read-row')));
  read=results.reduce((n,r)=>n+r.meta.rows_read,0);
 }
 return Response.json({attempt,bytes,url:request.url,written,read});}}`,
 compatibilityDate:'2026-07-30',host:'127.0.0.1',port:0,cf:false,bindings:{CONTROL_KEY:controlKey,WITH_D1:String(withD1),CONTROL_LINES:String(lines),CONTROL_DB_MODE:dbMode},...(withD1?{d1Databases:{DB:'phase4j-wire-only'},d1Persist:directory}:{}),
 ...(direct?{unsafeDirectSockets:[{host:'127.0.0.1',port:0,proxy:true}]}:{}),log:new Log(LogLevel.ERROR)});
const pool=await enablePooledFixture(mf),report={label,classification:'TRANSPORT_CONTROL_ONLY',noOrderOrSecurityHandlers:true,withD1,dbMode,lines,getConcurrency,postConcurrency,getRounds,postRounds,syntheticStateDirectory:withD1?directory:null,direct,noReuse:process.env.PROOF_4J_NO_REUSE==='1',stages:[]};
async function stage(name,count,post=false){const bodies=Array.from({length:count},()=>draft(5)),began=performance.now();
 const results=await Promise.allSettled(bodies.map(async body=>{
  const attempt=randomUUID(),route='https://proof.invalid/shops/proof-shop/orders',raw=post?JSON.stringify(body):undefined;
  const response=await mf.dispatchFetch(route,{method:post?'POST':'GET',headers:{...challengeHeaders(body),'x-control-key':controlKey,'x-proof-trace-id':body.submissionId,'x-proof-attempt-id':attempt,...(post?{'content-type':'application/json'}:{})},body:raw});
  assert.equal(response.status,200);const parsed=await response.json();assert.equal(parsed.attempt,attempt);assert.equal(parsed.url,route);assert.equal(parsed.bytes,post?Buffer.byteLength(raw):0);
 }));
 const errors=results.flatMap((r,i)=>r.status==='rejected'?[{index:i,message:String(r.reason),code:r.reason.code??r.reason.cause?.code,socket:r.reason.cause?.socket}]:[]);
 report.stages.push({name,count,errors,wallMs:performance.now()-began});console.log(JSON.stringify(report.stages.at(-1)));
}
try{
 if(withD1){const setup=await mf.dispatchFetch('https://proof.invalid/__setup',{method:'POST',headers:{'x-control-key':controlKey}});assert.equal(setup.status,200);}
 for(let i=0;i<getRounds;i++)await stage('get-'+i,getConcurrency);
 for(let i=0;i<postRounds;i++)await stage('post-'+i,postConcurrency,true);
 report.server=await (await mf.dispatchFetch('https://proof.invalid/__readtraces',{headers:{'x-control-key':controlKey}})).json();
 report.status=report.stages.every(x=>!x.errors.length)?'PASS':'FAIL';
}catch(e){report.status='ERROR';report.error=e.stack;}
finally{report.transport=pool.stats;report.client=pool.traces;report.sockets=pool.sockets;await pool.close();await mf.dispose();await writeFile(output,JSON.stringify(report));console.log(JSON.stringify({status:report.status,output}));}
process.exitCode=report.status==='PASS'?0:1;
