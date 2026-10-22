// Creates a reviewed, separately copied upload folder and a command file. It never
// logs in, invokes Wrangler, changes an account or deploys anything.
import {readFile,readdir,mkdir,writeFile} from 'node:fs/promises';
import {createHash,randomUUID} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
if(process.argv.length!==4||process.argv[2]!=='--approval')throw Error('Usage: npm run prepare:release -- --approval <private approval.json>');
const approvalPath=path.resolve(process.argv[3]);
const approval=JSON.parse(await readFile(approvalPath,'utf8'));
const keys=['accountApproved','projectApproved','contentApproved','deploymentApproved','freePlanVerified'];
if(keys.some(k=>approval[k]!==true))throw Error('Account/project/content/deployment and static Free-hosting approval are required. No upload command was created.');
if(!/^[a-f0-9]{32}$/i.test(approval.accountId??''))throw Error('Record the exact approved Cloudflare account ID');
if(!/^[a-z0-9](?:[a-z0-9-]{0,56}[a-z0-9])?$/.test(approval.pagesProject??''))throw Error('Record the exact approved Pages project');
if(approval.websiteUrl!==`https://${approval.pagesProject}.pages.dev/`)throw Error('Only the permanent approved Pages project URL is supported');
if(!Number.isFinite(Date.parse(approval.approvedAt??'')))throw Error('Record the owner approval timestamp');
const hash=b=>createHash('sha256').update(b).digest('hex');
const artifact=path.join(root,'dist'),manifestBytes=await readFile(path.join(artifact,'deployment-manifest.json'));
if(hash(manifestBytes)!==approval.artifactManifestSha256)throw Error('Artifact manifest differs from the reviewed hash');
const m=JSON.parse(manifestBytes);
if(m.schemaVersion!==1||m.mode!=='CATALOG_ONLY'||m.liveOrdering!==false||m.customerMutations!==false||m.functions!==false||!Number.isInteger(m.productCount)||m.productCount<1)throw Error('Only a nonempty approved catalog-only artifact may be prepared');
// The owner explicitly deferred testing and then approved this static catalog
// publication. Record that honestly; never convert deferred checks into passes.
// This exception cannot authorize any order API, Functions or live checkout.
if(approval.localAcceptancePassed!==true&&!(approval.localTestsDeferredByOwner===true&&approval.testDeferralScope==='STATIC_CATALOG_ONLY'))throw Error('Local acceptance or explicit owner deferral for this static-only release is required');
const assets=['index.html','404.html','app.mjs','styles.css','catalog.json','public-config.json','_headers','robots.txt'].sort();
if(JSON.stringify(Object.keys(m.files??{}).sort())!==JSON.stringify(assets))throw Error('Unexpected artifact manifest entries');
const entries=await readdir(artifact,{withFileTypes:true});
if(entries.some(e=>!e.isFile())||JSON.stringify(entries.map(e=>e.name).sort())!==JSON.stringify([...assets,'deployment-manifest.json'].sort()))throw Error('Build folder contains unreviewed files');
// Read and verify every byte before producing a fresh snapshot. Wrangler will
// receive this snapshot, never the mutable working dist directory.
const bytes=new Map();for(const name of assets){
 const value=await readFile(path.join(artifact,name)),record=m.files[name];
 if(record.bytes!==value.length||record.sha256!==hash(value))throw Error('Changed artifact: '+name);
 bytes.set(name,value);
}
if(m.approvedCatalogSha256!==hash(bytes.get('catalog.json'))||approval.catalogSha256!==m.approvedCatalogSha256)throw Error('Catalog approval does not match the artifact');
const catalog=JSON.parse(bytes.get('catalog.json'));
if(Object.keys(catalog).join(',')!=='products'||!Array.isArray(catalog.products)||catalog.products.length!==m.productCount)throw Error('Unexpected public catalog');
for(const row of catalog.products){
 if(Object.keys(row).sort().join(',')!=='name,sellingPricePaisa,size,unit'||Object.values(row).some(x=>typeof x!=='string')||!row.name.trim()||!row.unit.trim()||!/^[1-9]\d*$/.test(row.sellingPricePaisa))throw Error('Public catalog contains unsafe or unverified fields');
}
const config=JSON.parse(bytes.get('public-config.json'));
if(Object.keys(config).some(k=>!['mode','liveOrdering','shopName'].includes(k))||config.mode!=='CATALOG_ONLY'||config.liveOrdering!==false)throw Error('Customer ordering must remain disabled');
const parent=path.join(root,'release-local');await mkdir(parent,{recursive:true});
const snapshot=path.join(parent,`catalog-${hash(manifestBytes).slice(0,16)}-${randomUUID()}`);await mkdir(snapshot);
const upload=path.join(snapshot,'upload');await mkdir(upload);
for(const [name,value]of bytes)await writeFile(path.join(upload,name),value,{flag:'wx'});
await writeFile(path.join(upload,'deployment-manifest.json'),manifestBytes,{flag:'wx'});
const record={schemaVersion:1,state:'PREPARED_NOT_DEPLOYED',accountId:approval.accountId,pagesProject:approval.pagesProject,websiteUrl:approval.websiteUrl,artifactManifestSha256:hash(manifestBytes),catalogSha256:m.approvedCatalogSha256,productCount:m.productCount,localAcceptance:approval.localAcceptancePassed===true?'PASSED':'DEFERRED_BY_OWNER_STATIC_ONLY',preparedAt:new Date().toISOString(),uploadDirectory:upload};
await writeFile(path.join(snapshot,'release-record.json'),JSON.stringify(record,null,2)+'\n',{flag:'wx'});
const quote=s=>"'"+s.replaceAll("'","''")+"'";
// Explicit owner-run action only, using an already installed Wrangler binary.
// No npx package install, account/project creation, Functions or paid fallback.
const expectedHashes=Object.fromEntries([...assets.map(name=>[name,m.files[name].sha256]),['deployment-manifest.json',hash(manifestBytes)]]);
const command=[
 '# Run only after reviewing this exact snapshot and confirming the existing Free account/project.',
 "$ErrorActionPreference = 'Stop'",
 ` $taskUploadPath = ${quote(upload)}`.trimStart(),
 ` $taskExpected = ${quote(JSON.stringify(expectedHashes))} | ConvertFrom-Json`.trimStart(),
 'if (-not (Test-Path -LiteralPath $taskUploadPath -PathType Container)) { throw \'Reviewed upload folder is missing\' }',
 '$taskEntries = @(Get-ChildItem -LiteralPath $taskUploadPath -Force)',
 '$taskExpectedNames = @($taskExpected.PSObject.Properties.Name | Sort-Object)',
 'if (@($taskEntries | Where-Object { $_.PSIsContainer }).Count -gt 0 -or $taskEntries.Count -ne $taskExpectedNames.Count -or @(Compare-Object $taskExpectedNames @($taskEntries.Name | Sort-Object)).Count -gt 0) { throw \'Upload file list has changed; rebuild and review again\' }',
 'foreach ($taskEntry in $taskExpected.PSObject.Properties) {',
 "  $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskUploadPath $taskEntry.Name) -Algorithm SHA256).Hash.ToLowerInvariant()",
 "  if ($taskHash -ne $taskEntry.Value) { throw ('Reviewed upload bytes changed: ' + $taskEntry.Name) }",
 '}',
 'Get-Command wrangler -ErrorAction Stop | Out-Null',
 '$taskPreviousCloudflareAccount = $env:CLOUDFLARE_ACCOUNT_ID',
 'try {',
 `  $env:CLOUDFLARE_ACCOUNT_ID = ${quote(approval.accountId)}`,
 `  & wrangler pages deploy ${quote(upload)} --project-name ${quote(approval.pagesProject)} --branch main`,
 "  if ($LASTEXITCODE -ne 0) { throw 'Deployment failed; do not claim publication or generate a QR' }",
 '} finally { $env:CLOUDFLARE_ACCOUNT_ID = $taskPreviousCloudflareAccount }',
 '# Record the actual deployment ID; verify hosted HTTPS and use make-verified-qr.py next.',
 ''
].join('\r\n');
await writeFile(path.join(snapshot,'DEPLOY-APPROVED-CATALOG.ps1'),command,{flag:'wx'});
console.log(JSON.stringify(record,null,2));
