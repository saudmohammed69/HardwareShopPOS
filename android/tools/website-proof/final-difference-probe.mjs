// Local transport isolation only. No security/order bypass is exported or used
// by the application. All in-flight calls settle before the fixture is closed.
import {Miniflare,Log,LogLevel} from 'miniflare';
import {enablePooledFixture} from './pooled-fixture.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import {randomUUID,randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
const label=process.argv[2];assert.match(label??'',/^[a-z0-9-]+$/);
const out=`evidence/final-engineering/difference-${label}.json`;
await mkdir('evidence/final-engineering',{recursive:true});
await writeFile(out,'{"status":"RUNNING"}',{flag:'wx'});
const directory=`.local-state/final-difference-${label}`;await mkdir(directory,{recursive:false});
const key=randomBytes(32).toString('hex');
const mf=new Miniflare({modules:true,compatibilityDate:'2026-07-30',cf:false,host:'127.0.0.1',port:0,
 d1Databases:{DB:'control'},d1Persist:directory,bindings:{KEY:key},log:new Log(LogLevel.ERROR),
 script:`const seen=[];export default {async fetch(request,env){
 if(request.headers.get('x-control-key')!==env.KEY)return new Response(null,{status:403});
 const url=new URL(request.url),attempt=request.headers.get('x-proof-attempt-id');
 if(url.pathname==='/setup'){await env.DB.prepare('CREATE TABLE rows(id TEXT PRIMARY KEY,n INTEGER)').run();await env.DB.batch(Array.from({length:20},(_,i)=>env.DB.prepare('INSERT INTO rows VALUES(?,?)').bind(String(i),i)));return Response.json({ok:true});}
 if(url.pathname==='/trace')return Response.json(seen);
 seen.push({attempt,stage:'entry',at:Date.now()});
 try{const body=await request.json();let reads=0,writes=0,sum=0;
 if(url.pathname.includes('crypto')){for(let i=0;i<8;i++)await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(body)));}
 if(url.pathname.includes('sequential')){for(let i=0;i<20;i++){const r=await env.DB.prepare('SELECT n FROM rows WHERE id=?').bind(String(i)).all();reads+=r.meta.rows_read;sum+=r.results[0].n;}}
 else {const r=await env.DB.prepare('SELECT n FROM rows WHERE id IN ('+Array(20).fill('?').join(',')+')').bind(...Array.from({length:20},(_,i)=>String(i))).all();reads=r.meta.rows_read;sum=r.results.reduce((n,x)=>n+x.n,0);}
 const results=await env.DB.batch(Array.from({length:20},(_,i)=>env.DB.prepare('UPDATE rows SET n=n WHERE id=?').bind(String(i))));writes=results.reduce((n,r)=>n+r.meta.rows_written,0);
 seen.push({attempt,stage:'done',at:Date.now()});return Response.json({attempt,sum,reads,writes});
 }catch(e){seen.push({attempt,stage:'error',error:String(e),at:Date.now()});return Response.json({error:String(e)},{status:500});}
 }};`});
const pool=await enablePooledFixture(mf),report={label,directory,node:process.version,platform:process.platform,classification:'LOCAL_DIFFERENCE_CONTROL_ONLY',stages:[]};
try{
 assert.equal((await mf.dispatchFetch('https://proof.invalid/setup',{headers:{'x-control-key':key}})).status,200);
 for(const mode of ['batch','sequential','crypto-sequential']){
  const began=performance.now();let rowsRead=0,rowsWritten=0;
  const results=await Promise.allSettled(Array.from({length:100},async()=>{
   const attempt=randomUUID(),r=await mf.dispatchFetch('https://proof.invalid/'+mode,{method:'POST',headers:{'content-type':'application/json','x-control-key':key,'x-proof-trace-id':attempt,'x-proof-attempt-id':attempt},body:JSON.stringify({items:Array.from({length:20},(_,i)=>({productId:String(i),quantity:'1'}))})});
   assert.equal(r.status,200);const b=await r.json();assert.equal(b.attempt,attempt);assert.equal(b.sum,190);rowsRead+=b.reads;rowsWritten+=b.writes;
  }));
  const errors=results.flatMap((x,i)=>x.status==='rejected'?[{i,error:String(x.reason),code:x.reason.code??x.reason.cause?.code,socket:x.reason.cause?.socket}]:[]);
  const stage={mode,concurrent:100,passed:100-errors.length,errors,wallMs:performance.now()-began,rowsRead,rowsWritten};report.stages.push(stage);console.log(JSON.stringify(stage));
 }
 report.server=await (await mf.dispatchFetch('https://proof.invalid/trace',{headers:{'x-control-key':key}})).json();
 report.status=report.stages.every(x=>!x.errors.length)?'PASS':'FAIL';
}catch(e){report.status='ERROR';report.error=e.stack;}
finally{report.client=pool.traces;report.sockets=pool.sockets;await pool.close();await mf.dispose();await writeFile(out,JSON.stringify(report));}
process.exitCode=report.status==='PASS'?0:1;
