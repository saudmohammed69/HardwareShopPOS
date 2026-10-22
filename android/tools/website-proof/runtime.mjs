import {Miniflare,Log,LogLevel} from "miniflare";
import {readFile,mkdir} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {randomUUID,randomBytes} from "node:crypto";
import {projectProduct} from "./worker.mjs";
import {enablePooledFixture} from './pooled-fixture.mjs';
import {challengeHeaders} from './customer-abuse-fixture.mjs';
import {enableNodeHttpFixture} from './node-http-fixture.mjs';
export const root=path.dirname(fileURLToPath(import.meta.url));
export const token=()=>randomBytes(32).toString("hex");
export const identities={
  "fixture-owner-a":{uid:"owner-uid",device:"device-a",shop:"proof-shop",role:"OWNER",expiresAt:4102444800000},
  "fixture-owner-b":{uid:"owner-uid",device:"device-b",shop:"proof-shop",role:"OWNER",expiresAt:4102444800000},
  "fixture-owner":{uid:"owner-uid",device:"device-owner",shop:"proof-shop",role:"OWNER",expiresAt:4102444800000},
  "fixture-worker-a":{uid:"shared-staff-uid",device:"device-a",shop:"proof-shop",role:"WORKER",expiresAt:4102444800000},
  "fixture-worker-b":{uid:"shared-staff-uid",device:"device-b",shop:"proof-shop",role:"WORKER",expiresAt:4102444800000},
  "fixture-expired":{uid:"old",device:"old",shop:"proof-shop",role:"OWNER",expiresAt:1},
  "fixture-other-shop":{uid:"other",device:"other",shop:"other-shop",role:"OWNER",expiresAt:4102444800000}
};
export async function runtime({directory=path.join(root,".local-state",randomUUID()),bindings={},initialise=true,schemaFile=path.join(root,"schema.sql"),pooledTransport=process.env.PROOF_POOLED_TRANSPORT==='1'}={}){
  await mkdir(directory,{recursive:true});
  const fixtureKey=token();
  const env={LOCAL_PROOF_ONLY:"true",AUTH_MODE:"FIXTURE",PROOF_SHOP:"proof-shop",LOCAL_FIXTURE_KEY:fixtureKey,LOCAL_IDENTITIES:JSON.stringify(identities),
    LOCAL_RATE_KEY:"synthetic-rate-hmac-key-only-no-cloud-secret",...bindings};
  const direct=process.env.PROOF_4J_DIRECT==='1';
  const mf=new Miniflare({modules:true,scriptPath:path.join(root,"d1-fixture.mjs"),compatibilityDate:"2026-07-30",
    d1Databases:{DB:"local-proof-database"},d1Persist:directory,bindings:env,host:"127.0.0.1",port:0,
    ...(direct?{unsafeDirectSockets:[{host:'127.0.0.1',port:0,proxy:true}]}:{}),
    ...(process.env.PROOF_4J_NATIVE_TLS==='1'?{https:true,httpsKeyPath:path.join(root,'evidence/phase4i-tls-fixture/localhost-key.pem'),httpsCertPath:path.join(root,'evidence/phase4i-tls-fixture/localhost-cert.pem')}:{}),
    cf:false,log:new Log(process.env.PROOF_DEBUG?LogLevel.DEBUG:LogLevel.ERROR)});
  let pool;
  try {
    if(pooledTransport)pool=await (process.env.PROOF_TRANSPORT==='node-http'?enableNodeHttpFixture:enablePooledFixture)(mf);
    // Use asynchronous loopback fixture calls instead of Node's synchronous proxy.
    // Queries still execute against Miniflare/workerd's actual local D1 binding.
    async function query(body){const response=await mf.dispatchFetch("http://localhost/__local_fixture_sql",{method:"POST",headers:{"content-type":"application/json","x-fixture-key":fixtureKey},body:JSON.stringify(body)});
      const result=await response.json();if(!response.ok)throw Error(result.error);return result;}
    function prepare(sql,args=[]){return {sql,args,bind:(...values)=>prepare(sql,values),all:()=>query({sql,args}),run:()=>query({sql,args}),
      first:async()=>((await query({sql,args})).results[0]??null)};}
    const db={prepare,batch:statements=>query({batch:statements.map(s=>({sql:s.sql,args:s.args}))})};
    if(process.env.PROOF_DEBUG)console.log("D1 binding ready");
    if(initialise){const sql=(await readFile(schemaFile,"utf8")).replace(/--[^\n]*/g,"");for(const statement of sql.split(";").map(x=>x.trim()).filter(Boolean)){if(process.env.PROOF_DEBUG)console.log("Schema:",statement.slice(0,80));await db.prepare(statement).run();}}
    if(initialise&&['SIGNED_LOCAL','PAIRING_LOCAL'].includes(env.AUTH_MODE)){
      const securitySql=(await readFile(path.join(root,'security-schema.sql'),'utf8')).replace(/--[^\n]*/g,'');
      for(const statement of securitySql.split(';').map(x=>x.trim()).filter(Boolean))await db.prepare(statement).run();
    }
    if(initialise&&env.AUTH_MODE==='PAIRING_LOCAL'){const sql=(await readFile(path.join(root,'pairing-schema.sql'),'utf8')).replace(/--[^\n]*/g,'');for(const statement of sql.split(';').map(x=>x.trim()).filter(Boolean))await db.prepare(statement).run();}
    if(initialise&&(env.CUSTOMER_ABUSE_MODE||env.AUTH_MODE==='PAIRING_LOCAL')){const sql=(await readFile(path.join(root,'customer-abuse-schema.sql'),'utf8')).replace(/--[^\n]*/g,'');for(const statement of sql.split(';').map(x=>x.trim()).filter(Boolean))await db.prepare(statement).run();}
    const apiMeter=async()=>{const response=await mf.dispatchFetch('http://localhost/__local_fixture_meter',{method:'POST',headers:{'x-fixture-key':fixtureKey}});if(!response.ok)throw Error('Fixture meter unavailable');return response.json();};
    const fixtureSqlMeter=async()=>{const response=await mf.dispatchFetch('http://localhost/__local_fixture_sql_meter',{method:'POST',headers:{'x-fixture-key':fixtureKey}});if(!response.ok)throw Error('Fixture SQL meter unavailable');return response.json();};
    const lifecycle=async({signal}={})=>({client:pool?.traces??[],sockets:pool?.sockets??[],server:await (await mf.dispatchFetch('http://localhost/__local_fixture_traces',{method:'POST',headers:{'x-fixture-key':fixtureKey},...(signal?{signal}:{})})).json()});
    return {mf,db,directory,apiMeter,fixtureSqlMeter,lifecycle,clientTraces:pool?.traces??[],clientFailures:[],customerAbuseMode:env.CUSTOMER_ABUSE_MODE,gatewayKey:fixtureKey,fixtureTransport:pool?.stats,close:async()=>{try{await pool?.close();}finally{await mf.dispose();}},metrics:{requests:0,read:0,written:0,size:0,maxQueries:0,queries:0,latencies:[]}};
  } catch(e){console.error("Local D1 initialization failed:",e);await mf.dispose();await pool?.close();throw e;}
}
export const product=(i=0)=>({id:`p${String(i).padStart(5,"0")}`,name:`Synthetic fitting ${Math.floor(i/4)}`,size:`${i%4+1} inch`,
  category:"Synthetic hardware",type:"Fittings",unit:"Piece",sellingPricePaisa:String(1000+i),aliases:"fixture fitting socket ಸೊಕೆಟ್",gstRateBp:1800,
  currentStock:i%2===0?0:-20,purchasePricePaisa:"private-cost-must-disappear",supplier:"PRIVATE",passwordHash:"PRIVATE",imagePath:"PRIVATE"});
