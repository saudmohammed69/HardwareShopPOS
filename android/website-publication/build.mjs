// Static Pages package builder; never contacts an account or reads the POS DB.
import {readFile,writeFile,mkdir,copyFile,readdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {projectProduct} from '../tools/website-proof/pricing.mjs';
const here=path.dirname(fileURLToPath(import.meta.url)),root=path.resolve(here,'..');
const args=Object.fromEntries(process.argv.slice(2).map(v=>{const i=v.indexOf('=');if(i<3)throw Error('Use --name=value');return [v.slice(2,i),v.slice(i+1)];}));
if(Object.keys(args).some(k=>!['out','config','catalog','approval'].includes(k)))throw Error('Unknown build argument');
const output=path.resolve(root,args.out??'website-publication/build');
if(!output.startsWith(here+path.sep))throw Error('Output must stay inside website-publication');
const config=JSON.parse(await readFile(args.config?path.resolve(root,args.config):path.join(here,'publication.config.json'),'utf8'));
const configKeys=['schemaVersion','mode','liveOrdering','shopName','phone','address','hours','message'];
if(Object.keys(config).some(k=>!configKeys.includes(k))||configKeys.some(k=>!(k in config))||config.schemaVersion!==1||config.liveOrdering!==false||!['MAINTENANCE','CATALOG_ONLY'].includes(config.mode))throw Error('Live ordering is unsupported by this publishing artifact');
for(const k of ['shopName','phone','address','hours','message'])if(typeof config[k]!=='string'||config[k].length>500||/[\u0000-\u001f]/.test(config[k]))throw Error('Invalid public setting '+k);
if(!config.shopName.trim())throw Error('Shop name required');
let products=[],approvalHash=null;
if(args.catalog){
 if(config.mode!=='CATALOG_ONLY'||!args.approval)throw Error('Catalog publication requires explicit content approval and CATALOG_ONLY mode');
 const raw=await readFile(path.resolve(root,args.catalog));
 const approval=JSON.parse(await readFile(path.resolve(root,args.approval),'utf8'));
 approvalHash=createHash('sha256').update(raw).digest('hex');
 if(approval.publicCatalogApproved!==true||approval.catalogSha256!==approvalHash||typeof approval.approvedAt!=='string'||!Number.isFinite(Date.parse(approval.approvedAt)))throw Error('Catalog approval/hash missing or mismatched');
 const data=JSON.parse(raw);
 if(Object.keys(data).join(',')!=='products'||!Array.isArray(data.products)||data.products.length>20000)throw Error('Use a public-only products export');
 const allowed=new Set(['id','name','category','type','size','unit','aliases','sellingPricePaisa','gstRateBp','isPipe','feetPerLength','lengthPricePaisa','footPricePaisa']);
 products=data.products.map(p=>{if(Object.keys(p).some(k=>!allowed.has(k)))throw Error('Private/unknown catalog fields rejected');return projectProduct(p);});
 if(new Set(products.map(p=>p.id)).size!==products.length)throw Error('Duplicate product IDs');
}else if(config.mode!=='MAINTENANCE')throw Error('Catalog-only mode needs approved public content');
await mkdir(output,{recursive:false});
const files=['index.html','catalog.mjs'];for(const name of files)await copyFile(path.join(here,name),path.join(output,name));
for(const [dest,src]of Object.entries({'styles.css':'web/styles.css','web-model.mjs':'web-model.mjs','pricing.mjs':'pricing.mjs'}))await copyFile(path.join(root,'tools/website-proof',src),path.join(output,dest));
await writeFile(path.join(output,'public-config.json'),JSON.stringify(config));
await writeFile(path.join(output,'catalog.json'),JSON.stringify({products}));
await writeFile(path.join(output,'404.html'),'<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Not available</title><h1>This page is not available.</h1><p>Online ordering is unavailable.</p><a href="/">Back to catalog</a></html>');
await writeFile(path.join(output,'robots.txt'),'User-agent: *\nDisallow: /\n');
await writeFile(path.join(output,'_headers'),`/*
  Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
  X-Content-Type-Options: nosniff
  Referrer-Policy: no-referrer
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  X-Frame-Options: DENY
  X-Robots-Tag: noindex, nofollow
  Cache-Control: no-cache
`);
const manifest={schemaVersion:1,mode:config.mode,liveOrdering:false,customerMutations:false,functions:false,productCount:products.length,approvedCatalogSha256:approvalHash,files:{}};
const forbidden=/PRIVATE KEY-----|fixture-(owner|worker)|LOCAL_CHALLENGE_KEY|SYNTHETIC_CONTROL|PAIR_ADMIN_PRIVATE|__fixture\/|service_account|refresh_token|"(?:stock|currentStock|purchasePricePaisa|passwordHash)"/i;
for(const name of await readdir(output)){const b=await readFile(path.join(output,name));if(forbidden.test(b.toString()))throw Error('Unsafe artifact marker: '+name);manifest.files[name]={bytes:b.length,sha256:createHash('sha256').update(b).digest('hex')};}
await writeFile(path.join(output,'deployment-manifest.json'),JSON.stringify(manifest,null,2));
console.log(JSON.stringify({output,...manifest}));
