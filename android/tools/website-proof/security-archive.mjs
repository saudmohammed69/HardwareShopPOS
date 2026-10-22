// Owner-operated LOCAL archive. No live target/overwrite, auto-cleanup or authority
// reactivation API. Restored security is evidence, never a new trust source.
import {createHash,randomBytes,scryptSync,createCipheriv,createDecipheriv,randomUUID} from 'node:crypto';
import {mkdir,open,readFile} from 'node:fs/promises';
import {gzipSync,gunzipSync} from 'node:zlib';
import path from 'node:path';
import {runtime} from './runtime.mjs';
const tables=['auth_users','auth_identity_bindings','auth_memberships','auth_worker_links','auth_devices','auth_nonces','auth_audit','auth_bootstrap','auth_claim_reviews','auth_local_worker_bindings'];
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
export async function exportSecurity(rt,{directory,passphrase}){
 if(typeof passphrase!=='string'||passphrase.length<16)throw Error('Private passphrase required');
 // One real D1 batch provides a consistent snapshot across all security tables.
 const results=await rt.db.batch(tables.map(table=>rt.db.prepare(`SELECT * FROM ${table} ORDER BY rowid`)));
 const rows=Object.fromEntries(tables.map((t,i)=>[t,results[i].results]));
 const bytes=Buffer.from(JSON.stringify({format:'HWPOS-local-security',version:1,createdAt:Date.now(),rows}));
 if(bytes.length>16*1024*1024)throw Error('Local security archive bound exceeded; do not truncate evidence');
 const salt=randomBytes(16),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',scryptSync(passphrase,salt,32),iv);
 const data=Buffer.concat([cipher.update(gzipSync(bytes)),cipher.final()]);
 const envelope=Buffer.from(JSON.stringify({format:'HWPOS-encrypted-security',version:1,salt:salt.toString('hex'),iv:iv.toString('hex'),tag:cipher.getAuthTag().toString('hex'),sha256:digest(bytes),data:data.toString('base64')}));
 await mkdir(directory,{recursive:true});const file=path.join(directory,randomUUID()+'.hwpos-security');
 const h=await open(file,'wx',0o600);try{await h.writeFile(envelope);await h.sync();}finally{await h.close();}
 return {file,sha256:digest(envelope),plaintextSha256:digest(bytes),encryptedBytes:envelope.length,counts:Object.fromEntries(tables.map(t=>[t,rows[t].length])),
  sourceRead:results.reduce((n,r)=>n+r.meta.rows_read,0),sourceWritten:results.reduce((n,r)=>n+r.meta.rows_written,0)};
}
export async function restoreSecurity(archive,passphrase,{secondCopy,ownerConfirmed=false,bindings}={}){
 if(!ownerConfirmed||!secondCopy||path.resolve(secondCopy)===path.resolve(archive.file))throw Error('Owner confirmation and verified second copy required');
 const bytes=await readFile(archive.file);
 if(digest(bytes)!==archive.sha256||digest(await readFile(secondCopy))!==archive.sha256)throw Error('Security archive checksum mismatch');
 const e=JSON.parse(bytes);if(e.format!=='HWPOS-encrypted-security'||e.version!==1)throw Error('Unsupported security archive');
 const decrypt=createDecipheriv('aes-256-gcm',scryptSync(passphrase,Buffer.from(e.salt,'hex'),32),Buffer.from(e.iv,'hex'));decrypt.setAuthTag(Buffer.from(e.tag,'hex'));
 const plain=gunzipSync(Buffer.concat([decrypt.update(Buffer.from(e.data,'base64')),decrypt.final()]),{maxOutputLength:16*1024*1024});
 if(digest(plain)!==e.sha256)throw Error('Security plaintext checksum mismatch');
 const payload=JSON.parse(plain),keys=Object.keys(payload.rows??{}).sort().join();
 const legacy=tables.filter(t=>t!=='auth_local_worker_bindings');
 if(payload.format!=='HWPOS-local-security'||payload.version!==1||![tables,legacy].some(t=>keys===[...t].sort().join()))throw Error('Unsupported security tables');
 // Original nine-table archives contain no installation/account approval. Never
 // invent that authority while restoring older evidence into the new schema.
 payload.rows.auth_local_worker_bindings??=[];
 const isolated=await runtime({bindings:{...bindings,AUTH_MODE:'SIGNED_LOCAL'}}),now=Math.floor(Date.now()/1000),counts={};
 try{
  for(const table of tables){
   const transformed=payload.rows[table].map(original=>{
    const row={...original};
    if(table==='auth_users'){row.disabled=1;row.checked_until=0;row.valid_after=Math.max(row.valid_after,now+1);}
    if(table==='auth_identity_bindings')row.checked_until=0;
    if(table==='auth_memberships'){row.active=0;row.version++;row.valid_after=Math.max(row.valid_after,now+1);}
    if(table==='auth_devices'){row.status='REVOKED';row.version++;}
    if(table==='auth_nonces'){row.expires_at=Math.max(row.expires_at,now+61);row.shop??='';}
    if(table==='auth_bootstrap')row.expires_at=0;
    return row;
   });
   // Columns originate only from the current fixed schema, not arbitrary SQL in
   // an authenticated but malformed archive.
   const allowed=new Set((await isolated.db.prepare(`PRAGMA table_info(${table})`).all()).results.map(c=>c.name));
   for(let start=0;start<transformed.length;start+=40){
    await isolated.db.batch(transformed.slice(start,start+40).map(row=>{
     const columns=Object.keys(row);if(columns.some(c=>!allowed.has(c)))throw Error('Unknown security column');
     return isolated.db.prepare(`INSERT INTO ${table}(${columns.join(',')}) VALUES(${columns.map(()=>'?').join(',')})`).bind(...columns.map(c=>row[c]));
    }));
   }
   const restored=(await isolated.db.prepare(`SELECT * FROM ${table} ORDER BY rowid`).all()).results;
   if(JSON.stringify(restored)!==JSON.stringify(transformed))throw Error('Security restore comparison failed');
   counts[table]=restored.length;
  }
  return {...isolated,report:{counts,archiveSha256:archive.sha256,authority:'QUARANTINED',oldDeviceReactivationAllowed:false,sourceUnmodified:true}};
 }catch(e){await isolated.close();throw e;}
}
