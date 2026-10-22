// Read-only deployment verification using Node's normal certificate-verified
// HTTPS client. No cookies, account credentials, auth tokens or insecure TLS.
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
const args=process.argv.slice(2),getArg=name=>{const i=args.indexOf(name);if(i<0||!args[i+1])throw Error('Missing '+name);return args[i+1];};
if(args.length!==6||!['--approval','--artifact','--out'].every(x=>args.includes(x)))throw Error('Use --approval file --artifact directory --out file');
if(process.env.NODE_TLS_REJECT_UNAUTHORIZED==='0')throw Error('Certificate verification must remain enabled');
const approval=JSON.parse(await readFile(getArg('--approval'),'utf8')),artifact=path.resolve(getArg('--artifact'));
const hash=b=>createHash('sha256').update(b).digest('hex'),manifestBytes=await readFile(path.join(artifact,'deployment-manifest.json')),m=JSON.parse(manifestBytes);
if(['accountApproved','projectApproved','contentApproved','deploymentApproved','freePlanVerified'].some(k=>approval[k]!==true)||!approval.deploymentId)throw Error('Approved completed deployment is required');
if(!/^[a-z0-9](?:[a-z0-9-]{0,56}[a-z0-9])?$/.test(approval.pagesProject??'')||approval.websiteUrl!==`https://${approval.pagesProject}.pages.dev/`)throw Error('Use the permanent approved Pages origin');
if(hash(manifestBytes)!==approval.artifactManifestSha256||m.mode!=='CATALOG_ONLY'||m.liveOrdering!==false||m.customerMutations!==false||m.functions!==false)throw Error('Only the exact approved static artifact is supported');
const names=['404.html','_headers','app.mjs','catalog.json','index.html','public-config.json','robots.txt','styles.css'].sort();
if(JSON.stringify(Object.keys(m.files??{}).sort())!==JSON.stringify(names))throw Error('Unexpected public files');
for(const name of names){const b=await readFile(path.join(artifact,name));if(b.length!==m.files[name].bytes||hash(b)!==m.files[name].sha256)throw Error('Local artifact changed: '+name);}
if(m.approvedCatalogSha256!==m.files['catalog.json'].sha256||approval.catalogSha256!==m.approvedCatalogSha256)throw Error('Catalog hash mismatch');
const origin=new URL(approval.websiteUrl).origin;
async function request(route){
 let target=new URL(route,approval.websiteUrl);
 for(let redirects=0;redirects<6;redirects++){
  if(target.origin!==origin||target.protocol!=='https:')throw Error('Unexpected verification origin');
  const r=await fetch(target,{redirect:'manual',signal:AbortSignal.timeout(15000)});
  if([301,302,303,307,308].includes(r.status)){const location=r.headers.get('location');await r.body?.cancel();if(!location)throw Error('Redirect has no destination');target=new URL(location,target);continue;}
  const parts=[];let size=0;for await(const chunk of r.body??[]){size+=chunk.length;if(size>2000000)throw Error('Oversized verification response');parts.push(chunk);}
  return {status:r.status,bytes:Buffer.concat(parts),headers:r.headers,finalUrl:target.href};
 }
 throw Error('Too many redirects');
}
const remoteManifest=await request('deployment-manifest.json');
if(remoteManifest.status!==200||!remoteManifest.bytes.equals(manifestBytes))throw Error('Hosted manifest differs from approved deployment');
const verifiedFiles={};
for(const name of names){
 if(name==='_headers')continue;
 const r=await request(name),expected=m.files[name];
 if(!(r.status===200||(name==='404.html'&&r.status===404))||r.bytes.length!==expected.bytes||hash(r.bytes)!==expected.sha256)throw Error('Hosted file differs: '+name+' (HTTP '+r.status+')');
 verifiedFiles[name]={status:r.status,bytes:r.bytes.length,sha256:hash(r.bytes),finalUrl:r.finalUrl};
}
const home=await request('');
if(home.status!==200||hash(home.bytes)!==m.files['index.html'].sha256||!home.bytes.includes(Buffer.from('data-mode="catalog-only"')))throw Error('Permanent homepage differs');
const policy=home.headers.get('content-security-policy')??'';
if(!policy.includes("form-action 'none'")||!policy.includes("frame-ancestors 'none'"))throw Error('Missing published security headers');
const routes=['api/orders','api/recover','api/orders/00000000-0000-4000-a000-000000000000/edit'],disabledApiStatuses={};
for(const route of routes){const r=await request(route);if(![404,405].includes(r.status))throw Error('Unexpected API or SPA route: '+route);disabledApiStatuses[route]=r.status;}
const evidence={schemaVersion:1,classification:'ACTUAL_HTTPS_STATIC_PUBLICATION',url:approval.websiteUrl,deploymentId:approval.deploymentId,verifiedAt:new Date().toISOString(),tlsCertificateVerification:true,artifactManifestSha256:hash(manifestBytes),catalogSha256:m.approvedCatalogSha256,productCount:m.productCount,homeStatus:home.status,homeSha256:hash(home.bytes),securityHeadersVerified:true,verifiedFiles,disabledApiStatuses,acceptanceSuitesExecuted:false,physicalPhoneScanVerified:false};
await writeFile(getArg('--out'),JSON.stringify(evidence,null,2)+'\n');
console.log(JSON.stringify({url:evidence.url,deploymentId:evidence.deploymentId,productCount:evidence.productCount,verifiedFiles:Object.keys(verifiedFiles).length,homeStatus:evidence.homeStatus,disabledApiStatuses},null,2));