export async function seed(rt,count=4){
  const rows=Array.from({length:count},(_,i)=>projectProduct(product(i)));
  rows.push(projectProduct({id:"pipe20",name:"Synthetic PVC Pipe",category:"Pipes",type:"PVC",size:"1 inch",unit:"Length",sellingPricePaisa:"40000",
    isPipe:true,feetPerLength:20,lengthPricePaisa:"40000",footPricePaisa:"3500"}));
  rt.seedMetrics={read:0,written:0};
  for(let start=0;start<rows.length;start+=40){const results=await rt.db.batch(rows.slice(start,start+40).map(p=>rt.db.prepare(
    "INSERT INTO catalog(shop,id,doc,published,revision) VALUES('proof-shop',?,?,1,1) ON CONFLICT(shop,id) DO NOTHING").bind(p.id,JSON.stringify(p))));
    for(const result of results){rt.seedMetrics.read+=result.meta.rows_read;rt.seedMetrics.written+=result.meta.rows_written;}}
  return rows;
}
export const draft=(lineCount=1,offset=0)=>({submissionId:randomUUID(),capability:token(),customer:{name:"Synthetic customer",phone:"9876543210"},
  items:Array.from({length:lineCount},(_,i)=>({productId:product(i+offset).id,quantity:"1"})),note:"Synthetic local proof",missingRequest:""});
