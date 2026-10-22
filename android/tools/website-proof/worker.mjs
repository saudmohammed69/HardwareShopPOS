import {Fault,fail,exact,text,uuid,capability,canonical,hash,bounded,projectProduct,priceLine} from "./pricing.mjs";
import {authorizeSigned,authorityGuard,securityRoute} from './security.mjs';
import {tenantSnapshot} from './tenant-archive.mjs';
import {verifyCustomerAdmission,customerAdmissionStatements} from './customer-abuse.mjs';
import {authorizePaired,pairingGuard,pairingRoute,orderOperationStatements} from './pairing.mjs';
import {requestTrace} from './lifecycle-trace.mjs';
import {recordProofCost} from './proof-cost-meter.mjs';
export {projectProduct,priceLine} from "./pricing.mjs";

function database(env,metrics){
  let authority=null,pendingProof=null,orderOperation=null;const statementSql=new WeakMap();
  const budget=n=>{if(metrics.queries+n>50)fail(503,'Local D1 query safety budget reached; recover the original operation');metrics.queries+=n;};
  const record=r=>{metrics.read+=r.meta?.rows_read??0;metrics.written+=r.meta?.rows_written??0;metrics.size=Math.max(metrics.size,r.meta?.size_after??0);return r;};
  return {
    trace:metrics.trace,
    setAuthority:a=>{authority=a;},
    setProof:proof=>{pendingProof=proof;},
    clearProof:()=>{pendingProof=null;},
    proofStatements(){return pendingProof?[this.statement('DELETE FROM auth_nonces WHERE expires_at<?',[Math.floor(Date.now()/1000)]),this.statement('INSERT INTO auth_nonces(device,nonce,expires_at,shop) VALUES(?,?,?,?)',[pendingProof.device,pendingProof.nonce,pendingProof.expires,pendingProof.shop])]:[];},
    setOrderOperation:value=>{orderOperation=value;},
    orderOperation:()=>orderOperation,
    operationStatements(id,operation){return orderOperationStatements(this,orderOperation,id,operation);},
    async flushProof(){if(pendingProof)await this.batch([]);},
    statement:(sql,args=[])=>{const s=env.DB.prepare(sql).bind(...args);statementSql.set(s,sql);return s;},
    async all(sql,args=[]){budget(1);const r=record(await env.DB.prepare(sql).bind(...args).all());recordProofCost(env,sql,r);return r.results;},
    async first(sql,args=[]){return (await this.all(sql,args))[0]??null;},
    async batch(statements){if(env.PROOF_FAIL_WRITES==="true")fail(503,"Persistence unavailable; keep your cart and retry the same submission");
      try{
        // Consume mutation proof and perform the existing business statements in
        // ONE atomic batch. Idempotent POST responses flush the proof separately.
        // A failed business batch rolls back its effects. The error response
        // consumes its proof separately, so a failed signed mutation cannot be
        // replayed later when its business preconditions have changed.
        const proofStatements=pendingProof?[
          this.statement('DELETE FROM auth_nonces WHERE expires_at<?',[Math.floor(Date.now()/1000)]),
          this.statement('INSERT INTO auth_nonces(device,nonce,expires_at,shop) VALUES(?,?,?,?)',[pendingProof.device,pendingProof.nonce,pendingProof.expires,pendingProof.shop])]:[];
        const guarded=authority?[(authority.kind==='SHOP_DEVICE'?pairingGuard:authorityGuard)(this,authority),...proofStatements,...statements,this.statement('DELETE FROM auth_guard')]:statements;
        budget(guarded.length);
        const results=(await env.DB.batch(guarded)).map((r,i)=>{recordProofCost(env,statementSql.get(guarded[i])??'',r);return record(r);});pendingProof=null;
        metrics.trace('transaction-committed',{statements:guarded.length,receipt:!!orderOperation});
        return authority?results.slice(1+proofStatements.length,-1):results;
      }catch(e){if(String(e).includes('UNIQUE constraint')&&String(e).includes('customer_challenge_uses'))fail(403,'Order verification already used; keep your cart');if(String(e).includes('UNIQUE constraint')&&String(e).includes('auth_nonces')){metrics.trace('auth-denied',{reason:'NONCE_REPLAY'});pendingProof=null;fail(403,'Authentication or device authorization failed');}if(String(e).includes('auth_guard_ok')){metrics.trace('auth-denied',{reason:'ATOMIC_AUTHORITY_GUARD'});fail(403,'Authentication or device authorization failed');}if(String(e).includes("CHECK constraint failed"))fail(429,"Order admission limit reached; retry later");throw e;}}
  };
}
async function actor(request,env,shop,db,owner=false){
  if(env.AUTH_MODE==='PAIRING_LOCAL')return authorizePaired(request,env,shop,db,{owner});
  if(env.AUTH_MODE==='SIGNED_LOCAL')return authorizeSigned(request,env,shop,db,{owner});
  if(env.AUTH_MODE!=='FIXTURE')fail(503,'Authentication adapter is not configured');
  // Explicit fixture adapter. This is NOT production Firebase authentication.
  const token=request.headers.get("authorization")?.replace(/^Bearer /,"");
  let a;try {a=JSON.parse(env.LOCAL_IDENTITIES??"{}")[token];}catch{fail(503,"Local identity adapter is not configured");}
  if(!a || a.revoked || a.expiresAt<=Date.now() || a.shop!==shop || !["OWNER","WORKER"].includes(a.role) || (owner && a.role!=="OWNER"))fail(403,"Not authorized");
  return a;
}
const receipt=row=>({confirmed:true,id:row.id,orderNumber:"WO-"+row.id.toUpperCase(),status:row.status,revision:row.revision,
  totalPaisa:JSON.parse(row.doc).estimatedSubtotalPaisa,estimateOnly:true});
