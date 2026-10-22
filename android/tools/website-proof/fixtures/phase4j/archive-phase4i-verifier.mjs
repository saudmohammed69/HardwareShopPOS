// Historical query shape, synthetic cost comparison only. No cleanup authority.
import assert from 'node:assert/strict';
export async function comparePhase4iTable(r,restored,table){
 const keys=(await r.db.prepare(`PRAGMA table_info(${table})`).all()).results.filter(c=>c.pk).sort((a,b)=>a.pk-b.pk).map(c=>c.name);let cursor=null,count=0;
 while(true){const where=cursor?` AND (${keys.join(',')})>(${keys.map(()=>'?')})`:'',sql=`SELECT * FROM ${table} WHERE shop=?${where} ORDER BY ${keys.join(',')} LIMIT 32`,args=['proof-shop',...(cursor??[])];
  const source=(await r.db.prepare(sql).bind(...args).all()).results,copy=(await restored.db.prepare(sql).bind(...args).all()).results;assert.deepEqual(copy,source);count+=source.length;if(!source.length)return count;cursor=keys.map(k=>source.at(-1)[k]);
 }
}
