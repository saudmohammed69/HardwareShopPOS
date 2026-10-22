// Loopback test driver only. Kept separate from the backend implementation.
import backend from "./worker.mjs";
import {drainTraces,requestTrace} from './lifecycle-trace.mjs';
import {proofCostSummary} from './proof-cost-meter.mjs';
const meter={requests:0,rowsRead:0,rowsWritten:0,queries:0,maxQueries:0};
const sqlMeter={requests:0,rowsRead:0,rowsWritten:0,queries:0,maxQueries:0,peakAllocatedBytes:0};
export default {async fetch(request,env){
  const trace=requestTrace(request,env);trace('fixture-entry');
  const url=new URL(request.url);
  if(url.pathname==='/__local_fixture_sql_meter'){
    if(env.LOCAL_PROOF_ONLY!=='true'||request.method!=='POST'||request.headers.get('x-fixture-key')!==env.LOCAL_FIXTURE_KEY)return new Response(null,{status:403});
    return Response.json(sqlMeter);
  }
  if(url.pathname==='/__local_fixture_traces'){
    if(env.LOCAL_PROOF_ONLY!=='true'||request.method!=='POST'||request.headers.get('x-fixture-key')!==env.LOCAL_FIXTURE_KEY)return new Response(null,{status:403});
    return Response.json(drainTraces());
  }
  if(url.pathname==='/__local_fixture_meter'){
    if(env.LOCAL_PROOF_ONLY!=='true'||request.method!=='POST'||request.headers.get('x-fixture-key')!==env.LOCAL_FIXTURE_KEY)return new Response(null,{status:403});
    return Response.json({...meter,byTable:proofCostSummary().byTable});
  }
  if(url.pathname!=="/__local_fixture_sql"){
    let response;
    try{response=await backend.fetch(request,env);trace('fixture-backend-returned',{status:response.status});}
    catch(error){trace('fixture-backend-threw',{errorName:error?.name??'Error'});throw error;}
    // Count in workerd before the outer loopback response can be lost. This is
    // volatile fixture evidence, not a durable production quota meter.
    meter.requests++;meter.rowsRead+=Number(response.headers.get('x-proof-db-rows-read')??0);
    meter.rowsWritten+=Number(response.headers.get('x-proof-db-rows-written')??0);
    const queries=Number(response.headers.get('x-proof-db-queries')??0);meter.queries+=queries;meter.maxQueries=Math.max(meter.maxQueries,queries);
    return response;
  }
  if(env.LOCAL_PROOF_ONLY!=="true" || request.method!=="POST" || request.headers.get("x-fixture-key")!==env.LOCAL_FIXTURE_KEY)return new Response(null,{status:403});
  try{
    const body=await request.json();let result;
    if(body.batch)result=await env.DB.batch(body.batch.map(s=>env.DB.prepare(s.sql).bind(...s.args)));
    else result=await env.DB.prepare(body.sql).bind(...body.args).all();
    const results=Array.isArray(result)?result:[result];sqlMeter.requests++;sqlMeter.queries+=results.length;sqlMeter.maxQueries=Math.max(sqlMeter.maxQueries,results.length);
    for(const r of results){sqlMeter.rowsRead+=r.meta.rows_read??0;sqlMeter.rowsWritten+=r.meta.rows_written??0;sqlMeter.peakAllocatedBytes=Math.max(sqlMeter.peakAllocatedBytes,r.meta.size_after??0);}
    return Response.json(result);
  }catch(e){return Response.json({error:String(e)},{status:500});}
}};
