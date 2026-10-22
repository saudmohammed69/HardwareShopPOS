import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {serve} from './serve.mjs';
const here=path.dirname(fileURLToPath(import.meta.url)),root=path.resolve(here,'..');
const runId=`${new Date().toISOString().replace(/[-:.TZ]/g,'')}-${process.pid}`;
const evidence=path.join(root,'tools/website-proof/evidence/final-engineering','publication-runs',runId);
const fixtures=path.join(evidence,'fixtures');
const outputRoot=path.join(here,'build','publication-tests',runId);
const maintenanceOut=path.join(outputRoot,'maintenance');
const catalogOut=path.join(outputRoot,'catalog');
await mkdir(fixtures,{recursive:true});
await mkdir(outputRoot,{recursive:true});
const base=JSON.parse(await readFile(path.join(here,'publication.config.json'),'utf8'));
const configPath=path.join(fixtures,'config.json'),catalogPath=path.join(fixtures,'catalog.json'),approvalPath=path.join(fixtures,'approval.json');
const config={...base,mode:'CATALOG_ONLY',shopName:'Synthetic test catalog',message:'Synthetic browser fixtures only. Ordering unavailable.'};
const ordinary={id:'ordinary',name:'Fixture Cement',category:'Building',type:'General',size:'50 kg',unit:'Bag',aliases:'cement',sellingPricePaisa:'39000',gstRateBp:1800,isPipe:false};
const pipes=Array.from({length:40},(_,i)=>({id:'pipe-'+i,name:'Fixture PVC Pipe',category:'Plumbing',type:'Pressure',size:`${i+1} inch`,unit:'Length',sellingPricePaisa:'18000',gstRateBp:1800,isPipe:true,feetPerLength:20,lengthPricePaisa:'18000',footPricePaisa:'2000',aliases:'pipe'}));
const raw=Buffer.from(JSON.stringify({products:[ordinary,...pipes]})),sha=createHash('sha256').update(raw).digest('hex');
await writeFile(configPath,JSON.stringify(config));await writeFile(catalogPath,raw);await writeFile(approvalPath,JSON.stringify({publicCatalogApproved:true,catalogSha256:sha,approvedAt:new Date().toISOString(),syntheticFixtureOnly:true}));
function build(extra){return execFileSync(process.execPath,[path.join(here,'build.mjs'),...extra],{cwd:root,encoding:'utf8',stdio:'pipe'});}
const args=['--config='+configPath,'--catalog='+catalogPath,'--approval='+approvalPath];
test('maintenance package is static, empty, hashed and excludes APIs/credentials/ordering client',async()=>{
 build(['--out='+path.relative(root,maintenanceOut)]);const output=maintenanceOut,m=JSON.parse(await readFile(path.join(output,'deployment-manifest.json'),'utf8'));
 assert.equal(m.mode,'MAINTENANCE');assert.equal(m.productCount,0);assert.equal(m.liveOrdering,false);assert.equal(m.functions,false);
 assert.ok(!('app.mjs' in m.files)&&!('submission-client.mjs' in m.files)&&!('_worker.js' in m.files));
 for(const [name,row]of Object.entries(m.files)){const bytes=await readFile(path.join(output,name));assert.equal(createHash('sha256').update(bytes).digest('hex'),row.sha256);}
 assert.deepEqual(JSON.parse(await readFile(path.join(output,'catalog.json'),'utf8')),{products:[]});
});
test('approved content is projected; absent approval/private fields/hash tampering/live mode are rejected',async()=>{
 build([...args,'--out='+path.relative(root,catalogOut)]);
 assert.throws(()=>build(['--config='+configPath,'--catalog='+catalogPath,'--out=website-publication/rejected-no-approval']));
 const bad=path.join(fixtures,'private.json');await writeFile(bad,JSON.stringify({products:[{...ordinary,currentStock:100,purchasePricePaisa:'1'}]}));
 const a=path.join(fixtures,'private-approval.json');await writeFile(a,JSON.stringify({publicCatalogApproved:true,catalogSha256:createHash('sha256').update(await readFile(bad)).digest('hex'),approvedAt:new Date().toISOString()}));
 assert.throws(()=>build(['--config='+configPath,'--catalog='+bad,'--approval='+a,'--out=website-publication/rejected-private']));
 assert.throws(()=>build(['--config='+configPath,'--catalog='+bad,'--approval='+approvalPath,'--out=website-publication/rejected-hash']));
 const live=path.join(fixtures,'live.json');await writeFile(live,JSON.stringify({...base,liveOrdering:true}));
 assert.throws(()=>build(['--config='+live,'--out=website-publication/rejected-live']));
});
test('maintenance/mobile/read-only variants/CSP/unknown orders/local receipt storage fail closed',async()=>{
 const require=createRequire(import.meta.url),playwrightPath=process.env.PLAYWRIGHT_MODULE_PATH??'playwright',{chromium}=require(playwrightPath);
 const browserPath=process.env.CHROME_EXECUTABLE_PATH;
 const browser=await chromium.launch({headless:true,...(browserPath?{executablePath:browserPath}:{})});
 let server;const requests=[],errors=[],layouts=[];
 try{
  server=await serve(maintenanceOut);let page=await browser.newPage({viewport:{width:360,height:800}});
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>requests.push({url:r.url(),method:r.method()}));
  const response=await page.goto(server.origin);await page.getByText('0 products · sizes grouped together').waitFor();
  assert.ok(response.headers()['content-security-policy'].includes("form-action 'none'"));
  assert.equal(await page.locator('form,img,svg,canvas,video,picture').count(),0);
  assert.equal((await fetch(server.origin+'/api/orders',{method:'POST',body:'{}'})).status,405);
  assert.equal((await fetch(server.origin+'/api/orders/unknown/edit')).status,404);
  await page.screenshot({path:path.join(evidence,'publication-maintenance-mobile.png')});await page.close();await server.close();
  server=await serve(catalogOut);
  for(const width of [320,360,390,412,768,1024,1440]){
   const size={width,height:width===1024?768:800};page=await browser.newPage({viewport:size});
   page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>requests.push({url:r.url(),method:r.method()}));
   await page.addInitScript(()=>localStorage.setItem('pending-proof-order',JSON.stringify({phase:'sending',receipt:{id:'not-a-real-order'}})));
   await page.goto(server.origin);await page.getByText('2 products · sizes grouped together').waitFor();
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   assert.equal(await page.locator('img,form,[name="phone"],#cart-top,#submit-order,#last-order').count(),0);
   await page.getByRole('searchbox').fill('PVC');await page.getByText('1 products · sizes grouped together').waitFor();
   await page.getByRole('button',{name:'View sizes for Fixture PVC Pipe',exact:true}).click();
   assert.equal(await page.locator('dialog .variant').count(),40);await page.locator('dialog .variant').last().scrollIntoViewIfNeeded();
   const panel=await page.locator('dialog').boundingBox(),done=await page.getByRole('button',{name:'Done',exact:true}).boundingBox();
   assert.ok(panel.x>=0&&panel.x+panel.width<=width+1);assert.ok(done.y>=0&&done.y+done.height<=size.height+1);
   assert.ok((await page.locator('dialog').innerText()).includes('₹20.00 / extra ft'));
   if(width===360)await page.screenshot({path:path.join(evidence,'publication-catalog-sizes-mobile.png')});
   await page.getByRole('button',{name:'Done',exact:true}).click();
   await page.route('**/public-config.json',route=>route.fulfill({json:{...config,liveOrdering:true}}));
   await page.getByRole('button',{name:'Refresh catalog'}).click();await page.getByText('The catalog is unavailable. Please ask our team at the counter.').waitFor();
   assert.equal(await page.locator('article.product').count(),0);layouts.push(size);await page.close();
  }
  assert.deepEqual(errors,[]);assert.ok(requests.every(r=>r.method==='GET'&&r.url.startsWith(server.origin)&&!r.url.includes('/api/')));
  await writeFile(path.join(evidence,'publication-browser.json'),JSON.stringify({status:'PASS',layouts,browser:'Chrome headless; emulated layouts, not physical iPhone/Android',errors,requests,productionHttpsVerified:false},null,2));
 }finally{await browser.close();await server?.close();}
});
