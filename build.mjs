import {mkdir,copyFile,readFile,readdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root=path.dirname(fileURLToPath(import.meta.url));
const options=new Set(process.argv.slice(2));if([...options].some(x=>x!=='--preview')||options.size>1)throw Error('Only --preview is supported');
const preview=options.has('--preview'),out=path.join(root,'dist');
const catalogBytes=await readFile(path.join(root,'public','catalog.json'));const catalog=JSON.parse(catalogBytes);
if(Object.keys(catalog).join(',')!=='products'||!Array.isArray(catalog.products)||catalog.products.length>20000)throw Error('Invalid catalog envelope');
const keys=['name','size','unit','sellingPricePaisa'];const seen=new Set();
for(const p of catalog.products){if(Object.keys(p).some(k=>!keys.includes(k))||keys.some(k=>typeof p[k]!=='string')||!p.name.trim()||!p.unit.trim()||!/^[1-9]\d*$/.test(p.sellingPricePaisa))throw Error('Unsafe/invalid public product');const k=JSON.stringify([p.name.normalize('NFKC').toLowerCase().trim(),p.size.normalize('NFKC').toLowerCase().trim(),p.unit.normalize('NFKC').toLowerCase().trim()]);if(seen.has(k))throw Error('Duplicate public item');seen.add(k);}
const catalogHash=createHash('sha256').update(catalogBytes).digest('hex');let approvedCatalogHash=null;
if(!preview){let approval;try{approval=JSON.parse(await readFile(path.join(root,'public','catalog-approval.json'),'utf8'));}catch{throw Error('Public catalog deployment requires public/catalog-approval.json for this exact catalog hash');}
 if(approval.publicCatalogApproved!==true||approval.catalogSha256!==catalogHash||typeof approval.approvedAt!=='string'||!Number.isFinite(Date.parse(approval.approvedAt)))throw Error('Catalog approval is missing or does not match this exact catalog');approvedCatalogHash=catalogHash;}
const config=JSON.parse(await readFile(path.join(root,'public','public-config.json'),'utf8'));
if(Object.keys(config).some(k=>!['mode','liveOrdering','shopName'].includes(k))||config.mode!=='CATALOG_ONLY'||config.liveOrdering!==false||typeof config.shopName!=='string'||!config.shopName.trim()||config.shopName.length>200)throw Error('Invalid catalog-only public settings');
const sourceFiles=['index.html','app.mjs','styles.css','404.html'];
const publicFiles=['catalog.json','public-config.json','_headers','robots.txt'];
const assetFiles=[...sourceFiles,...publicFiles].sort();
await mkdir(out,{recursive:true});
const existing=await readdir(out,{withFileTypes:true});
if(existing.some(f=>!f.isFile()||!([...assetFiles,'deployment-manifest.json'].includes(f.name))))throw Error('Unexpected build output; use a fresh directory before publishing');
for(const file of sourceFiles)await copyFile(path.join(root,file),path.join(out,file));
for(const file of publicFiles)await copyFile(path.join(root,'public',file),path.join(out,file));

const manifest={schemaVersion:1,mode:preview?'REVIEW_PREVIEW':'CATALOG_ONLY',liveOrdering:false,customerMutations:false,functions:false,productCount:catalog.products.length,approvedCatalogSha256:approvedCatalogHash,files:{}};
for(const file of assetFiles){const b=await readFile(path.join(out,file));if(/PRIVATE KEY-----|service_account|refresh_token|purchasePrice|currentStock|supplier/i.test(b.toString()))throw Error('Unsafe marker in '+file);manifest.files[file]={bytes:b.length,sha256:createHash('sha256').update(b).digest('hex')};}
await writeFile(path.join(out,'deployment-manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({output:out,...manifest}));
