// Hosted-compatible protocol proof ONLY. No cleanup, live restore or secret key.
import {fail,exact,uuid} from './pricing.mjs';
export const tenantTables=['auth_users','auth_identity_bindings','auth_memberships','auth_worker_links','auth_devices','auth_nonces','auth_audit','auth_bootstrap','auth_claim_reviews','auth_local_worker_bindings'];
export async function tenantSnapshot(request,shop,db,authorize,readJson){
 if(new URL(request.url).protocol!=='https:')fail(403,'Archive transport requires HTTPS');
 const a=await authorize();const b=await readJson(request);exact(b,['orderIds']);
 if(!Array.isArray(b.orderIds)||b.orderIds.length>12||new Set(b.orderIds).size!==b.orderIds.length)fail(400,'Select at most 12 unique orders');
 b.orderIds.forEach(uuid);
 const member='SELECT uid FROM auth_memberships WHERE shop=?';
 const specs=[
  ['auth_users',`SELECT * FROM auth_users WHERE uid IN (${member})`,[shop]],
  ['auth_identity_bindings',`SELECT * FROM auth_identity_bindings WHERE uid IN (${member})`,[shop]],
  ['auth_memberships','SELECT * FROM auth_memberships WHERE shop=?',[shop]],
  ['auth_worker_links','SELECT * FROM auth_worker_links WHERE shop=?',[shop]],
  ['auth_devices','SELECT * FROM auth_devices WHERE shop=?',[shop]],
  ['auth_nonces','SELECT * FROM auth_nonces WHERE shop=?',[shop]],
  ['auth_audit','SELECT * FROM auth_audit WHERE shop=?',[shop]],
  ['auth_bootstrap','SELECT * FROM auth_bootstrap WHERE shop=?',[shop]],
  ['auth_claim_reviews','SELECT * FROM auth_claim_reviews WHERE shop=?',[shop]],
  ['auth_local_worker_bindings','SELECT * FROM auth_local_worker_bindings WHERE shop=?',[shop]]
 ];
 const placeholders=b.orderIds.map(()=>'?').join(',')||'NULL';
 // All reads and current owner authorization share one atomic D1 batch.
 const queries=specs.map(([,sql,args])=>db.statement(sql+' ORDER BY rowid LIMIT 201',args));
 queries.push(db.statement(`SELECT * FROM orders WHERE shop=? AND id IN (${placeholders}) ORDER BY id`,[shop,...b.orderIds]));
 queries.push(db.statement(`SELECT * FROM events WHERE shop=? AND order_id IN (${placeholders}) ORDER BY seq LIMIT 1001`,[shop,...b.orderIds]));
 const results=await db.batch(queries);
 if(results.slice(0,10).some(r=>r.results.length>200)||results[11].results.length>1000)fail(409,'Archive bound exceeded; reviewed paginated snapshot protocol required. Nothing is truncated or cleaned.');
 if(results[10].results.length!==b.orderIds.length)fail(404,'Selected order unavailable');
 const snapshot={format:'HWPOS-tenant-snapshot',version:1,shop,createdAt:Date.now(),owner:a.uid,
  rows:Object.fromEntries(specs.map(([table],i)=>[table,results[i].results])),orders:results[10].results,events:results[11].results,
  authorityOnRestore:'QUARANTINED',cleanupAuthorized:false};
 if(new TextEncoder().encode(JSON.stringify(snapshot)).length>2*1024*1024)fail(413,'Archive snapshot bound exceeded; nothing cleaned');
 return snapshot;
}