export async function call(rt,route,{method="POST",body,auth,capability,ip=body?.submissionId??randomUUID(),raw,headers={},abuse=true,customerSession,beforeTransmit}={}){
  const requestHeaders={...(rt.customerAbuseMode&&route==='orders'&&body&&abuse?challengeHeaders(body,{...(customerSession?{session:customerSession}:{})}):{}),...headers};if(auth)requestHeaders.authorization=`Bearer ${auth}`;if(capability)requestHeaders["x-order-capability"]=capability;
  requestHeaders['x-proof-trace-id']??=requestHeaders['x-operation-id']??body?.submissionId??randomUUID();
  requestHeaders['x-proof-attempt-id']=randomUUID();
  if(rt.customerAbuseMode)requestHeaders['x-local-gateway-key']=rt.gatewayKey;
  requestHeaders["x-proof-client-ip"]=ip;
  if(body!==undefined || raw!==undefined)requestHeaders["content-type"]="application/json";
  const began=performance.now();
  let response,result;
  try{response=await rt.mf.dispatchFetch(`https://proof.invalid/shops/proof-shop/${route}`,{method,headers:requestHeaders,
    body:raw??(body===undefined?undefined:JSON.stringify(body)),...(beforeTransmit?{beforeTransmit}:{})});
  result=await response.json();
  }catch(error){
    rt.clientFailures?.push({operation:requestHeaders['x-operation-id']??requestHeaders['x-proof-trace-id'],
      attempt:requestHeaders['x-proof-attempt-id'],route:route.split('?')[0],method,
      clientMonoMs:performance.now(),elapsedMs:performance.now()-began,
      errorName:error?.name??'Error',code:error?.code??error?.cause?.code??'UNKNOWN',
      bytesRead:error?.cause?.socket?.bytesRead??null,bytesWritten:error?.cause?.socket?.bytesWritten??null});
    throw error;
  }
  const wall=performance.now()-began;
  rt.metrics.requests++;rt.metrics.read+=Number(response.headers.get("x-proof-db-rows-read")??0);
  rt.metrics.written+=Number(response.headers.get("x-proof-db-rows-written")??0);
  const queries=Number(response.headers.get('x-proof-db-queries')??0);rt.metrics.queries+=queries;rt.metrics.maxQueries=Math.max(rt.metrics.maxQueries,queries);
  rt.metrics.size=Math.max(rt.metrics.size,Number(response.headers.get("x-proof-db-size")??0));rt.metrics.latencies.push(wall);
  return {status:response.status,body:result,wall,headers:response.headers};
}
export async function stored(rt,id){return rt.db.prepare("SELECT * FROM orders WHERE id=?").bind(id).first();}
