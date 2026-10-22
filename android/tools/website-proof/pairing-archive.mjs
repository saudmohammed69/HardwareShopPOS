// Isolated owner archive of the complete pairing/security/operation ledger.
// No overwrite/live restore or cleanup. Restored authority always quarantined.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {randomBytes,randomUUID,createHash,scryptSync,createCipheriv,createDecipheriv} from 'node:crypto';
import {gzipSync,gunzipSync} from 'node:zlib';
import path from 'node:path';
import assert from 'node:assert/strict';
import {runtime} from './runtime.mjs';
const tables=['pair_authority','pair_devices','pair_grants','pair_audit','pair_operations','auth_nonces'];
const hash=b=>createHash('sha256').update(b).digest('hex');
export async function exportPairing(rt,{directory,passphrase}){
 assert.ok(passphrase.length>=16);
 const results=await rt.db.batch(tables.map(t=>rt.db.prepare(`SELECT * FROM ${t} ORDER BY rowid`)));
 const rows=Object.fromEntries(tables.map((t,i)=>[t,results[i].results]));
 const plain=Buffer.from(JSON.stringify({format:'HWPOS-local-pairing',version:1,rows}));assert.ok(plain.length<=16*1024*1024);
 const salt=randomBytes(16),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',scryptSync(passphrase,salt,32),iv);
 const data=Buffer.concat([cipher.update(gzipSync(plain)),cipher.final()]);
 const bytes=Buffer.from(JSON.stringify({format:'HWPOS-encrypted-pairing',version:1,salt:salt.toString('hex'),iv:iv.toString('hex'),tag:cipher.getAuthTag().toString('hex'),plainHash:hash(plain),data:data.toString('base64')}));
 await mkdir(directory,{recursive:true});const file=path.join(directory,randomUUID()+'.hwpos-pairing');await writeFile(file,bytes,{flag:'wx',mode:0o600});
 return {file,sha256:hash(bytes),bytes:bytes.length,counts:Object.fromEntries(tables.map(t=>[t,rows[t].length])),sourceRowsRead:results.reduce((n,r)=>n+r.meta.rows_read,0)};
}
export async function restorePairing(archive,passphrase,{secondCopy,ownerConfirmed=false,bindings={}}={}){
 assert.ok(ownerConfirmed&&path.resolve(secondCopy)!==path.resolve(archive.file));const bytes=await readFile(archive.file);
 assert.equal(hash(bytes),archive.sha256);assert.equal(hash(await readFile(secondCopy)),archive.sha256);
 const e=JSON.parse(bytes);assert.equal(e.format,'HWPOS-encrypted-pairing');assert.equal(e.version,1);
 const decipher=createDecipheriv('aes-256-gcm',scryptSync(passphrase,Buffer.from(e.salt,'hex'),32),Buffer.from(e.iv,'hex'));decipher.setAuthTag(Buffer.from(e.tag,'hex'));
 const plain=gunzipSync(Buffer.concat([decipher.update(Buffer.from(e.data,'base64')),decipher.final()]),{maxOutputLength:16*1024*1024});assert.equal(hash(plain),e.plainHash);
 const p=JSON.parse(plain);assert.equal(p.format,'HWPOS-local-pairing');assert.equal(p.version,1);assert.deepEqual(Object.keys(p.rows).sort(),[...tables].sort());
 const isolated=await runtime({bindings:{...bindings,AUTH_MODE:'PAIRING_LOCAL',PAIR_QUARANTINED:'true'},pooledTransport:true});
 try{
  for(const t of tables){const allowed=new Set((await isolated.db.prepare(`PRAGMA table_info(${t})`).all()).results.map(c=>c.name));
   const rows=p.rows[t].map(x=>{const r={...x};if(t==='pair_authority'){r.status='QUARANTINED';r.epoch++;}if(t==='pair_devices'){r.status='REVOKED';r.version++;}if(t==='pair_grants')r.consumed=1;return r;});
   for(let i=0;i<rows.length;i+=40)await isolated.db.batch(rows.slice(i,i+40).map(r=>{const cols=Object.keys(r);assert.ok(cols.every(c=>allowed.has(c)));return isolated.db.prepare(`INSERT INTO ${t}(${cols.join(',')}) VALUES(${cols.map(()=>'?')})`).bind(...cols.map(c=>r[c]));}));
   assert.deepEqual((await isolated.db.prepare(`SELECT * FROM ${t} ORDER BY rowid`).all()).results,rows);
  }
  return {...isolated,report:{authority:'QUARANTINED',counts:archive.counts,allFieldsCompared:true,sourceUnmodified:true}};
 }catch(e){await isolated.close();throw e;}
}
