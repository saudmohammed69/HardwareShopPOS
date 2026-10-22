// Owner-operated LOCAL proof. No hosted cleanup endpoint, scheduler, or cloud SDK.
import {createHash,randomBytes,scryptSync,createCipheriv,createDecipheriv,randomUUID} from 'node:crypto';
import {open,readFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {gzipSync,gunzipSync} from 'node:zlib';
import {runtime} from './runtime.mjs';
const digest=x=>createHash('sha256').update(x).digest('hex');
const stable=x=>JSON.stringify(x,(_,v)=>v && !Array.isArray(v) && typeof v==='object'?Object.fromEntries(Object.entries(v).sort(([a],[b])=>a.localeCompare(b))):v);
const cols=['id','shop','submission','capability_hash','payload_hash','doc','status','revision','actor','operation','lock_device','lock_hash','bill_id','updated_at'];
export function storagePolicy(bytes,{limit=500000000,accountBytes=bytes,accountLimit=5000000000}={}){
  const ratio=Math.max(bytes/limit,accountBytes/accountLimit);
  return {bytes,limit,accountBytes,accountLimit,level:ratio>=.9?'STOP_NEW_ORDERS':ratio>=.8?'CRITICAL':ratio>=.65?'WARNING':'OK',
    acceptNew:ratio<.9,remainingBytes:Math.max(0,limit-bytes),message:ratio>=.65?'Owner: verify exports and resolve storage. Active orders must not be removed.':'Storage within configured budget'};
}
export async function measureStorage(rt){
  // Native D1 size_after is allocated DB size; do not pretend DELETE shrinks it.
  const r=await rt.db.prepare('SELECT COUNT(*) n FROM orders').all();
  const logical=await rt.db.prepare('SELECT COALESCE(SUM(length(CAST(doc AS BLOB))),0) n FROM orders').first();
  return {...storagePolicy(r.meta.size_after),orderCount:r.results[0].n,logicalOrderBytes:logical.n};
}
export async function measureAllocatedStorage(rt){
  // Cleanup needs allocated bytes and warning/cutoff policy, not a full COUNT /
  // document-size scan twice per batch. Keep the detailed summary above as an
  // explicit owner/report operation; this bounded probe matches owner/storage.
  const r=await rt.db.prepare('SELECT id FROM orders LIMIT 1').all();
  return {...storagePolicy(r.meta.size_after),summaryScope:'ALLOCATED_ONLY'};
}
export async function pruneExpiredAdmission(rt,{now=Date.now()}={}){
  // Abuse counters are not order, audit, bill-identity, or replay records. The
  // current ten-minute bucket and current day remain available for enforcement.
  // This Node-only operation is reached from explicit owner archive cleanup.
  const window=Math.floor(now/600000),day=Math.floor(now/86400000);
  const results=await rt.db.batch([
    rt.db.prepare('DELETE FROM admission WHERE shop=? AND window<?').bind('proof-shop',window),
    rt.db.prepare('DELETE FROM daily_admission WHERE shop=? AND day<?').bind('proof-shop',day)
  ]);
  // Only expiry metadata for anonymous admission, never order/audit/bill rows.
  // Expired challenges cannot validate again; durable order identities remain.
  const customerResults=rt.customerAbuseMode?await rt.db.batch([
    rt.db.prepare('DELETE FROM customer_session_admission WHERE shop=? AND expires_at<?').bind('proof-shop',Math.floor(now/1000)),
    rt.db.prepare('DELETE FROM customer_ip_admission WHERE shop=? AND expires_at<?').bind('proof-shop',Math.floor(now/1000)),
    rt.db.prepare('DELETE FROM customer_challenge_uses WHERE shop=? AND expires_at<?').bind('proof-shop',Math.floor(now/1000))
  ]):[];
  const measured=[...results,...customerResults];
  return {currentWindow:window,currentDay:day,expiredAdmissionRows:results[0].meta.changes??0,
    expiredDailyRows:results[1].meta.changes??0,expiredCustomerMetadataRows:customerResults.reduce((n,r)=>n+(r.meta.changes??0),0),rowsRead:measured.reduce((n,r)=>n+(r.meta.rows_read??0),0),
    rowsWritten:measured.reduce((n,r)=>n+(r.meta.rows_written??0),0)};
}
export async function exportOrders(rt,{ids,directory,passphrase,attestations}){
  if(!Array.isArray(ids)||ids.length<1||ids.length>100||new Set(ids).size!==ids.length)throw Error('Select 1–100 unique orders per owner export');
  if(typeof passphrase!=='string'||passphrase.length<16)throw Error('Use a private archive passphrase of at least 16 characters');
  const orders=[],events=[];
  for(const id of ids){
    const row=await rt.db.prepare('SELECT * FROM orders WHERE id=? AND shop=?').bind(id,'proof-shop').first();
    const a=attestations?.[id];
    if(!row||!['COMPLETED','CANCELLED'].includes(row.status)||JSON.parse(row.doc).archiveRef)throw Error('Only full terminal orders can be exported for cleanup');
    if(!a||a.resolved!==true||a.collected!==true||!a.owner||!a.reason||a.billId!==(row.bill_id??null))throw Error('Owner collection/resolution and matching finalized bill attestation required');
    if(row.status==='COMPLETED'&&!row.bill_id)throw Error('Completed order has no bill link');
    orders.push(row);events.push(...(await rt.db.prepare('SELECT * FROM events WHERE order_id=? ORDER BY seq').bind(id).all()).results);
  }
  const payload={format:'HardwareShopPOS-local-orders',version:1,createdAt:new Date().toISOString(),orders,events,attestations};
  const plaintext=Buffer.from(stable(payload)),salt=randomBytes(16),iv=randomBytes(12),key=scryptSync(passphrase,salt,32);
  const cipher=createCipheriv('aes-256-gcm',key,iv),encrypted=Buffer.concat([cipher.update(gzipSync(plaintext)),cipher.final()]);
  const envelope={format:'HWPOS-encrypted-archive',version:1,kdf:'scrypt-N16384-r8-p1',salt:salt.toString('hex'),iv:iv.toString('hex'),tag:cipher.getAuthTag().toString('hex'),sha256:digest(plaintext),data:encrypted.toString('base64')};
  const bytes=Buffer.from(JSON.stringify(envelope)),sha256=digest(bytes);
  await mkdir(directory,{recursive:true});const file=path.join(directory,randomUUID()+'.hwpos-archive');
  const handle=await open(file,'wx',0o600);try{await handle.writeFile(bytes);await handle.sync();}finally{await handle.close();}
  // Explicit sidecar digest. Encryption authenticates contents; digest detects transfer corruption.
  const mh=await open(file+'.sha256','wx',0o600);try{await mh.writeFile(sha256+'\n');await mh.sync();}finally{await mh.close();}
  return {file,sha256,plaintextSha256:envelope.sha256,count:orders.length,encryptedBytes:bytes.length};
}
export async function readArchive(file,passphrase,expectedHash){
  const bytes=await readFile(file);if(!expectedHash||digest(bytes)!==expectedHash)throw Error('Archive checksum mismatch');
  const e=JSON.parse(bytes);if(e.format!=='HWPOS-encrypted-archive'||e.version!==1||e.kdf!=='scrypt-N16384-r8-p1')throw Error('Unsupported archive');
  const decipher=createDecipheriv('aes-256-gcm',scryptSync(passphrase,Buffer.from(e.salt,'hex'),32),Buffer.from(e.iv,'hex'));
  decipher.setAuthTag(Buffer.from(e.tag,'hex'));
  const plaintext=gunzipSync(Buffer.concat([decipher.update(Buffer.from(e.data,'base64')),decipher.final()]),{maxOutputLength:16*1024*1024});
  if(digest(plaintext)!==e.sha256)throw Error('Plaintext checksum mismatch');
  const p=JSON.parse(plaintext);if(p.format!=='HardwareShopPOS-local-orders'||p.version!==1||p.orders.length>100)throw Error('Unsupported payload');return p;
}
export async function verifyRestore(file,passphrase,expectedHash){
  const payload=await readArchive(file,passphrase,expectedHash),isolated=await runtime();
  try{
    // Only a newly-created disposable D1 is used; no target/live DB argument exists.
    const statements=payload.orders.map(row=>isolated.db.prepare(`INSERT INTO orders(${cols.join(',')}) VALUES(${cols.map(()=>'?').join(',')})`).bind(...cols.map(k=>row[k])));
    statements.push(...payload.events.map(e=>isolated.db.prepare('INSERT INTO events(seq,shop,order_id,revision,kind,created_at) VALUES(?,?,?,?,?,?)').bind(e.seq,e.shop,e.order_id,e.revision,e.kind,e.created_at)));
    // Bounded local restore batches preserve parent-before-child ordering. Any
    // failure abandons this disposable DB and never authorizes source cleanup.
    for(let i=0;i<statements.length;i+=40)await isolated.db.batch(statements.slice(i,i+40));
    const restored=(await isolated.db.prepare('SELECT * FROM orders ORDER BY id').all()).results;
    const history=(await isolated.db.prepare('SELECT * FROM events ORDER BY seq').all()).results;
    if(stable(restored)!==stable([...payload.orders].sort((a,b)=>a.id.localeCompare(b.id)))||stable(history)!==stable([...payload.events].sort((a,b)=>a.seq-b.seq)))throw Error('Restore comparison failed');
    return {payload,restoredOrders:restored.length,restoredEvents:history.length,isolatedDirectory:isolated.directory,isolatedLocalD1Cost:await isolated.fixtureSqlMeter()};
  }finally{await isolated.close();}
}
export async function compactVerified(rt,{file,sha256},passphrase,{ownerConfirmed=false,secondCopy,now=Date.now()}={}){
  if(!ownerConfirmed)throw Error('Owner cleanup confirmation required');
  if(!secondCopy||path.resolve(secondCopy)===path.resolve(file))throw Error('Independent owner-controlled archive copy required');
  if(digest(await readFile(secondCopy))!==sha256)throw Error('Second archive copy checksum mismatch');
  const verified=await verifyRestore(file,passphrase,sha256), statements=[];
  // A failed guard aborts the entire local D1 batch, including previously updated rows.
  await rt.db.prepare('CREATE TABLE IF NOT EXISTS archive_guard(ok INTEGER CHECK(ok=1))').run();
  for(const row of verified.payload.orders){
    const history=verified.payload.events.filter(e=>e.order_id===row.id),last=history.at(-1);
    if(!last||last.revision!==row.revision)throw Error('Terminal event checkpoint is missing');
    const doc=JSON.parse(row.doc),small=JSON.stringify({estimatedSubtotalPaisa:doc.estimatedSubtotalPaisa,archiveRef:sha256,archiveVersion:1,source:doc.source,archiveEventRange:{first:history[0].seq,last:last.seq,count:history.length}});
    statements.push(rt.db.prepare('INSERT INTO archive_guard(ok) SELECT CASE WHEN EXISTS(SELECT 1 FROM orders WHERE id=? AND revision=? AND status=? AND doc=? AND bill_id IS ?) THEN 1 ELSE 0 END').bind(row.id,row.revision,row.status,row.doc,row.bill_id));
    statements.push(rt.db.prepare('UPDATE orders SET doc=? WHERE id=? AND revision=? AND doc=?').bind(small,row.id,row.revision,row.doc));
    // Full history lives in both verified encrypted copies. Keep the latest monotonic
    // event online so a device with any older cursor still receives the terminal snapshot.
    statements.push(rt.db.prepare('DELETE FROM events WHERE order_id=? AND seq<?').bind(row.id,last.seq));
  }
  statements.push(rt.db.prepare('DELETE FROM archive_guard'));
  await rt.db.batch(statements);
  const compactedStorage=await measureAllocatedStorage(rt),expiredAdmission=await pruneExpiredAdmission(rt,{now});
  return {compacted:verified.restoredOrders,sha256,eventsArchived:verified.restoredEvents,terminalEventsKept:verified.restoredOrders,isolatedLocalD1Cost:verified.isolatedLocalD1Cost,
    compactedStorage,expiredAdmission,storage:await measureAllocatedStorage(rt)};
}
