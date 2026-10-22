import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {writeFile,mkdir} from 'node:fs/promises';
import {performance} from 'node:perf_hooks';
import {startWeb} from './web-server.mjs';
import {call} from './runtime.mjs';
const require=createRequire(import.meta.url),{chromium}=require('C:/Users/mohammed saud/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
await mkdir('evidence/phase3a',{recursive:true});
const app=await startWeb({port:4318}),browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'}),results=[],consoleErrors=[],external=[];
let latestPage;
async function run(name,fn){if(process.env.BROWSER_CASE&&!name.startsWith(process.env.BROWSER_CASE))return;const start=performance.now();try{const extra=await fn();results.push({name,pass:true,ms:performance.now()-start,...extra});console.log('PASS',name);}catch(e){await latestPage?.screenshot({path:'evidence/phase3a/browser-failure.png'});if(latestPage)console.log('Failure DOM',await latestPage.evaluate(()=>({html:document.querySelector('#panel').outerHTML,rect:document.querySelector('#submit-order')?.getBoundingClientRect().toJSON(),scroll:document.querySelector('#panel').scrollTop,inner:innerHeight,active:document.activeElement.outerHTML})));results.push({name,pass:false,ms:performance.now()-start,error:e.stack});console.error('FAIL',name,e);throw e;}}
async function context(size={width:390,height:844}){const ctx=await browser.newContext({viewport:size,deviceScaleFactor:1,isMobile:size.width<600,hasTouch:size.width<600});const page=await ctx.newPage();latestPage=page;page.on('pageerror',e=>consoleErrors.push(e.message));page.on('request',r=>{if(!r.url().startsWith(app.origin))external.push(r.url());});return {ctx,page};}
async function loaded(page){await page.goto(app.origin);await page.getByText(/products · sizes grouped/).waitFor();}
async function selectPipe(page){await page.getByRole('searchbox').fill('PVC Pipe');await page.getByRole('button',{name:'Choose sizes for PVC Pipe',exact:true}).click();await page.getByRole('textbox',{name:'Full lengths for PVC Pipe 1/2 inch',exact:true}).fill('1');await page.getByRole('textbox',{name:'Extra feet for PVC Pipe 1/2 inch',exact:true}).fill('3.5');await page.getByRole('textbox',{name:'Full lengths for PVC Pipe 3/4 inch',exact:true}).fill('2');await page.getByRole('button',{name:'Save sizes to cart'}).click();}
async function details(page){await page.getByRole('button',{name:'Continue to details'}).click();await page.getByRole('textbox',{name:'Your name',exact:true}).fill('Browser Test Customer');await page.getByRole('textbox',{name:'Mobile number',exact:true}).fill('9876543210');}
async function clearLimits(){await app.r.db.prepare('DELETE FROM admission').run();}
try{
 await run('Responsive layouts, text-only listing, all sizes scrollable and reduced viewport keyboard simulation',async()=>{
  const data=[];for(const size of [{width:320,height:640},{width:360,height:800},{width:390,height:844},{width:412,height:915},{width:768,height:1024},{width:1024,height:768},{width:1440,height:900}]){
   const {ctx,page}=await context(size);await loaded(page);assert.equal(await page.locator('img,svg,canvas,picture,video').count(),0);
   assert.equal(await page.locator('article.product').count(),36);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await selectPipe(page);await page.screenshot({path:`evidence/phase3a/cart-${size.width}x${size.height}.png`});
   const box=await page.locator('#panel').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=size.width+1&&box.height<=size.height+1);
   await details(page);await page.setViewportSize({width:size.width,height:400});await page.getByRole('textbox',{name:'Mobile number',exact:true}).focus();
   const submit=await page.getByRole('button',{name:'Submit pickup order'}).boundingBox();assert.ok(submit.y>=0&&submit.y+submit.height<=400);
   const unnamed=await page.locator('dialog input').evaluateAll(nodes=>nodes.filter(n=>!n.labels?.length&&!n.getAttribute('aria-label')).length);assert.equal(unnamed,0);
   await page.keyboard.press('Tab');assert.ok(await page.evaluate(()=>document.activeElement.closest('dialog')!==null));
   data.push({size,panel:box,submitIn400pxHeight:submit});await ctx.close();
  }return {layouts:data};
 });
 await run('Full mobile shopping flow: multi-size pipes, ordinary item, cart editing/removal, notes, details, durable receipt and secure order edit/cancel',async()=>{
  const {ctx,page}=await context();await loaded(page);await selectPipe(page);assert.ok(await page.locator('#panel-actions').innerText().then(t=>t.includes('₹770.00')));
  await page.getByRole('button',{name:'Keep browsing'}).click();await page.getByRole('searchbox').fill('Cement');await page.getByRole('button',{name:'Choose sizes for Cement',exact:true}).click();await page.getByRole('textbox',{name:'Quantity for Cement 50 kg bag',exact:true}).fill('2');await page.getByRole('button',{name:'Save sizes to cart'}).click();
  await page.getByPlaceholder('Anything our team should know?').fill('Collect at the counter');await page.getByPlaceholder('Describe the item, size and quantity…').fill('Ask about brass washers');await page.getByRole('textbox',{name:'Item note for PVC Pipe 1/2 inch'}).fill('Keep cuts together');
  assert.ok((await page.locator('#panel-actions').innerText()).includes('₹1,550.00'));
  await page.getByRole('button',{name:'Remove Cement 50 kg bag'}).click();assert.ok((await page.locator('#panel-actions').innerText()).includes('₹770.00'));
  await page.getByRole('button',{name:'Close panel'}).click();await page.reload();await page.getByRole('button',{name:'Open cart'}).click();assert.ok((await page.locator('#panel-actions').innerText()).includes('₹770.00'));assert.equal(await page.getByPlaceholder('Anything our team should know?').inputValue(),'Collect at the counter');
  await details(page);await page.getByRole('textbox',{name:'Mobile number',exact:true}).fill('123');await page.getByRole('button',{name:'Submit pickup order'}).click();assert.ok(await page.locator('#customer-phone').evaluate(n=>!n.validity.valid));assert.equal((await app.r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,0);
  await page.getByRole('textbox',{name:'Mobile number',exact:true}).fill('9876543210');await page.getByRole('button',{name:'Submit pickup order'}).click();await page.getByText('Your order was stored successfully.',{exact:false}).waitFor();
  let row=await app.r.db.prepare('SELECT * FROM orders').first();const doc=JSON.parse(row.doc);assert.equal(doc.estimatedSubtotalPaisa,'77000');assert.equal(doc.items.length,2);assert.equal(doc.items[0].note,'Keep cuts together');assert.equal(doc.missingRequest,'Ask about brass washers');
  assert.equal(await page.evaluate(()=>localStorage.getItem('hwpos-remember-contact')),null);assert.ok(!page.url().includes('capability'));
  assert.ok(!await page.evaluate(()=>localStorage.getItem('pending-proof-order').includes('9876543210')),'Opt-out contact must not be saved in receipt');
  await page.getByRole('button',{name:'Edit order',exact:true}).click();await page.getByRole('button',{name:'Edit',exact:true}).first().click();await page.getByRole('textbox',{name:'Full lengths for PVC Pipe 1/2 inch',exact:true}).fill('2');await page.getByRole('button',{name:'Save sizes to cart'}).click();await page.getByRole('button',{name:'Review changes'}).click();await page.getByRole('button',{name:'Send changes'}).click();await page.getByText('Your order was stored successfully.',{exact:false}).waitFor();
  row=await app.r.db.prepare('SELECT * FROM orders').first();assert.equal(JSON.parse(row.doc).estimatedSubtotalPaisa,'95000');assert.equal(row.revision,2);
  await page.getByRole('button',{name:'Cancel order',exact:true}).click();await page.getByRole('button',{name:'Yes, cancel order'}).click();await page.getByText('This order has been cancelled.').waitFor();assert.equal((await app.r.db.prepare('SELECT * FROM orders').first()).status,'CANCELLED');
  await page.screenshot({path:'evidence/phase3a/confirmation-mobile.png'});await page.getByRole('button',{name:'Forget order and saved contact on this device'}).click();assert.equal(await page.evaluate(()=>localStorage.getItem('pending-proof-order')),null);await ctx.close();
 });
 await run('Order edit context survives reload, preserves original order identity and opt-in contact is explicitly removable',async()=>{
  await clearLimits();const {ctx,page}=await context();await loaded(page);await selectPipe(page);await details(page);await page.getByRole('checkbox',{name:'Remember my name and number on this device'}).check();await page.getByRole('button',{name:'Submit pickup order'}).click();await page.getByRole('button',{name:'Edit order',exact:true}).waitFor();const initial=await page.evaluate(()=>JSON.parse(localStorage.getItem('pending-proof-order')).receipt.id);
  assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('hwpos-remember-contact')).phone),'9876543210');
  await page.getByRole('button',{name:'Edit order',exact:true}).click();await page.getByRole('button',{name:'Close panel'}).click();await page.reload();await page.getByRole('button',{name:'Open cart'}).click();await page.getByRole('button',{name:'Review changes'}).click();await page.getByRole('textbox',{name:'Your name',exact:true}).fill('Changed pickup contact');await page.getByRole('button',{name:'Send changes'}).click();await page.getByRole('button',{name:'Edit order',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('pending-proof-order')).receipt.id),initial);assert.equal((await app.r.db.prepare('SELECT revision FROM orders WHERE id=?').bind(initial).first()).revision,2);
  await page.getByRole('button',{name:'Forget order and saved contact on this device'}).click();assert.equal(await page.evaluate(()=>localStorage.getItem('hwpos-remember-contact')),null);assert.equal(await page.evaluate(()=>localStorage.getItem('hwpos-edit-context')),null);await ctx.close();
 });
 await run('Lost response and browser restart recover one committed order with no duplicate or second stock effect',async()=>{
  await clearLimits();const before=(await app.r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n;const {ctx,page}=await context();await loaded(page);await selectPipe(page);await details(page);
  await page.route('**/api/orders',async route=>{await route.fetch();await route.abort('connectionreset');});await page.getByRole('button',{name:'Submit pickup order'}).click();await page.getByText('CONFIRMATION NOT RECEIVED',{exact:true}).waitFor();assert.equal((await app.r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,before+1);
  await page.unroute('**/api/orders');await page.reload();await page.getByRole('button',{name:'Recover / retry original order',exact:true}).click();await page.getByText('Your order was stored successfully.',{exact:false}).waitFor();assert.equal((await app.r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,before+1);
  const state=await page.evaluate(()=>JSON.parse(localStorage.getItem('pending-proof-order')));await call(app.r,`orders/${state.receipt.id}/accept`,{auth:'fixture-worker-a',body:{expectedRevision:1}});await page.getByRole('button',{name:'Refresh status'}).click();await page.getByText('The shop has started handling this order.',{exact:false}).waitFor();assert.equal(await page.getByRole('button',{name:'Edit order',exact:true}).count(),0);await ctx.close();
 });
 await run('Persistence failure shows no false order confirmation, preserves cart and permits checked revision before editing',async()=>{
  await clearLimits();const {ctx,page}=await context();await loaded(page);await selectPipe(page);await details(page);const before=(await app.r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n;
  await page.route('**/api/orders',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({error:'Synthetic quota exhausted; keep your cart',retryable:true})}));await page.getByRole('button',{name:'Submit pickup order'}).click();await page.getByText('CONFIRMATION NOT RECEIVED',{exact:true}).waitFor();assert.equal(await page.getByText('Your order was stored successfully.',{exact:false}).count(),0);assert.equal((await app.r.db.prepare('SELECT COUNT(*) n FROM orders').first()).n,before);
  await page.getByRole('button',{name:'Check before editing cart'}).click();await page.getByRole('button',{name:'Continue to details'}).waitFor();assert.ok((await page.locator('#panel-actions').innerText()).includes('₹770.00'));await ctx.close();
 });
 await run('Malformed HTTP 200 receipts keep the unresolved cart and never show successful confirmation',async()=>{
  const {ctx,page}=await context();await loaded(page);await selectPipe(page);await details(page);
  await page.route('**/api/orders',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({confirmed:true,error:'Incomplete receipt fixture'})}));
  await page.getByRole('button',{name:'Submit pickup order'}).click();await page.getByText('CONFIRMATION NOT RECEIVED',{exact:true}).waitFor();assert.equal(await page.getByText('Your order was stored successfully.',{exact:false}).count(),0);assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('pending-proof-order')).phase),'unknown');assert.ok(await page.evaluate(()=>JSON.parse(localStorage.getItem('pending-proof-order')).cart));await ctx.close();
 });
 await run('Maintenance/closed browsing, strict same-origin surface and no owner API on customer server',async()=>{
  const {ctx,page}=await context();await page.route('**/api/public-config',async route=>{const r=await route.fetch(),data=await r.json();await route.fulfill({json:{...data,orderingOpen:false,maintenance:true}});});await loaded(page);await page.getByText('Ordering paused · Browse the catalog').waitFor();await selectPipe(page);await details(page);assert.equal(await page.getByRole('button',{name:'Submit pickup order'}).isDisabled(),true);
  assert.equal((await fetch(app.origin+'/api/owner/storage')).status,404);assert.equal((await fetch(app.origin+'/__local_fixture_sql',{method:'POST'})).status,404);assert.equal((await fetch(app.origin+'/api/orders',{method:'POST',headers:{origin:'https://evil.invalid'}})).status,403);await ctx.close();
 });
 await run('Many-size selector scrolls to final size while keeping footer accessible; owner publication rebuild excludes hidden variants',async()=>{
  const {ctx,page}=await context({width:360,height:640});await loaded(page);await page.getByRole('searchbox').fill('Hex Bolt');await page.getByRole('button',{name:'Choose sizes for Hex Bolt',exact:true}).click();
  const last=page.getByRole('textbox',{name:'Quantity for Hex Bolt M39 × 40 mm',exact:true});await last.fill('2');await last.scrollIntoViewIfNeeded();assert.equal(await page.locator('dialog .variant').count(),36);
  const footer=await page.getByRole('button',{name:'Save sizes to cart'}).boundingBox();assert.ok(footer.y+footer.height<=640);await page.screenshot({path:'evidence/phase3a/many-sizes-mobile.png'});await page.getByRole('button',{name:'Save sizes to cart'}).click();assert.ok((await page.locator('#panel-actions').innerText()).includes('₹94.00'));
  const item=app.snapshot.plain.toString();const source=JSON.parse(item).products.find(p=>p.id==='demo-cement');await call(app.r,'catalog/publish',{auth:'fixture-owner',body:{product:source,published:false}});await app.refreshCatalog();assert.ok(!JSON.parse(app.snapshot.plain).products.some(p=>p.id==='demo-cement'));await ctx.close();
 });
 await run('Slow mobile-data catalog/search budget, ETag, bounded DOM, no remote dependencies or browser errors',async()=>{
  const {ctx,page}=await context({width:360,height:800});const cdp=await ctx.newCDPSession(page);await cdp.send('Network.enable');await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:150,downloadThroughput:150000/8,uploadThroughput:75000/8});await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});const begin=performance.now();await loaded(page);const loadMs=performance.now()-begin;
  // The sample elbow also has the Kannada alias and a 3/4-inch size. Scope the
  // measured search to the 800 known synthetic families rather than assuming
  // the added sample catalog cannot also match.
  await page.getByRole('button',{name:'Synthetic hardware',exact:true}).click();
  const start=performance.now();await page.getByRole('searchbox').fill('ಸೊಕೆಟ್ 4 inch');await page.getByText('800 products · sizes grouped together',{exact:true}).waitFor();const searchMs=performance.now()-start;
  assert.ok(app.snapshot.gzip.length<60000);assert.ok(searchMs<2000);assert.ok(loadMs<15000);assert.equal(await page.locator('.product').count(),36);
  const conditional=await fetch(app.origin+'/catalog.json',{headers:{'if-none-match':app.snapshot.etag}});assert.equal(conditional.status,304);
  await page.screenshot({path:'evidence/phase3a/catalog-mobile.png',fullPage:false});await ctx.close();assert.deepEqual(consoleErrors,[]);assert.deepEqual(external,[]);return {catalogVariants:app.snapshot.count,catalogGzipBytes:app.snapshot.gzip.length,slowMobileLoadMs:loadMs,throttledSearchMs:searchMs,browser:'Installed Chrome, headless; emulated network and CPU, not physical mobile data'};
 });
}finally{
 await writeFile('evidence/phase3a-browser-results.json',JSON.stringify({executedAt:new Date().toISOString(),results,consoleErrors,external},null,2));await browser.close();await app.close();
}
