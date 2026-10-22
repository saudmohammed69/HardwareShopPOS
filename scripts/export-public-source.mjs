// Copy only this standalone site's source to a fresh directory. Never traverse
// the POS project, Git history, local evidence, approval records or QR outputs.
import {readFile,mkdir,writeFile,lstat} from 'node:fs/promises';
import {createHash,randomUUID} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
if(process.argv.length!==2)throw Error('Usage: npm run export:source');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const files=[
 '.gitignore','.node-version','README.md','START-CATALOG-PREVIEW.cmd',
 '404.html','app.mjs','build.mjs','index.html','package.json','serve.mjs','styles.css',
 'release-approval.example.json',
 'public/catalog.json','public/public-config.json','public/_headers','public/robots.txt',
 'scripts/export-public-source.mjs','scripts/make-verified-qr.py','scripts/prepare-release.mjs','scripts/start-ordering-preview.mjs','scripts/verify-published-catalog.mjs',
 'tests/catalog.test.mjs'
].sort();
const catalogBytes=await readFile(path.join(root,'public/catalog.json'));
const catalog=JSON.parse(catalogBytes);
if(Object.keys(catalog).join(',')!=='products'||!Array.isArray(catalog.products)||catalog.products.length<1)throw Error('Invalid public-only catalog');
for(const p of catalog.products){
 if(Object.keys(p).sort().join(',')!=='name,sellingPricePaisa,size,unit'||Object.values(p).some(v=>typeof v!=='string')||!p.name.trim()||!p.unit.trim()||!/^[1-9]\d*$/.test(p.sellingPricePaisa))throw Error('Unexpected public product fields or price');
}
const config=JSON.parse(await readFile(path.join(root,'public/public-config.json'),'utf8'));
if(Object.keys(config).some(k=>!['mode','liveOrdering','shopName'].includes(k))||config.mode!=='CATALOG_ONLY'||config.liveOrdering!==false)throw Error('Only the disabled-ordering static source may be exported');
const example=JSON.parse(await readFile(path.join(root,'release-approval.example.json'),'utf8'));
if(Object.values(example).some(value=>value!==''&&value!==false))throw Error('The public approval example must not contain account details or approvals');
const contents=new Map(),hash=b=>createHash('sha256').update(b).digest('hex');
for(const name of files){
 // Do not follow a file/directory junction into another tree.
 let current=root;for(const part of name.split('/')){current=path.join(current,part);if((await lstat(current)).isSymbolicLink())throw Error('Linked source is not exportable: '+name);}
 if(!(await lstat(current)).isFile())throw Error('Expected a regular source file: '+name);
 const bytes=await readFile(current),text=bytes.toString('utf8');
 if(/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,}|AIza[A-Za-z0-9_-]{35}|"type"\s*:\s*"service_account"/.test(text))throw Error('Possible credential in '+name+'; inspect it before public release');
 contents.set(name,bytes);
}
const parent=path.join(root,'release-local');await mkdir(parent,{recursive:true});
const out=path.join(parent,'public-source-'+randomUUID());await mkdir(out);
const manifest={classification:'SOURCE_CANDIDATE_NOT_TESTED_OR_PUBLISHED',createdAt:new Date().toISOString(),catalogSha256:hash(catalogBytes),productCount:catalog.products.length,gitHistoryIncluded:false,files:{}};
for(const [name,bytes]of contents){
 const target=path.join(out,name);await mkdir(path.dirname(target),{recursive:true});await writeFile(target,bytes,{flag:'wx'});
 manifest.files[name]={bytes:bytes.length,sha256:hash(bytes)};
}
await writeFile(path.join(out,'PUBLIC_SOURCE_MANIFEST.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({sourceDirectory:out,...manifest},null,2));
