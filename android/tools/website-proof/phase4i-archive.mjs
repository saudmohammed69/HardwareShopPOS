// PRIVATE local prototype. No hosted archive, live restore or cleanup endpoint.
// A verified baseline plus authenticated ordered deltas. Source records are never
// removed here. Cache advancement is resumable and never implies order cleanup.
import assert from 'node:assert/strict';
import path from 'node:path';
import {randomUUID,createDecipheriv} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {runtime} from './runtime.mjs';
import {chunkInternals,exportChunks,copyChunks} from './phase4h-archive.mjs';
export {exportChunks,copyChunks};
const {tables,load,hash,readBounded,maxPage}=chunkInternals,verified=new WeakMap();
const permanent=new Set(['orders','pair_operations','pair_audit']);
const appendOnly=new Set(['pair_operations','pair_audit','events']);
const ident=name=>{assert.match(name,/^[a-z_]+$/);return '"'+name+'"';};
async function shape(rt,table){
 const columns=(await rt.db.prepare(`PRAGMA table_info(${ident(table)})`).all()).results;
 assert.ok(columns.some(c=>c.name==='shop')&&columns.length<=16);
 const keys=columns.filter(c=>c.pk).sort((a,b)=>a.pk-b.pk).map(c=>c.name);assert.ok(keys.length);
 return {columns:columns.map(c=>c.name),keys};
}
const object=(cols,prefix='')=>`json_object(${cols.map(c=>`'${c}',${prefix}${ident(c)}`).join(',')})`;
const rowKey=(keys,prefix='')=>`json_array(${keys.map(c=>prefix+ident(c)).join(',')})`;
function revisionMarker(table,cols,prefix=''){
 if(appendOnly.has(table))return "''";
 if(table==='catalog')return `CAST(${prefix}revision AS TEXT)`;
 if(table==='orders')return `json_array(${prefix}revision,json_extract(${prefix}doc,'$.archiveRef'))`;
 return object(cols,prefix);
}
function cachedMarker(table,row){
 if(appendOnly.has(table))return '';
 if(table==='catalog')return String(row.revision);
 if(table==='orders')return JSON.stringify([row.revision,JSON.parse(row.doc).archiveRef??null]);
 return JSON.stringify(row);
}
async function init(rt){
 for(const sql of [
  "CREATE TABLE IF NOT EXISTS archive_i_head(shop TEXT PRIMARY KEY,generation INTEGER NOT NULL,manifest_hash TEXT NOT NULL,pending TEXT,applied INTEGER NOT NULL DEFAULT 0,applied_table TEXT NOT NULL DEFAULT '') WITHOUT ROWID",
  'CREATE TABLE IF NOT EXISTS archive_i_cache(shop TEXT,table_name TEXT,row_key TEXT,doc TEXT,PRIMARY KEY(shop,table_name,row_key)) WITHOUT ROWID',
  'CREATE TABLE IF NOT EXISTS local_export_rows(snapshot TEXT,table_name TEXT,seq INTEGER,doc TEXT,PRIMARY KEY(snapshot,table_name,seq)) WITHOUT ROWID',
  'CREATE TABLE IF NOT EXISTS archive_i_guard(ok INTEGER CHECK(ok=1))'
 ])await rt.db.prepare(sql).run();
 // Revision optimization has executable source contracts, not assumptions about
 // timestamps. Existing online business transitions increment revision. The
 // separately verified terminal compactor is the only unversioned doc change.
 for(const table of appendOnly){await rt.db.prepare(`CREATE TRIGGER IF NOT EXISTS archive_i_immutable_${table} BEFORE UPDATE ON ${ident(table)} BEGIN SELECT RAISE(ABORT,'Archive append-only contract'); END`).run();}
 for(const table of ['pair_operations','pair_audit'])await rt.db.prepare(`CREATE TRIGGER IF NOT EXISTS archive_i_preserve_${table} BEFORE DELETE ON ${ident(table)} BEGIN SELECT RAISE(ABORT,'Permanent archive evidence'); END`).run();
 await rt.db.prepare("CREATE TRIGGER IF NOT EXISTS archive_i_catalog_revision BEFORE UPDATE ON catalog WHEN NEW.revision<=OLD.revision BEGIN SELECT RAISE(ABORT,'Catalog revision must advance'); END").run();
 // All non-doc fields participate: a silent claim/bill/identity change cannot
 // evade the marker. Terminal compaction must retain its bill and all identity.
 const {columns}=await shape(rt,'orders'),unchanged=columns.filter(c=>c!=='doc').map(c=>`NEW.${ident(c)} IS OLD.${ident(c)}`).join(' AND ');
 await rt.db.prepare(`CREATE TRIGGER IF NOT EXISTS archive_i_order_revision BEFORE UPDATE ON orders WHEN NEW.revision<=OLD.revision AND NOT COALESCE((NEW.revision=OLD.revision AND ${unchanged} AND OLD.status IN ('COMPLETED','CANCELLED') AND json_extract(OLD.doc,'$.archiveRef') IS NULL AND json_type(NEW.doc,'$.archiveRef')='text' AND length(json_extract(NEW.doc,'$.archiveRef'))=64 AND json_extract(NEW.doc,'$.archiveRef') NOT GLOB '*[^a-f0-9]*' AND NEW.doc<>OLD.doc),0) BEGIN SELECT RAISE(ABORT,'Order revision or terminal archive contract'); END`).run();
}
export async function resumeIncrement(rt,shop){
 assert.match(shop,/^[a-z0-9-]{1,80}$/);
 const head=await rt.db.prepare('SELECT * FROM archive_i_head WHERE shop=?').bind(shop).first();assert.ok(head?.pending,'No pending incremental capture');
 assert.match(head.pending,/^[a-f0-9-]{36}$/);
 return {id:head.pending,shop,chain:{format:'HWPOS-DELTA-1',packing:8,generation:head.generation+1,parentHash:head.manifest_hash,baseline:head.generation===0}};
}
export async function startIncrement(rt,shop){
 assert.match(shop,/^[a-z0-9-]{1,80}$/);const size=await rt.db.prepare('SELECT id FROM orders LIMIT 1').all();assert.ok(size.meta.size_after<450000000,'Admission cutoff');
 await init(rt);await rt.db.prepare("INSERT INTO archive_i_head(shop,generation,manifest_hash) VALUES(?,0,'') ON CONFLICT(shop) DO NOTHING").bind(shop).run();
 const head=await rt.db.prepare('SELECT * FROM archive_i_head WHERE shop=?').bind(shop).first();assert.equal(head.pending,null,'Finish the pending archive before starting another');
 const id=randomUUID(),chain={format:'HWPOS-DELTA-1',packing:8,generation:head.generation+1,parentHash:head.manifest_hash,baseline:head.generation===0},statements=[];
 statements.push(rt.db.prepare('INSERT INTO archive_i_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM archive_i_head WHERE shop=? AND generation=? AND manifest_hash=? AND pending IS NULL) THEN 1 ELSE 0 END').bind(shop,head.generation,head.manifest_hash));
 for(const table of tables){const {columns,keys}=await shape(rt,table),doc=object(columns,'s.'),key=rowKey(keys,'s.');
  // Exact values for unversioned small tables; enforced revision/immutability
  // contracts for large tables. Exported changed rows always contain ALL fields.
  const sql=`SELECT ${key} k,${doc} d,${revisionMarker(table,columns,'s.')} marker FROM ${ident(table)} s WHERE s.shop=?`;
  const changed=`SELECT ROW_NUMBER() OVER(ORDER BY s.k) rn,json_object('key',json(s.k),'deleted',0,'row',json(s.d)) change FROM (${sql}) s LEFT JOIN archive_i_cache c ON c.shop=? AND c.table_name=? AND c.row_key=s.k WHERE c.row_key IS NULL OR c.doc<>s.marker`;
  statements.push(rt.db.prepare(`INSERT INTO local_export_rows SELECT ?,?,MIN(rn),json_group_array(json(change)) FROM (${changed}) GROUP BY CAST((rn-1)/8 AS INTEGER)`).bind(id,table,shop,shop,table));
  const missing=`SELECT c.row_key FROM archive_i_cache c WHERE c.shop=? AND c.table_name=? AND NOT EXISTS(SELECT 1 FROM ${ident(table)} s WHERE s.shop=? AND ${keys.map((k,i)=>`s.${ident(k)}=json_extract(c.row_key,'$[${i}]')`).join(' AND ')})`;
  if(permanent.has(table))statements.push(rt.db.prepare(`INSERT INTO archive_i_guard SELECT CASE WHEN NOT EXISTS(${missing}) THEN 1 ELSE 0 END`).bind(shop,table,shop));
  else statements.push(rt.db.prepare(`INSERT INTO local_export_rows SELECT ?,?,1000000000+MIN(rn),json_group_array(json(change)) FROM (SELECT ROW_NUMBER() OVER(ORDER BY row_key) rn,json_object('key',json(row_key),'deleted',1,'row',NULL) change FROM (${missing})) GROUP BY CAST((rn-1)/8 AS INTEGER)`).bind(id,table,shop,table,shop));
 }
 statements.push(rt.db.prepare("UPDATE archive_i_head SET pending=?,applied=0,applied_table='' WHERE shop=?").bind(id,shop),rt.db.prepare('DELETE FROM archive_i_guard'));
 assert.ok(statements.length<=50);await rt.db.batch(statements);
 return {id,shop,chain};
}
async function decode(directory,secondCopy,m,key,c){
 assert.equal(hash(await readBounded(path.join(secondCopy,c.file))),c.sha256);
 const b=await readBounded(path.join(directory,c.file));assert.equal(hash(b),c.sha256);
 const e=JSON.parse(b);assert.equal(e.aad,JSON.stringify({snapshot:m.snapshot,shop:m.shop,table:c.table,index:c.index,previous:c.previous}));
 const cipher=createDecipheriv('aes-256-gcm',key,Buffer.from(e.iv,'hex'));cipher.setAAD(Buffer.from(e.aad));cipher.setAuthTag(Buffer.from(e.tag,'hex'));
 const plain=Buffer.concat([cipher.update(Buffer.from(e.data,'base64')),cipher.final()]);assert.ok(plain.length<=maxPage);assert.equal(hash(plain),c.plainHash);
 const rows=JSON.parse(plain);assert.equal(rows.length,c.rows);return rows;
}
async function verifyChain(chain,passphrase,visit){
 assert.ok(chain.length>0);let parent='',shop,generation=0;
 for(const entry of chain){assert.notEqual(path.resolve(entry.directory),path.resolve(entry.secondCopy));
  const {m,key}=await load(entry.directory,passphrase),other=await load(entry.secondCopy,passphrase);assert.ok(m.complete&&other.m.complete);assert.equal(m.mac,other.m.mac);
  assert.equal(m.chain?.format,'HWPOS-DELTA-1');assert.equal(m.chain.packing,8);assert.equal(m.chain.generation,++generation);assert.equal(m.chain.parentHash,parent);assert.equal(m.chain.baseline,generation===1);shop??=m.shop;assert.equal(m.shop,shop);
  let previous='0'.repeat(64),index=0;
  for(const c of m.chunks){assert.equal(c.previous,previous);assert.equal(c.index,index++);assert.ok(tables.includes(c.table));const packed=await decode(entry.directory,entry.secondCopy,m,key,c);assert.ok(packed.length<=4);const page=packed.flatMap(({doc})=>{const changes=JSON.parse(doc);assert.ok(Array.isArray(changes)&&changes.length>=1&&changes.length<=8);return changes.map(change=>({doc:JSON.stringify(change)}));});previous=c.sha256;await visit?.(m,c.table,page);}
  parent=hash(await readFile(path.join(entry.directory,'manifest.json')));
 }
 return {shop,generation,manifestHash:parent};
}
export async function restoreChain({chain,passphrase,bindings={},ownerConfirmed=false}){
 assert.ok(ownerConfirmed);const isolated=await runtime({bindings:{...bindings,AUTH_MODE:'PAIRING_LOCAL',PAIR_QUARANTINED:'true'},pooledTransport:true}),cost={rowsRead:0,rowsWritten:0,statements:0,maxQueries:0};
 const record=results=>{for(const r of results){cost.rowsRead+=r.meta.rows_read??0;cost.rowsWritten+=r.meta.rows_written??0;cost.statements++;}cost.maxQueries=Math.max(cost.maxQueries,results.length);};
 try{const proof=await verifyChain(chain,passphrase,async(m,table,page)=>{
  const {columns,keys}=await shape(isolated,table),statements=[];
  for(const {doc} of page){const change=JSON.parse(doc);assert.ok(Array.isArray(change.key)&&change.key.length===keys.length);const where=keys.map(k=>`${ident(k)}=?`).join(' AND ');
   if(change.deleted){assert.ok(!permanent.has(table));statements.push(isolated.db.prepare(`DELETE FROM ${ident(table)} WHERE shop=? AND ${where}`).bind(m.shop,...change.key));}
   else{const r=change.row;assert.equal(r.shop,m.shop);assert.deepEqual(Object.keys(r),columns);assert.deepEqual(keys.map(k=>r[k]),change.key);
    statements.push(isolated.db.prepare(`INSERT INTO ${ident(table)}(${columns.map(ident)}) VALUES(${columns.map(()=>'?')}) ON CONFLICT(${keys.map(ident)}) DO UPDATE SET ${columns.map(k=>`${ident(k)}=excluded.${ident(k)}`).join(',')}`).bind(...columns.map(k=>r[k])));
   }
  }
  assert.ok(statements.length<=32);record(await isolated.db.batch(statements));
  const comparisons=await isolated.db.batch(page.map(({doc})=>{const c=JSON.parse(doc);return isolated.db.prepare(`SELECT * FROM ${ident(table)} WHERE shop=? AND ${keys.map(k=>`${ident(k)}=?`).join(' AND ')}`).bind(m.shop,...c.key);}));record(comparisons);
  for(let i=0;i<page.length;i++){const c=JSON.parse(page[i].doc);assert.deepEqual(comparisons[i].results,c.deleted?[]:[c.row]);}
 });
 // Quarantine only AFTER all deltas: a later archived ACTIVE row cannot undo it.
 record(await isolated.db.batch([
  isolated.db.prepare("UPDATE pair_authority SET status='QUARANTINED',epoch=epoch+1 WHERE shop=?").bind(proof.shop),
  isolated.db.prepare("UPDATE pair_devices SET status='REVOKED',version=version+1 WHERE shop=?").bind(proof.shop),
  isolated.db.prepare('UPDATE pair_grants SET consumed=1 WHERE shop=?').bind(proof.shop)
 ]));
 const result={...isolated,report:{...proof,recoveryCost:cost,recoveryIncludingSchemaAndVerification:await isolated.fixtureSqlMeter(),allFieldsCompared:true,authority:'QUARANTINED',independentPhysicalMedia:false}};verified.set(result,proof);return result;
 }catch(e){await isolated.close();throw e;}
}
export async function acknowledgeIncrement(rt,{snapshot,chain,passphrase,restored,ownerConfirmed=false,stopAfterPages=Infinity}){
 const proof=verified.get(restored);assert.ok(ownerConfirmed&&proof);assert.deepEqual(await verifyChain(chain,passphrase),proof);assert.equal(proof.shop,snapshot.shop);assert.equal(proof.generation,snapshot.chain.generation);
 const last=(await load(chain.at(-1).directory,passphrase)).m;assert.equal(last.snapshot,snapshot.id);
 let pages=0;
 while(true){const head=await rt.db.prepare('SELECT * FROM archive_i_head WHERE shop=?').bind(snapshot.shop).first();
  if(head.pending===null){assert.equal(head.generation,proof.generation);assert.equal(head.manifest_hash,proof.manifestHash);return {complete:true};}
  assert.equal(head.pending,snapshot.id);assert.equal(head.generation,proof.generation-1);
  // Existing composite primary key supplies a bounded keyset page. No OFFSET,
  // full-table rowid sort or extra write-amplifying cursor index is required.
  const rows=(await rt.db.prepare('SELECT seq,table_name,doc FROM local_export_rows WHERE snapshot=? AND (table_name,seq)>(?,?) ORDER BY table_name,seq LIMIT 4').bind(snapshot.id,head.applied_table,head.applied).all()).results;
  const guard=rt.db.prepare('INSERT INTO archive_i_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM archive_i_head WHERE shop=? AND pending=? AND applied=? AND applied_table=?) THEN 1 ELSE 0 END').bind(snapshot.shop,snapshot.id,head.applied,head.applied_table);
  if(!rows.length){await rt.db.batch([guard,rt.db.prepare('DELETE FROM local_export_rows WHERE snapshot=?').bind(snapshot.id),rt.db.prepare("UPDATE archive_i_head SET generation=?,manifest_hash=?,pending=NULL,applied=0,applied_table='' WHERE shop=?").bind(proof.generation,proof.manifestHash,snapshot.shop),rt.db.prepare('DELETE FROM archive_i_guard')]);return {complete:true};}
  const writes=rows.flatMap(r=>{const changes=JSON.parse(r.doc);assert.ok(Array.isArray(changes)&&changes.length<=8);return changes.map(c=>{const key=JSON.stringify(c.key);return c.deleted?rt.db.prepare('DELETE FROM archive_i_cache WHERE shop=? AND table_name=? AND row_key=?').bind(snapshot.shop,r.table_name,key):rt.db.prepare('INSERT INTO archive_i_cache VALUES(?,?,?,?) ON CONFLICT(shop,table_name,row_key) DO UPDATE SET doc=excluded.doc').bind(snapshot.shop,r.table_name,key,cachedMarker(r.table_name,c.row));});});
  await rt.db.batch([guard,...writes,rt.db.prepare('UPDATE archive_i_head SET applied=?,applied_table=? WHERE shop=?').bind(rows.at(-1).seq,rows.at(-1).table_name,snapshot.shop),rt.db.prepare('DELETE FROM archive_i_guard')]);
  if(++pages>=stopAfterPages)return {complete:false};
 }
}
