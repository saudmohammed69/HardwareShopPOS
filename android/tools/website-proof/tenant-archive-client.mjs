// Owner workstation proof: encrypt locally; never send archive passphrases to API.
import {createHash,randomBytes,scryptSync,createCipheriv,createDecipheriv,randomUUID} from 'node:crypto';
import {mkdir,open,readFile} from 'node:fs/promises';
import {gzipSync,gunzipSync} from 'node:zlib';
import path from 'node:path';
import assert from 'node:assert/strict';
import {runtime} from './runtime.mjs';
import {tenantTables} from './tenant-archive.mjs';
const hash=b=>createHash('sha256').update(b).digest('hex');
export function validateTenantSnapshot(p){
 assert.equal(p.format,'HWPOS-tenant-snapshot');assert.equal(p.version,1);assert.equal(p.authorityOnRestore,'QUARANTINED');assert.equal(p.cleanupAuthorized,false);
 assert.ok(typeof p.shop==='string'&&p.shop.length>0);assert.deepEqual(Object.keys(p.rows).sort(),[...tenantTables].sort());
 const members=new Set(p.rows.auth_memberships.map(r=>r.uid));
 for(const table of tenantTables){assert.ok(p.rows[table].length<=200);for(const r of p.rows[table]){
  if(['auth_users','auth_identity_bindings'].includes(table))assert.ok(members.has(r.uid));
  else assert.equal(r.shop,p.shop);
 }}
 const devices=new Set(p.rows.auth_devices.flatMap(r=>[r.id,'enroll:'+r.fingerprint]));
 for(const r of p.rows.auth_bootstrap)devices.add('enroll:'+r.fingerprint);
 for(const r of p.rows.auth_nonces)assert.ok(devices.has(r.device)||/^enroll:[a-f0-9]{64}$/.test(r.device));
 assert.ok(p.orders.length<=12&&p.events.length<=1000);
 const ids=new Set(p.orders.map(r=>{assert.equal(r.shop,p.shop);return r.id;}));
 for(const e of p.events){assert.equal(e.shop,p.shop);assert.ok(ids.has(e.order_id));}
}
export async function sealTenantSnapshot(snapshot,{directory,passphrase}){
 validateTenantSnapshot(snapshot);assert.ok(typeof passphrase==='string'&&passphrase.length>=16);
 const plain=Buffer.from(JSON.stringify(snapshot));assert.ok(plain.length<=2*1024*1024);
 const salt=randomBytes(16),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',scryptSync(passphrase,salt,32),iv);
 const data=Buffer.concat([cipher.update(gzipSync(plain)),cipher.final()]);
 const bytes=Buffer.from(JSON.stringify({format:'HWPOS-tenant-encrypted',version:1,salt:salt.toString('hex'),iv:iv.toString('hex'),tag:cipher.getAuthTag().toString('hex'),plainHash:hash(plain),data:data.toString('base64')}));
 await mkdir(directory,{recursive:true});const file=path.join(directory,randomUUID()+'.hwpos-tenant');
 const h=await open(file,'wx',0o600);try{await h.writeFile(bytes);await h.sync();}finally{await h.close();}
 return {file,sha256:hash(bytes),bytes:bytes.length,shop:snapshot.shop};
}
export async function restoreTenantArchive(archive,passphrase,{secondCopy,ownerConfirmed=false}={}){
 assert.ok(ownerConfirmed&&secondCopy&&path.resolve(secondCopy)!==path.resolve(archive.file));
 const bytes=await readFile(archive.file);assert.equal(hash(bytes),archive.sha256);assert.equal(hash(await readFile(secondCopy)),archive.sha256);
 const e=JSON.parse(bytes);assert.equal(e.format,'HWPOS-tenant-encrypted');assert.equal(e.version,1);
 const decipher=createDecipheriv('aes-256-gcm',scryptSync(passphrase,Buffer.from(e.salt,'hex'),32),Buffer.from(e.iv,'hex'));decipher.setAuthTag(Buffer.from(e.tag,'hex'));
 const plain=gunzipSync(Buffer.concat([decipher.update(Buffer.from(e.data,'base64')),decipher.final()]),{maxOutputLength:2*1024*1024});assert.equal(hash(plain),e.plainHash);
 const p=JSON.parse(plain);validateTenantSnapshot(p);assert.equal(p.shop,archive.shop);
 // No target/overwrite option. This creates only a new authority-disabled DB.
 const isolated=await runtime({bindings:{AUTH_MODE:'SIGNED_LOCAL'}}),now=Math.floor(Date.now()/1000),expected={};
 try{
  for(const table of [...tenantTables,'orders','events']){
   const rows=(p.rows[table]??p[table]).map(original=>{const r={...original};
    if(table==='auth_users'){r.disabled=1;r.checked_until=0;r.valid_after=Math.max(r.valid_after,now+1);}
    if(table==='auth_identity_bindings')r.checked_until=0;
    if(table==='auth_memberships'){r.active=0;r.version++;r.valid_after=Math.max(r.valid_after,now+1);}
    if(table==='auth_devices'){r.status='REVOKED';r.version++;}
    if(table==='auth_nonces')r.expires_at=Math.max(r.expires_at,now+61);
    if(table==='auth_bootstrap')r.expires_at=0;
    return r;
   });expected[table]=rows;
   const allowed=new Set((await isolated.db.prepare(`PRAGMA table_info(${table})`).all()).results.map(c=>c.name));
   for(let i=0;i<rows.length;i+=40)await isolated.db.batch(rows.slice(i,i+40).map(r=>{
    const cols=Object.keys(r);assert.ok(cols.every(c=>allowed.has(c)));return isolated.db.prepare(`INSERT INTO ${table}(${cols.join(',')}) VALUES(${cols.map(()=>'?').join(',')})`).bind(...cols.map(c=>r[c]));
   }));
   const restored=(await isolated.db.prepare(`SELECT * FROM ${table} ORDER BY rowid`).all()).results;
   // Compare every field; order PK rowid differs for events/order sorting.
   const stable=values=>values.map(r=>JSON.stringify(r,Object.keys(r).sort())).sort();assert.deepEqual(stable(restored),stable(rows));
  }
  return {...isolated,report:{shop:p.shop,counts:Object.fromEntries(Object.entries(expected).map(([t,r])=>[t,r.length])),authority:'QUARANTINED',cleanupAuthorized:false,sourceUnmodified:true}};
 }catch(error){await isolated.close();throw error;}
}