async function recoveredOperation(db,shop){
 const op=db.orderOperation();if(!op)return null;
 const prior=await db.first('SELECT request_hash,result FROM pair_operations WHERE device=? AND request_id=? AND shop=?',[op.device,op.id,shop]);
 if(!prior)return null;if(prior.request_hash!==op.requestHash)fail(409,'Operation identity is bound to another request');return JSON.parse(prior.result);
}
function configurationReceipt(db,result){
 const op=db.orderOperation();return op?[db.statement('INSERT INTO pair_operations(shop,device,request_id,request_hash,order_id,result,at) VALUES(?,?,?,?,?,?,?)',[op.shop,op.device,op.id,op.requestHash,'configuration:'+op.route,JSON.stringify(result),Date.now()])]:[];
}
async function verifyCapability(row,token){
  if(!row || row.capability_hash!==await hash(capability(token)))fail(404,"Submission unavailable");
}
async function readJson(request){
  if(!request.headers.get("content-type")?.startsWith("application/json"))fail(415,"JSON required");
  if(Number(request.headers.get("content-length")??0)>32768)fail(413,"Request is too large");
  const reader=request.body?.getReader();let size=0,chunks=[];
  if(reader)while(true){const {value,done}=await reader.read();if(done)break;size+=value.byteLength;if(size>32768){await reader.cancel();fail(413,"Request is too large");}chunks.push(value);}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  let parsed;try {parsed=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));}catch{fail(400,"Invalid JSON");}
  function depth(value,level=0){if(level>8)fail(400,"Request nesting is too deep");if(value && typeof value==="object")for(const child of Object.values(value))depth(child,level+1);}
  depth(parsed);return parsed;
}
async function orderDocument(body,db,shop,env){
  exact(body,["submissionId","capability","customer","items","note","missingRequest","pickupAt"],["submissionId","capability","customer","items"]);
  uuid(body.submissionId);capability(body.capability);exact(body.customer,["name","phone"]);
  const customer={name:text(body.customer.name,100,1),phone:text(body.customer.phone,14,10).replace(/^\+91/,"")};
  if(!/^[6-9]\d{9}$/.test(customer.phone))fail(400,"Enter an Indian mobile number");
  if(!Array.isArray(body.items) || body.items.length<1 || body.items.length>25)fail(400,"Use 1–25 item lines");
  body.items.forEach(line=>exact(line,["productId","quantity","fullLengths","extraFeet","note"],["productId"]));
  if(new Set(body.items.map(x=>x.productId)).size!==body.items.length)fail(400,"Duplicate variants");
  // No stock quantity is stored, fetched, tested, reserved or deducted here.
  const items=[];
  for(const line of body.items){const id=text(line.productId,100,1);const row=await db.first("SELECT doc FROM catalog WHERE shop=? AND id=? AND published=1",[shop,id]);
    if(!row)fail(400,"A product is no longer published");items.push(priceLine(JSON.parse(row.doc),line));}
  const now=new Date();const minuteParts=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Kolkata",hour:"2-digit",minute:"2-digit",hourCycle:"h23"}).format(now).split(":");
  const minute=Number(minuteParts[0])*60+Number(minuteParts[1]);
  const open=Number(env.PROOF_OPEN_MINUTE??0),close=Number(env.PROOF_CLOSE_MINUTE??1440);
  if(env.PROOF_MAINTENANCE==="true" || minute<open || minute>=close)fail(409,"Ordering is closed; browsing remains available");
  let pickupAt=null;
  if(body.pickupAt){pickupAt=text(body.pickupAt,40);if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})$/.test(pickupAt))fail(400,"Use a pickup instant with timezone");
    const pickup=new Date(pickupAt);if(!Number.isFinite(pickup.getTime()) || pickup.getTime()<Date.now() || pickup.getTime()>Date.now()+7*86400000)fail(400,"Invalid pickup time");
    const parts=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Kolkata",hour:"2-digit",minute:"2-digit",hourCycle:"h23"}).format(pickup).split(":");
    if(Number(parts[0])*60+Number(parts[1])<open || Number(parts[0])*60+Number(parts[1])>=close)fail(400,"Pickup is outside shop hours");}
  const total=items.reduce((sum,line)=>bounded(sum+BigInt(line.estimatedLineTotalPaisa)),0n);
  return {customer,items,note:text(body.note??"",1000),missingRequest:text(body.missingRequest??"",1000),pickupAt,
    estimatedSubtotalPaisa:total.toString(),taxMode:"FINAL_POS_CONFIRMATION",source:"IN_SHOP_QR"};
}
async function mutation(db,row,operation,updates,kind){
  const now=Date.now();
  const result=await db.batch([
    db.statement(`UPDATE orders SET ${updates.sql},revision=revision+1,operation=?,updated_at=? WHERE id=? AND shop=? AND revision=? AND status=?`,[...updates.args,operation,now,row.id,row.shop,row.revision,row.status]),
    db.statement("INSERT INTO events(shop,order_id,revision,kind,created_at) SELECT shop,id,revision,?,? FROM orders WHERE id=? AND operation=?",[kind,now,row.id,operation]),
    ...db.operationStatements(row.id,operation)
  ]);
  if(result[0].meta.changes!==1)fail(409,"Order changed; refresh before retrying");
  return db.first("SELECT * FROM orders WHERE id=? AND shop=?",[row.id,row.shop]);
}

