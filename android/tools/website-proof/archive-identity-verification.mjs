// Private verification of a captured/restored source. Never authorizes cleanup.
// Walk EXISTING shop indexes and compare by logical identity. Physical rowids
// are local traversal cursors only; restore never has to reproduce them.
import assert from 'node:assert/strict';
export async function compareArchiveIdentities(source,restored,table,shop='proof-shop'){
 assert.ok(['orders','pair_operations'].includes(table));
 let cursor=null,count=0;
 while(true){
  const receipts=table==='pair_operations';
  let rows;
  if(receipts&&cursor){
   // SQLite cannot reliably seek the implicit rowid through a tuple range.
   // Explicit equality on the index prefix permits a real rowid range seek,
   // even when thousands of configuration receipts share one order_id.
   rows=(await source.db.prepare('SELECT rowid AS __local_cursor,* FROM pair_operations INDEXED BY pair_operations_order WHERE shop=? AND order_id=? AND rowid>? ORDER BY rowid LIMIT 32').bind(shop,...cursor).all()).results;
   if(rows.length<32){
    const next=(await source.db.prepare('SELECT rowid AS __local_cursor,* FROM pair_operations INDEXED BY pair_operations_order WHERE shop=? AND order_id>? ORDER BY order_id,rowid LIMIT ?').bind(shop,cursor[0],32-rows.length).all()).results;
    rows.push(...next);
   }
  }else{
   const sql=receipts?
    'SELECT rowid AS __local_cursor,* FROM pair_operations INDEXED BY pair_operations_order WHERE shop=? ORDER BY order_id,rowid LIMIT 32':
    `SELECT * FROM orders WHERE shop=?${cursor?' AND submission>?':''} ORDER BY submission LIMIT 32`;
   rows=(await source.db.prepare(sql).bind(shop,...(cursor??[])).all()).results;
  }
  if(!rows.length){
   const copyCount=await restored.db.prepare(`SELECT COUNT(*) n FROM ${table} WHERE shop=?`).bind(shop).first();
   assert.equal(copyCount.n,count,'Restore contains an unmatched identity');return count;
  }
  const queries=rows.map(row=>receipts?
   restored.db.prepare('SELECT * FROM pair_operations WHERE device=? AND request_id=? AND shop=?').bind(row.device,row.request_id,shop):
   restored.db.prepare('SELECT * FROM orders WHERE shop=? AND submission=?').bind(shop,row.submission));
  const copies=await restored.db.batch(queries);assert.equal(copies.length,rows.length);
  for(let i=0;i<rows.length;i++){
   const {__local_cursor,...row}=rows[i];assert.deepEqual(copies[i].results,[row]);
  }
  const last=rows.at(-1);cursor=receipts?[last.order_id,last.__local_cursor]:[last.submission];count+=rows.length;
 }
}
