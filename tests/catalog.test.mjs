import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';
import {execFileSync,spawn} from 'node:child_process';
import {once} from 'node:events';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const catalog=JSON.parse(await readFile(path.join(root,'public/catalog.json'),'utf8'));
test('catalog contains only the approved display fields and has unique priced variants',()=>{
 assert.deepEqual(Object.keys(catalog),['products']);assert.equal(catalog.products.length,1478);const seen=new Set();
 for(const p of catalog.products){assert.deepEqual(Object.keys(p),['name','size','unit','sellingPricePaisa']);assert.ok(p.name.trim());assert.ok(p.unit.trim());assert.match(p.sellingPricePaisa,/^[1-9]\d*$/);const key=JSON.stringify([p.name.normalize('NFKC').toLowerCase().trim(),p.size.normalize('NFKC').toLowerCase().trim(),p.unit.normalize('NFKC').toLowerCase().trim()]);assert.ok(!seen.has(key));seen.add(key);}
 assert.ok(!JSON.stringify(catalog).match(/purchasePrice|currentStock|supplier|\bstock\b|password|secret/i));
});
test('catalog-only build has no ordering, image, external asset or backend files',async()=>{
 assert.throws(()=>execFileSync(process.execPath,[path.join(root,'build.mjs')],{cwd:root,stdio:'pipe'}));
 execFileSync(process.execPath,[path.join(root,'build.mjs'),'--preview'],{cwd:root,stdio:'pipe'});
 const dist=path.join(root,'dist');const files=(await readdir(dist)).sort();assert.deepEqual(files.sort(),['404.html','_headers','app.mjs','catalog.json','deployment-manifest.json','index.html','public-config.json','robots.txt','styles.css']);
 const text=files.map(async f=>[f,await readFile(path.join(dist,f),'utf8')]);const entries=await Promise.all(text);const all=entries.map(([,v])=>v).join('\n');
 const config=JSON.parse(await readFile(path.join(dist,'public-config.json'),'utf8'));assert.equal(config.liveOrdering,false);assert.equal(config.mode,'CATALOG_ONLY');const manifest=JSON.parse(await readFile(path.join(dist,'deployment-manifest.json'),'utf8'));assert.equal(manifest.mode,'REVIEW_PREVIEW');assert.equal(manifest.approvedCatalogSha256,null);assert.equal(manifest.liveOrdering,false);assert.equal(manifest.functions,false);
 assert.doesNotMatch(all,/<img\b|<form\b|<picture\b|src=["']https?:|href=["']https?:|fetch\(["']https?:/i);assert.ok(!all.includes('/api/'));
 assert.match(all,/img-src 'none'/);assert.doesNotMatch(all,/purchasePrice|currentStock|service_account|PRIVATE KEY-----/i);
});

test('local HTTP preview serves the catalog safely and rejects mutations',async()=>{
 const child=spawn(process.execPath,[path.join(root,'serve.mjs')],{cwd:root,env:{...process.env,PORT:'0'},stdio:['ignore','pipe','pipe']});
 let line='';try{
  const ready=new Promise((resolve,reject)=>{child.stdout.setEncoding('utf8');child.stdout.on('data',chunk=>{line+=chunk;const m=line.match(/http:\/\/127\.0\.0\.1:(\d+)\//);if(m)resolve(Number(m[1]));});child.once('error',reject);child.once('exit',code=>reject(new Error(`preview exited ${code}`)));});
  let startTimer;const startupDeadline=new Promise((_,reject)=>{startTimer=setTimeout(()=>reject(new Error('preview startup timeout')),5000);});const port=await Promise.race([ready,startupDeadline]);clearTimeout(startTimer);const base=`http://127.0.0.1:${port}`;
  const page=await fetch(base+'/');assert.equal(page.status,200);assert.match(page.headers.get('content-security-policy'),/form-action 'none'/);assert.match(await page.text(),/HardwareShopPOS/);
  const data=await fetch(base+'/catalog.json');assert.equal(data.status,200);assert.equal((await data.json()).products.length,1478);
  const mutation=await fetch(base+'/api/orders',{method:'POST',headers:{'content-type':'application/json'},body:'{}'});assert.equal(mutation.status,405);assert.equal((await fetch(base+'/missing')).status,404);
 }finally{child.kill();await Promise.race([once(child,'exit'),new Promise(resolve=>setTimeout(resolve,1000))]);}
});
