// PRIVATE local owner prototype. No hosted cleanup/live restore endpoint.
// Immutable tenant SQL snapshot; bounded pages, authenticated manifest + chunks.
import {mkdir,readFile,writeFile,rename,copyFile,stat} from 'node:fs/promises';
import path from 'node:path';
import {randomBytes,randomUUID,createHash,createHmac,scryptSync,createCipheriv,createDecipheriv,timingSafeEqual} from 'node:crypto';
import assert from 'node:assert/strict';
import {runtime} from './runtime.mjs';
const tables=['public_config','catalog','orders','events','admission','daily_admission','customer_session_admission','customer_ip_admission','customer_challenge_uses','pair_authority','pair_devices','pair_grants','pair_audit','pair_operations','auth_nonces'];
const hash=b=>createHash('sha256').update(b).digest('hex'),maxPage=8*1024*1024;
const verifiedRestores=new WeakMap();
async function readBounded(file,limit=12*1024*1024){const info=await stat(file);assert.ok(info.isFile()&&info.size<=limit,'Archive input bound exceeded');return readFile(file);}
const keyFor=(pass,salt)=>{assert.ok(pass.length>=16);return scryptSync(pass,Buffer.from(salt,'hex'),32);};
function unsigned(m){const o={...m};delete o.mac;return JSON.stringify(o);}
const mac=(m,key)=>createHmac('sha256',key).update(unsigned(m)).digest('hex');
async function save(dir,m,key){m.mac=mac(m,key);const bytes=Buffer.from(JSON.stringify(m));assert.ok(bytes.length<=8*1024*1024,'Manifest bound reached; snapshot retained');const tmp=path.join(dir,randomUUID()+'.tmp');await writeFile(tmp,bytes,{flag:'wx',mode:0o600});await rename(tmp,path.join(dir,'manifest.json'));}
async function load(dir,pass){const m=JSON.parse(await readBounded(path.join(dir,'manifest.json'),8*1024*1024));assert.equal(m.format,'HWPOS-TENANT-CHUNKS-1');assert.match(m.snapshot,/^[a-f0-9-]{36}$/);assert.match(m.salt,/^[a-f0-9]{32}$/);assert.match(m.mac,/^[a-f0-9]{64}$/);const key=keyFor(pass,m.salt);assert.ok(timingSafeEqual(Buffer.from(m.mac,'hex'),Buffer.from(mac(m,key),'hex')),'Manifest authentication failed');assert.deepEqual(m.tables,tables);for(const c of m.chunks)assert.match(c.file,/^[a-f0-9-]{36}\.chunk$/);return {m,key};}
export async function startSnapshot(rt,shop){
 assert.match(shop,/^[a-z0-9-]{1,80}$/);
 const size=await rt.db.prepare('SELECT id FROM orders LIMIT 1').all();assert.ok(size.meta.size_after<450000000,'Admission cutoff: snapshot cannot start');
 await rt.db.prepare('CREATE TABLE IF NOT EXISTS local_export_rows(snapshot TEXT,table_name TEXT,seq INTEGER,doc TEXT,PRIMARY KEY(snapshot,table_name,seq))').run();
 const id=randomUUID(),statements=[];
 for(const table of tables){const cols=(await rt.db.prepare(`PRAGMA table_info(${table})`).all()).results.map(c=>c.name);assert.ok(cols.includes('shop'));
  const objects=[];for(let i=0;i<cols.length;i+=16)objects.push(`json_object(${cols.slice(i,i+16).map(c=>`'${c}',${c}`).join(',')})`);
  const object=objects.reduce((a,b)=>`json_patch(${a},${b})`);
  statements.push(rt.db.prepare(`INSERT INTO local_export_rows SELECT ?,?,rowid,${object} FROM ${table} WHERE shop=?`).bind(id,table,shop));}
 // A single D1 transaction fixes ALL table boundaries, including event/receipt
 // identity. Later source mutations cannot change an in-progress export.
 await rt.db.batch(statements);
 return {id,shop};
}
export async function exportChunks(rt,{snapshot,directory,passphrase,stopAfter=Infinity}){
 await mkdir(directory,{recursive:true});let m,key;
 try{({m,key}=await load(directory,passphrase));assert.equal(m.snapshot,snapshot.id);assert.equal(m.shop,snapshot.shop);}
 catch(e){if(e.code!=='ENOENT')throw e;m={format:'HWPOS-TENANT-CHUNKS-1',snapshot:snapshot.id,shop:snapshot.shop,...(snapshot.chain?{chain:snapshot.chain}:{}),salt:randomBytes(16).toString('hex'),tables,chunks:[],tableIndex:0,cursor:0,complete:false,plainBytes:0};key=keyFor(passphrase,m.salt);await save(directory,m,key);}
 if(snapshot.chain)assert.deepEqual(m.chain,snapshot.chain);
 // Resume validates already committed files; never silently skips damaged pages.
 for(const c of m.chunks)assert.equal(hash(await readBounded(path.join(directory,c.file))),c.sha256);
 let written=0;
 while(m.tableIndex<tables.length){const table=tables[m.tableIndex],rows=(await rt.db.prepare('SELECT seq,doc FROM local_export_rows WHERE snapshot=? AND table_name=? AND seq>? ORDER BY seq LIMIT '+(snapshot.chain?.packing?4:32)).bind(snapshot.id,table,m.cursor).all()).results;
  if(!rows.length){m.tableIndex++;m.cursor=0;await save(directory,m,key);continue;}
  const plain=Buffer.from(JSON.stringify(rows));assert.ok(plain.length<=maxPage,'Page bound exceeded; source snapshot retained');
  const previous=m.chunks.at(-1)?.sha256??'0'.repeat(64),index=m.chunks.length,aad=JSON.stringify({snapshot:m.snapshot,shop:m.shop,table,index,previous}),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);cipher.setAAD(Buffer.from(aad));
  const encrypted=Buffer.concat([cipher.update(plain),cipher.final()]),envelope=Buffer.from(JSON.stringify({aad,iv:iv.toString('hex'),tag:cipher.getAuthTag().toString('hex'),data:encrypted.toString('base64')})),file=randomUUID()+'.chunk';
  await writeFile(path.join(directory,file),envelope,{flag:'wx',mode:0o600});
  m.chunks.push({file,table,index,previous,sha256:hash(envelope),plainHash:hash(plain),rows:rows.length,last:rows.at(-1).seq});m.cursor=rows.at(-1).seq;m.plainBytes+=plain.length;await save(directory,m,key);
  if(++written>=stopAfter)return {directory,complete:false,plainBytes:m.plainBytes,chunks:m.chunks.length};
 }
 m.complete=true;await save(directory,m,key);return {directory,complete:true,plainBytes:m.plainBytes,chunks:m.chunks.length,manifestHash:hash(await readFile(path.join(directory,'manifest.json')))};
}
export async function copyChunks(source,destination,passphrase){const {m}=await load(source,passphrase);assert.ok(m.complete);await mkdir(destination);for(const c of m.chunks){const b=await readBounded(path.join(source,c.file));assert.equal(hash(b),c.sha256);await writeFile(path.join(destination,c.file),b,{flag:'wx',mode:0o600});assert.equal(hash(await readBounded(path.join(destination,c.file))),c.sha256);}await copyFile(path.join(source,'manifest.json'),path.join(destination,'manifest.json'));return destination;}
export async function restoreChunks({directory,secondCopy,passphrase,bindings={},ownerConfirmed=false}){
 assert.ok(ownerConfirmed&&path.resolve(directory)!==path.resolve(secondCopy));const {m,key}=await load(directory,passphrase),other=await load(secondCopy,passphrase);assert.ok(m.complete&&other.m.complete);assert.equal(m.mac,other.m.mac);
 const isolated=await runtime({bindings:{...bindings,AUTH_MODE:'PAIRING_LOCAL',PAIR_QUARANTINED:'true'},pooledTransport:true}),counts={},digests={};let previous='0'.repeat(64);
 try{for(const c of m.chunks){assert.equal(c.previous,previous);assert.equal(c.index,m.chunks.indexOf(c));assert.ok(tables.includes(c.table));assert.match(c.file,/^[a-f0-9-]{36}\.chunk$/);
  const b=await readBounded(path.join(directory,c.file));assert.equal(hash(b),c.sha256);assert.equal(hash(await readBounded(path.join(secondCopy,c.file))),c.sha256);previous=c.sha256;
  const e=JSON.parse(b),expected=JSON.stringify({snapshot:m.snapshot,shop:m.shop,table:c.table,index:c.index,previous:c.previous});assert.equal(e.aad,expected);
  const decipher=createDecipheriv('aes-256-gcm',key,Buffer.from(e.iv,'hex'));decipher.setAAD(Buffer.from(e.aad));decipher.setAuthTag(Buffer.from(e.tag,'hex'));const plain=Buffer.concat([decipher.update(Buffer.from(e.data,'base64')),decipher.final()]);assert.ok(plain.length<=maxPage);assert.equal(hash(plain),c.plainHash);
  const page=JSON.parse(plain);assert.equal(page.length,c.rows);const allowed=(await isolated.db.prepare(`PRAGMA table_info(${c.table})`).all()).results.map(x=>x.name);
  const rows=page.map(({doc})=>{const r=JSON.parse(doc);assert.equal(r.shop,m.shop);assert.deepEqual(Object.keys(r),allowed);if(c.table==='pair_authority'){r.status='QUARANTINED';r.epoch++;}if(c.table==='pair_devices'){r.status='REVOKED';r.version++;}if(c.table==='pair_grants')r.consumed=1;return r;});
  await isolated.db.batch(rows.map(r=>isolated.db.prepare(`INSERT INTO ${c.table}(${allowed.join(',')}) VALUES(${allowed.map(()=>'?')})`).bind(...allowed.map(k=>r[k]))));
  // Verify each persisted field immediately, with deterministic order and page size.
  const actual=(await isolated.db.prepare(`SELECT * FROM ${c.table} ORDER BY rowid DESC LIMIT ?`).bind(rows.length).all()).results.reverse();assert.deepEqual(actual,rows);counts[c.table]=(counts[c.table]??0)+rows.length;
 }
 const result={...isolated,report:{counts,plainBytes:m.plainBytes,chunks:m.chunks.length,allFieldsCompared:true,authority:'QUARANTINED',sourceUnmodified:true}};
 verifiedRestores.set(result,{snapshot:m.snapshot,shop:m.shop,mac:m.mac});return result;
 }catch(e){await isolated.close();throw e;}
}
export async function cleanupSnapshot(rt,{snapshot,directory,secondCopy,passphrase,restored,ownerConfirmed=false}){
 const proof=verifiedRestores.get(restored);assert.ok(ownerConfirmed&&proof&&proof.snapshot===snapshot.id&&proof.shop===snapshot.shop);
 const {m}=await load(directory,passphrase),other=await load(secondCopy,passphrase);assert.equal(m.mac,proof.mac);assert.equal(other.m.mac,proof.mac);
 for(const c of m.chunks){assert.equal(hash(await readBounded(path.join(directory,c.file))),c.sha256);assert.equal(hash(await readBounded(path.join(secondCopy,c.file))),c.sha256);}
 // Explicit owner-reviewed temporary-snapshot cleanup ONLY. Permanent source
 // receipts, pending/uncollected/unresolved orders and bill links are untouched.
 await rt.db.prepare('DELETE FROM local_export_rows WHERE snapshot=?').bind(snapshot.id).run();
}
// Private local archive tooling only. Existing whole-snapshot format/restore
// remains unchanged; the incremental reader explicitly requires chain metadata.
export const chunkInternals={tables,load,hash,readBounded,maxPage};
