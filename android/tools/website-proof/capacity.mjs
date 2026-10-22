import assert from "node:assert/strict";
import {writeFile,mkdir} from "node:fs/promises";
import {gzipSync} from "node:zlib";
import {spawnSync} from "node:child_process";
import path from "node:path";
import {runtime,seed,draft,call,token,root} from "./runtime.mjs";
const evidence=path.join(root,"evidence");await mkdir(evidence,{recursive:true});
const check=r=>{assert.ok(r.status<300,JSON.stringify(r));return r.body;};
async function pool(values,concurrency,fn){let i=0;await Promise.all(Array.from({length:concurrency},async()=>{while(i<values.length){const n=i++;await fn(values[n],n);}}));}
const percentile=(xs,p)=>[...xs].sort((a,b)=>a-b)[Math.min(xs.length-1,Math.floor(xs.length*p))];
async function dbSize(rt){const result=await rt.db.prepare("SELECT id FROM catalog LIMIT 1").all();
  assert.ok(Number.isFinite(result.meta.size_after),"D1 must provide measured size_after");return result.meta.size_after;}
function workerdCpu(){
  if(process.platform!=="win32")return null;
  const script=`$taskChildren=Get-CimInstance Win32_Process -Filter "ParentProcessId=${process.pid}" | Where-Object Name -EQ 'workerd.exe'; $taskCpu=0; foreach($taskChild in $taskChildren){$taskCpu+=(Get-Process -Id $taskChild.ProcessId).TotalProcessorTime.TotalMilliseconds}; [Console]::Write($taskCpu)`;
  const result=spawnSync("powershell.exe",["-NoProfile","-NonInteractive","-Command",script],{encoding:"utf8",windowsHide:true,timeout:15000});
  const value=Number(result.stdout?.trim());return result.status===0 && Number.isFinite(value)?value:null;
}
const results=[];
for(const lines of [5,20])for(const concurrent of [20,100]){
  const rt=await runtime();
  try{
    const rows=await seed(rt,3200),before=await dbSize(rt),nodeBefore=process.cpuUsage(),workerdBefore=workerdCpu(),start=performance.now();
    let catalogAfter="",catalogReads=0,catalogRequests=0;
    do{const readBefore=rt.metrics.read;const response=check(await call(rt,`catalog?after=${catalogAfter}`,{method:"GET"}));catalogReads+=rt.metrics.read-readBefore;catalogRequests++;catalogAfter=response.next;}while(catalogAfter);
    const orders=Array.from({length:1000},()=>draft(lines));let accepted=0,cancelled=0,lostResponses=0;
    await pool(orders,concurrent,async(body,i)=>{
      const created=check(await call(rt,"orders",{body}));let revision=created.revision;
      if(i%10===0){const duplicate=check(await call(rt,"orders",{body}));assert.equal(duplicate.id,created.id);}
      if(i%20===0){lostResponses++;const recovered=check(await call(rt,"recover",{body:{submissionId:body.submissionId,capability:body.capability}}));assert.equal(recovered.id,created.id);}
      if(i%10===0){const changed={customer:body.customer,items:body.items.map((line,index)=>({...line,quantity:index===0?"2":line.quantity})),note:"Customer edit"};
        revision=check(await call(rt,`orders/${created.id}/edit`,{body:{expectedRevision:revision,order:changed},capability:body.capability})).revision;}
      if(i%20===1){check(await call(rt,`orders/${created.id}/customer-cancel`,{body:{expectedRevision:revision},capability:body.capability}));cancelled++;return;}
      const auth=i%2?"fixture-worker-a":"fixture-worker-b";
      for(const action of ["accept","prepare","ready"]){revision=check(await call(rt,`orders/${created.id}/${action}`,{auth,body:{expectedRevision:revision}})).revision;}
      const claimToken=token(),claim=check(await call(rt,`orders/${created.id}/billing-claim`,{auth,body:{expectedRevision:revision,claimToken}}));
      const bodyComplete={expectedRevision:claim.revision,claimToken,billId:claim.billId};
      check(await call(rt,`orders/${created.id}/complete`,{auth,body:bodyComplete}));accepted++;
      if(i%10===0)check(await call(rt,`orders/${created.id}/complete`,{auth,body:bodyComplete}));
    });
    let cursor=0,events=0,syncMs=[],syncRequests=0;
    for(const auth of ["fixture-worker-a","fixture-worker-b","fixture-owner"]){cursor=0;
      while(true){const began=performance.now(),feed=check(await call(rt,`pos/events?cursor=${cursor}`,{method:"GET",auth}));syncMs.push(performance.now()-began);syncRequests++;
        if(auth==="fixture-worker-a")events+=feed.events.length;const old=cursor;cursor=feed.cursor;if(feed.events.length===0)break;
        if(syncRequests%10===0){const repeat=check(await call(rt,`pos/events?cursor=${old}`,{method:"GET",auth}));assert.equal(repeat.cursor,cursor);}}
    }
    const elapsed=performance.now()-start,nodeCpu=process.cpuUsage(nodeBefore),workerAfter=workerdCpu(),after=await dbSize(rt);
    const counts=await rt.db.prepare("SELECT COUNT(*) n,SUM(status='COMPLETED') completed,SUM(status='CANCELLED') cancelled,COUNT(DISTINCT submission) unique_submissions,COUNT(DISTINCT bill_id) unique_bills FROM orders").first();
    assert.equal(counts.n,1000);assert.equal(counts.unique_submissions,1000);assert.equal(counts.completed,950);assert.equal(counts.cancelled,50);assert.equal(counts.unique_bills,950);
    const metrics={...rt.metrics};delete metrics.latencies;
    const driverCpuMs=(nodeCpu.user+nodeCpu.system)/1000,localWorkerdCpuMs=workerdBefore!==null && workerAfter!==null?workerAfter-workerdBefore:null;
    const result={lines,concurrent,orders:1000,completed:accepted,cancelled,lostResponseRecoveryChecks:lostResponses,
      elapsedMs:elapsed,requests:metrics.requests,rowsRead:metrics.read,rowsWritten:metrics.written,catalogSeed:rt.seedMetrics,
      catalogFullRead:{requests:catalogRequests,rowsRead:catalogReads},events,syncRequests,syncPageWallP95Ms:percentile(syncMs,.95),
      requestWallP50Ms:percentile(rt.metrics.latencies,.5),requestWallP95Ms:percentile(rt.metrics.latencies,.95),requestWallMaxMs:Math.max(...rt.metrics.latencies),
      driverCpuMs,localWorkerdCpuMs,localAggregateCpuMs:localWorkerdCpuMs===null?null:localWorkerdCpuMs+driverCpuMs,
      cloudCpuPerRequestVerified:false,storageBeforeBytes:before,storageAfterBytes:after,storageGrowthBytes:after-before,
      bytesGrowthPerOrder:(after-before)/1000,localPersistencePath:rt.directory,
      model:{directCatalog1000CustomersRowsRead:metrics.read-catalogReads+catalogReads*1000,
        directCatalog1000CustomersRequests:metrics.requests-catalogRequests+catalogRequests*1000,
        staticCatalogScenarioRowsRead:metrics.read,staticCatalogScenarioRequests:metrics.requests,
        writesWithDailyFullCatalogRepublish:metrics.written+rt.seedMetrics.written,
        additionalForegroundPollsFiveDevices10Hours60Seconds:3000,
        retention30DaysBytes:before+(after-before)*30,retention90DaysBytes:before+(after-before)*90}}
    results.push(result);await writeFile(path.join(evidence,"capacity-results.json"),JSON.stringify({runtime:"Miniflare 4.20260730.0 / local workerd D1",productionCapacityVerified:false,results},null,2));
    await writeFile(path.join(evidence,"synthetic-public-catalog.json.gz"),gzipSync(JSON.stringify(rows)));
    console.log(JSON.stringify({lines,concurrent,orders:1000,completed:accepted,requests:result.requests,rowsRead:result.rowsRead,rowsWritten:result.rowsWritten,storageGrowthBytes:result.storageGrowthBytes,elapsedMs:Math.round(elapsed)}));
  }finally{await rt.close();}
}
const indexed=await runtime();try{
  await indexed.db.prepare("CREATE TABLE probe_plain(id INTEGER PRIMARY KEY,shop TEXT,revision INTEGER,payload TEXT)").run();
  await indexed.db.prepare("CREATE TABLE probe_indexed(id INTEGER PRIMARY KEY,shop TEXT,revision INTEGER,payload TEXT)").run();
  await indexed.db.prepare("CREATE INDEX probe_lookup ON probe_indexed(shop,revision)").run();
  const data={};for(const table of ["probe_plain","probe_indexed"]){let inserted=0,updated=0;
    for(let begin=0;begin<1000;begin+=100){const writes=await indexed.db.batch(Array.from({length:100},(_,j)=>indexed.db.prepare(`INSERT INTO ${table} VALUES(?,?,?,?)`).bind(begin+j,"shop",1,"synthetic")));
      inserted+=writes.reduce((s,r)=>s+r.meta.rows_written,0);}
    const update=await indexed.db.prepare(`UPDATE ${table} SET revision=2 WHERE shop='shop'`).run();updated=update.meta.rows_written;
    const plan=await indexed.db.prepare(`EXPLAIN QUERY PLAN SELECT * FROM ${table} WHERE shop='shop' AND revision=2`).all();
    data[table]={insertedRowsWritten:inserted,updatedRowsWritten:updated,queryPlan:plan.results};}
  assert.ok(data.probe_indexed.insertedRowsWritten>data.probe_plain.insertedRowsWritten);assert.ok(data.probe_indexed.updatedRowsWritten>data.probe_plain.updatedRowsWritten);
  await writeFile(path.join(evidence,"index-overhead.json"),JSON.stringify(data,null,2));console.log("Index overhead verified:",JSON.stringify(data));
}finally{await indexed.close();}
console.log("All four 1,000-order workloads passed locally. Production CPU, quotas and account eligibility remain unverified.");