export default {async fetch(request,env){
  const started=performance.now(),trace=requestTrace(request,env),metrics={read:0,written:0,size:0,queries:0,trace};
  trace('server-received');
  let db;
  const reply=async(value,status=200)=>{if(status<300&&db)await db.flushProof();trace('server-response',{status});return new Response(JSON.stringify(value),{status,headers:{"content-type":"application/json","cache-control":"no-store",
    "x-content-type-options":"nosniff","referrer-policy":"no-referrer","content-security-policy":"default-src 'none'",
    "x-proof-db-rows-read":String(metrics.read),"x-proof-db-rows-written":String(metrics.written),"x-proof-db-size":String(metrics.size),"x-proof-db-queries":String(metrics.queries),
    "x-proof-request-wall-ms":String(performance.now()-started)}});};
  try{
    if(env.LOCAL_PROOF_ONLY!=="true")fail(503,"Local proof adapter disabled; not a production service");
    const url=new URL(request.url),parts=url.pathname.split("/").filter(Boolean);
    if(parts[0]!=="shops" || parts.length<3)fail(404,"Unknown route");
    const shop=text(parts[1],80,1);if(shop!==env.PROOF_SHOP)fail(404,"Shop unavailable");
    if(request.method!=="GET" && request.headers.has("origin") && !["https://proof.invalid",env.LOCAL_WEB_ORIGIN].filter(Boolean).includes(request.headers.get("origin")))fail(403,"Origin is not allowed");
    db=database(env,metrics);const route=parts.slice(2).join("/");
    if(parts.length>5)fail(404,'Unknown route');
    if(route.startsWith('pair/'))return await reply(await pairingRoute(request,env,shop,route,db,readJson));
    if(route==='owner/archive-snapshot'&&request.method==='POST'){
      if(env.AUTH_MODE!=='SIGNED_LOCAL')fail(503,'Signed owner archive authority required');
      return await reply(await tenantSnapshot(request,shop,db,()=>actor(request,env,shop,db,true),readJson));
    }
    if(route.startsWith('auth/'))return await reply(await securityRoute(request,env,shop,route,db,readJson));
    if(route==="public-config"||route==="orders"||/\/edit$/.test(route)){
      const saved=await db.first("SELECT doc FROM public_config WHERE shop=?",[shop]);if(saved)env={...env,...JSON.parse(saved.doc)};
    }
    if(route==="owner/public-config" && request.method==="POST"){
      await actor(request,env,shop,db,true);const recovered=await recoveredOperation(db,shop);if(recovered)return await reply(recovered);const b=await readJson(request);exact(b,["name","phone","address","openMinute","closeMinute","maintenance"]);
      if(!Number.isInteger(b.openMinute)||!Number.isInteger(b.closeMinute)||b.openMinute<0||b.closeMinute>1440||b.openMinute>=b.closeMinute||typeof b.maintenance!=="boolean")fail(400,"Use a valid daily opening window");
      const doc={PUBLIC_SHOP_NAME:text(b.name,100,1),PUBLIC_SHOP_PHONE:text(b.phone,30),PUBLIC_SHOP_ADDRESS:text(b.address,300),PROOF_OPEN_MINUTE:String(b.openMinute),PROOF_CLOSE_MINUTE:String(b.closeMinute),PROOF_MAINTENANCE:String(b.maintenance)};
      await db.batch([db.statement("INSERT INTO public_config(shop,doc) VALUES(?,?) ON CONFLICT(shop) DO UPDATE SET doc=excluded.doc",[shop,JSON.stringify(doc)]),...configurationReceipt(db,{saved:true})]);return await reply({saved:true});
    }
    if(route==="public-config" && request.method==="GET"){
      const open=Number(env.PROOF_OPEN_MINUTE??0),close=Number(env.PROOF_CLOSE_MINUTE??1440);
      const parts=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Kolkata",hour:"2-digit",minute:"2-digit",hourCycle:"h23"}).format(new Date()).split(":");
      const minute=Number(parts[0])*60+Number(parts[1]);
      return await reply({name:env.PUBLIC_SHOP_NAME||"HardwareShopPOS",address:env.PUBLIC_SHOP_ADDRESS||"",phone:env.PUBLIC_SHOP_PHONE||"",localPreview:true,
        timeZone:"Asia/Kolkata",openMinute:open,closeMinute:close,maintenance:env.PROOF_MAINTENANCE==="true",orderingOpen:env.PROOF_MAINTENANCE!=="true"&&minute>=open&&minute<close});
    }
    if(route==="owner/storage" && request.method==="GET"){
      // A one-row probe returns native allocated size without scanning an ever
      // growing order table each time the owner checks storage.
      await actor(request,env,shop,db,true);await db.first("SELECT id FROM orders LIMIT 1");
      const ratio=metrics.size/Number(env.LOCAL_STORAGE_LIMIT??500000000);
      return await reply({bytes:metrics.size,limit:Number(env.LOCAL_STORAGE_LIMIT??500000000),level:ratio>=.9?"STOP_NEW_ORDERS":ratio>=.8?"CRITICAL":ratio>=.65?"WARNING":"OK",acceptNew:ratio<.9});
    }
    if(route==="catalog" && request.method==="GET"){
      const after=url.searchParams.get("after")??"";if(after.length>100)fail(400,"Invalid page");
      const rows=await db.all("SELECT id,doc,revision FROM catalog WHERE shop=? AND published=1 AND id>? ORDER BY id LIMIT 500",[shop,after]);
      return await reply({products:rows.map(r=>({...JSON.parse(r.doc),revision:r.revision})),next:rows.length===500?rows.at(-1).id:null});
    }
    if(route==="catalog/publish" && request.method==="POST"){
      await actor(request,env,shop,db,true);const recovered=await recoveredOperation(db,shop);if(recovered)return await reply(recovered);const body=await readJson(request);exact(body,["product","published"]);
      if(typeof body.published!=="boolean")fail(400,"Publication must be explicit");const p=projectProduct(body.product);
      await db.batch([db.statement("INSERT INTO catalog(shop,id,doc,published,revision) VALUES(?,?,?,?,1) ON CONFLICT(shop,id) DO UPDATE SET doc=excluded.doc,published=excluded.published,revision=catalog.revision+1",[shop,p.id,JSON.stringify(p),+body.published]),...configurationReceipt(db,{published:body.published})]);
      return await reply({published:body.published});
    }
    if(route==="catalog/publish-batch" && request.method==="POST"){
      await actor(request,env,shop,db,true);const recovered=await recoveredOperation(db,shop);if(recovered)return await reply(recovered);const b=await readJson(request);exact(b,["products"]);
      if(!Array.isArray(b.products)||b.products.length<1||b.products.length>40)fail(400,"Publish 1–40 variants per batch");
      const safe=b.products.map(x=>{exact(x,["product","published"]);if(typeof x.published!=="boolean")fail(400,"Publication must be explicit");return {product:projectProduct(x.product),published:x.published};});
      if(new Set(safe.map(x=>x.product.id)).size!==safe.length)fail(400,"Duplicate public product IDs");
      await db.batch([...safe.map(x=>db.statement("INSERT INTO catalog(shop,id,doc,published,revision) VALUES(?,?,?,?,1) ON CONFLICT(shop,id) DO UPDATE SET doc=excluded.doc,published=excluded.published,revision=catalog.revision+1",[shop,x.product.id,JSON.stringify(x.product),+x.published])),...configurationReceipt(db,{publishedCount:safe.length})]);
      return await reply({publishedCount:safe.length});
    }
    if(route==="orders" && request.method==="POST"){
      const body=await readJson(request);uuid(body?.submissionId);capability(body?.capability);
      const clean={...body};delete clean.capability;const payloadHash=await hash(canonical(clean));
      const previous=await db.first("SELECT * FROM orders WHERE shop=? AND submission=?",[shop,body.submissionId]);
      if(previous){await verifyCapability(previous,body.capability);if(previous.payload_hash!==payloadHash)fail(409,"Submission identity already used with another cart");return await reply(receipt(previous));}
      // Replays above remain recoverable even while admission is stopped. Native allocated size is measured by the indexed lookup.
      if(metrics.size>=Number(env.LOCAL_STORAGE_LIMIT??500000000)*.9)fail(503,"Order storage is near its safe limit; keep your cart and contact the shop");
      const admission=env.CUSTOMER_ABUSE_MODE?await verifyCustomerAdmission(request,env,shop,body,payloadHash):null;
      const doc=await orderDocument(body,db,shop,env),id=crypto.randomUUID(),now=Date.now();
      const key=await crypto.subtle.importKey("raw",new TextEncoder().encode(env.LOCAL_RATE_KEY),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
      const bucket=Array.from(new Uint8Array(await crypto.subtle.sign("HMAC",key,new TextEncoder().encode(request.headers.get("x-proof-client-ip")??"local-unknown"))),b=>b.toString(16).padStart(2,"0")).join("");
      const capHash=await hash(body.capability),window=Math.floor(now/600000),day=Math.floor(now/86400000);
      await db.batch([
        db.statement("INSERT INTO orders(id,shop,submission,capability_hash,payload_hash,doc,status,revision,operation,updated_at) VALUES(?,?,?,?,?,?,'NEW',1,?,?) ON CONFLICT(shop,submission) DO NOTHING",[id,shop,body.submissionId,capHash,payloadHash,JSON.stringify(doc),id,now]),
        ...(admission?customerAdmissionStatements(db,shop,id,admission):[db.statement("INSERT INTO admission(shop,bucket,window,count) SELECT ?,?,?,1 WHERE EXISTS(SELECT 1 FROM orders WHERE id=?) ON CONFLICT(shop,bucket) DO UPDATE SET count=CASE WHEN admission.window=excluded.window THEN admission.count+1 ELSE 1 END,window=excluded.window",[shop,bucket,window,id])]),
        db.statement("INSERT INTO daily_admission(shop,day,count) SELECT ?,?,1 WHERE EXISTS(SELECT 1 FROM orders WHERE id=?) ON CONFLICT(shop,day) DO UPDATE SET count=count+1",[shop,day,id]),
        db.statement("INSERT INTO events(shop,order_id,revision,kind,created_at) SELECT shop,id,revision,'NEW',? FROM orders WHERE id=?",[now,id])
      ]);
      const row=await db.first("SELECT * FROM orders WHERE shop=? AND submission=?",[shop,body.submissionId]);
      await verifyCapability(row,body.capability);if(row.payload_hash!==payloadHash)fail(409,"Submission identity already used with another cart");return await reply(receipt(row),row.id===id?201:200);
    }
    if(route==="recover" && request.method==="POST"){
      const body=await readJson(request);exact(body,["submissionId","capability"]);uuid(body.submissionId);
      const row=await db.first("SELECT * FROM orders WHERE shop=? AND submission=?",[shop,body.submissionId]);await verifyCapability(row,body.capability);return await reply(receipt(row));
    }
    if(route==="pos/events" && request.method==="GET"){
      await actor(request,env,shop,db);const cursor=url.searchParams.get("cursor")??"0";if(!/^\d{1,15}$/.test(cursor))fail(400,"Invalid cursor");
      const rows=await db.all("SELECT e.seq,e.kind,e.revision AS eventRevision,o.id,o.status,o.revision,o.doc,o.bill_id,o.lock_device,o.updated_at FROM events e JOIN orders o ON o.id=e.order_id WHERE e.shop=? AND e.seq>? ORDER BY e.seq LIMIT 100",[shop,Number(cursor)]);
      const events=[];let bytes=0;
      for(const row of rows){const e={...row,doc:JSON.parse(row.doc)},size=new TextEncoder().encode(JSON.stringify(e)).byteLength;
        if(events.length&&bytes+size>1000000)break;events.push(e);bytes+=size;}
      return await reply({events,cursor:events.at(-1)?.seq??Number(cursor),hasMore:events.length<rows.length||rows.length===100});
    }
    if(parts[2]==="orders" && parts[3]){
      const id=uuid(parts[3]),action=parts[4],customerAction=['customer-view','edit','customer-cancel'].includes(action);
      const a=customerAction?null:await actor(request,env,shop,db);
      const recorded=db.orderOperation();
      if(recorded){const previous=await db.first('SELECT request_hash,result FROM pair_operations WHERE device=? AND request_id=? AND shop=?',[recorded.device,recorded.id,shop]);
        if(previous){if(previous.request_hash!==recorded.requestHash)fail(409,'Operation identity is bound to another request');return await reply(JSON.parse(previous.result));}}
      const row=await db.first("SELECT * FROM orders WHERE shop=? AND id=?",[shop,id]);
      if(!row)fail(404,"Order unavailable");
      if(action==="customer-view" && request.method==="POST"){
        await verifyCapability(row,request.headers.get("x-order-capability"));exact(await readJson(request),[]);
        return await reply({...receipt(row),order:row.status==="NEW"?JSON.parse(row.doc):null});
      }
      // Authenticate private staff requests before reading their body/returning
      // state-dependent errors. Customer capabilities remain separate.
      const body=await readJson(request),operation=recorded?.id??crypto.randomUUID();
      if(["edit","customer-cancel"].includes(action) && request.method==="POST"){
        await verifyCapability(row,request.headers.get("x-order-capability"));if(row.status!=="NEW")fail(409,"Only staff may change an accepted order");
        if(!Number.isInteger(body.expectedRevision) || body.expectedRevision!==row.revision)fail(409,"Order changed; refresh first");
        if(action==="customer-cancel"){
          exact(body,["expectedRevision"]);return await reply(receipt(await mutation(db,row,operation,{sql:"status='CANCELLED'",args:[]},"CUSTOMER_CANCELLED")));
        }
        exact(body,["expectedRevision","order"]);const updated=await orderDocument({...body.order,submissionId:row.submission,capability:request.headers.get("x-order-capability")},db,shop,env);
        return await reply(receipt(await mutation(db,row,operation,{sql:"doc=?",args:[JSON.stringify(updated)]},"CUSTOMER_EDITED")));
      }
      if(request.method!=="POST")fail(405,"Method not allowed");
      if(action==="adjust"){
        exact(body,["expectedRevision","items","reason","customerConfirmed"]);
        if(body.customerConfirmed!==true)fail(400,"Customer confirmation is required");
        const reason=text(body.reason,300,1);
        if(!["ACCEPTED","PREPARING","READY_FOR_PICKUP"].includes(row.status)||body.expectedRevision!==row.revision)fail(409,"Refresh before adjusting an accepted, unbilled order");
        const previous=JSON.parse(row.doc);if((previous.adjustments??[]).length>=20)fail(409,"Owner review required before further adjustments");
        const priced=await orderDocument({submissionId:row.submission,capability:"0".repeat(64),customer:previous.customer,items:body.items},db,shop,{...env,PROOF_OPEN_MINUTE:"0",PROOF_CLOSE_MINUTE:"1440",PROOF_MAINTENANCE:"false"});
        const doc={...previous,items:priced.items,estimatedSubtotalPaisa:priced.estimatedSubtotalPaisa,adjustments:[...(previous.adjustments??[]),{actor:a.uid,device:a.device,at:Date.now(),reason,customerConfirmed:true,beforeItems:previous.items,afterItems:priced.items}]};
        if(new TextEncoder().encode(JSON.stringify(doc)).byteLength>96000)fail(409,"Adjustment history reached its safe limit; owner reconciliation required. Existing order is preserved.");
        return await reply(receipt(await mutation(db,row,operation,{sql:"doc=?,status='ACCEPTED',actor=?",args:[JSON.stringify(doc),a.uid]},"CUSTOMER_CONFIRMED_ADJUSTMENT")));
      }
      if(action==="billing-claim"){
        exact(body,["expectedRevision","claimToken"]);const lockHash=await hash(capability(body.claimToken));
        if(["BILLING_IN_PROGRESS","COMPLETED"].includes(row.status)){
          if(row.lock_device===a.device && row.lock_hash===lockHash)return await reply({...receipt(row),billId:row.bill_id});
          fail(409,"Billing belongs to another installation; owner reconciliation required");
        }
        if(!["ACCEPTED","PREPARING","READY_FOR_PICKUP"].includes(row.status) || body.expectedRevision!==row.revision)fail(409,"Refresh and accept before billing");
        const billId=crypto.randomUUID();const updated=await mutation(db,row,operation,{sql:"status='BILLING_IN_PROGRESS',lock_device=?,lock_hash=?,bill_id=?,actor=?",args:[a.device,lockHash,billId,a.uid]},"BILLING_CLAIMED");
        return await reply({...receipt(updated),billId});
      }
      if(action==="complete"){
        exact(body,["expectedRevision","claimToken","billId","posBillSyncId"],["expectedRevision","claimToken","billId"]);uuid(body.billId);
        if(body.posBillSyncId!==undefined)text(body.posBillSyncId,100,16);
        if(row.lock_device!==a.device || row.lock_hash!==await hash(capability(body.claimToken)) || row.bill_id!==body.billId)fail(409,"Billing ownership mismatch");
        if(row.status==="COMPLETED")return await reply({...receipt(row),billId:row.bill_id});
        if(row.status!=="BILLING_IN_PROGRESS" || body.expectedRevision!==row.revision)fail(409,"Billing revision mismatch");
        const doc={...JSON.parse(row.doc),...(body.posBillSyncId?{finalizedPosBillSyncId:body.posBillSyncId}:{})};
        return await reply({...receipt(await mutation(db,row,operation,{sql:"status='COMPLETED',doc=?",args:[JSON.stringify(doc)]},"COMPLETED")),billId:row.bill_id});
      }
      const transitions={accept:["NEW","ACCEPTED"],prepare:["ACCEPTED","PREPARING"],ready:["PREPARING","READY_FOR_PICKUP"]};
      if(action==="cancel"){
        exact(body,["expectedRevision","reason"]);text(body.reason,300,1);
        if(!["NEW","ACCEPTED","PREPARING","READY_FOR_PICKUP"].includes(row.status))fail(409,"Locked/completed orders cannot be cancelled here");
        if(body.expectedRevision!==row.revision)fail(409,"Order changed");
        const doc={...JSON.parse(row.doc),cancelReason:body.reason};return await reply(receipt(await mutation(db,row,operation,{sql:"status='CANCELLED',doc=?,actor=?",args:[JSON.stringify(doc),a.uid]},"STAFF_CANCELLED")));
      }
      exact(body,["expectedRevision"]);const transition=transitions[action];
      if(!transition || row.status!==transition[0] || body.expectedRevision!==row.revision)fail(409,"Invalid or stale transition");
      return await reply(receipt(await mutation(db,row,operation,{sql:"status=?,actor=?",args:[transition[1],a.uid]},transition[1])));
    }
    fail(404,"Unknown route");
  }catch(e){
    if(env.LOCAL_PROOF_ONLY==='true'&&env.PROOF_DEBUG_ERRORS==='true')console.error('Local proof diagnostic',String(e));
    // Preserve the existing one-use policy even for valid signed requests that
    // fail validation/CAS. Revoked authority fails this guarded flush closed.
    try{if(db)await db.flushProof();}catch(authError){e=authError;}
    if(e instanceof Fault)return await reply({error:e.message,retryable:[429,503].includes(e.status)},e.status);
    return await reply({error:"Persistence unavailable; confirmation is unknown. Keep your cart and recover the original submission.",retryable:true},503);}
}};
