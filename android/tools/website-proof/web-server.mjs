import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {gzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {runtime,seed,root} from './runtime.mjs';
import {projectProduct} from './pricing.mjs';
import {publicSettings} from './public-settings.mjs';
import {abuseFixtureBindings,anonymousSession,challengeHeaders} from './customer-abuse-fixture.mjs';
const mime={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.mjs':'text/javascript; charset=utf-8'};
export async function startWeb({port=4317,directory,bindings={},largeCatalog=true,settings={},runtimeFactory=runtime}={}){
 if(!Number.isInteger(port)||port<1024||port>65535)throw Error('A valid loopback preview port is required');
 const origin=`http://127.0.0.1:${port}`;
 // Source POS uses shop_name/shop_phone/shop_address. No production setting is read.
 // Until an approved public settings export exists, this is an explicit local preview.
 const r=await runtimeFactory({directory,bindings:{...abuseFixtureBindings,...publicSettings(settings),LOCAL_WEB_ORIGIN:origin,...bindings}});
 let server;
 try{
 await seed(r,largeCatalog?3200:4);
 const samples=[
  ...[['1/2 inch','18000','2000'],['3/4 inch','26000','2500'],['1 inch','40000','3500'],['1.5 inch','62000','5500']].map(([size,rate,foot],i)=>({id:`demo-pvc-${i}`,name:'PVC Pipe',category:'Plumbing',type:'Pressure pipe',size,unit:'Length',isPipe:true,feetPerLength:20,sellingPricePaisa:rate,lengthPricePaisa:rate,footPricePaisa:foot,aliases:'pipe pvc water ಪೈಪ್'})),
  ...['1/2 inch','3/4 inch','1 inch'].map((size,i)=>({id:`demo-elbow-${i}`,name:'PVC Elbow',category:'Plumbing',type:'90° fitting',size,unit:'Piece',sellingPricePaisa:String(1800+i*700),aliases:'bend connector elbow ಸೊಕೆಟ್'})),
  {id:'demo-cement',name:'Cement',category:'Building',type:'General purpose',size:'50 kg bag',unit:'Bag',sellingPricePaisa:'39000',aliases:'cement ಸಿಮೆಂಟ್'},
  {id:'demo-tape',name:'Insulation Tape',category:'Electrical',type:'PVC electrical',size:'18 mm × 20 m',unit:'Roll',sellingPricePaisa:'3500',aliases:'wire tape'},
  {id:'demo-hammer',name:'Claw Hammer',category:'Tools',type:'Steel',size:'500 g',unit:'Piece',sellingPricePaisa:'29500',aliases:'hammer ಸುತ್ತಿಗೆ'},
  {id:'demo-screw',name:'Wood Screws',category:'Fasteners',type:'Countersunk',size:'1 inch',unit:'Pack',sellingPricePaisa:'8500',aliases:'screw fastener'},
  {id:'demo-brush',name:'Paint Brush',category:'Painting',type:'Flat brush',size:'2 inch',unit:'Piece',sellingPricePaisa:'6500',aliases:'paint brush'},
  ...Array.from({length:36},(_,i)=>({id:`demo-bolt-${String(i).padStart(2,'0')}`,name:'Hex Bolt',category:'Fasteners',type:'Zinc plated',size:`M${i+4} × 40 mm`,unit:'Piece',sellingPricePaisa:String(1200+i*100),aliases:'bolt fastener'}))
 ].map(p=>projectProduct({...p,gstRateBp:1800}));
 await r.db.batch(samples.map(p=>r.db.prepare("INSERT INTO catalog(shop,id,doc,published,revision) VALUES('proof-shop',?,?,1,1) ON CONFLICT(shop,id) DO NOTHING").bind(p.id,JSON.stringify(p))));
 let snapshot;
 async function refreshCatalog(){const all=[];let after='';while(true){const result=await r.db.prepare("SELECT id,doc,revision FROM catalog WHERE shop='proof-shop' AND published=1 AND id>? ORDER BY id LIMIT 500").bind(after).all();all.push(...result.results.map(x=>({...JSON.parse(x.doc),revision:x.revision})));if(result.results.length<500)break;after=result.results.at(-1).id;}
  const plain=Buffer.from(JSON.stringify({products:all,localPreview:true}));snapshot={plain,gzip:gzipSync(plain),etag:'"'+createHash('sha256').update(plain).digest('hex')+'"',count:all.length};return snapshot;}
 await refreshCatalog();
 const files={'/':'web/index.html','/app.mjs':'web/app.mjs','/styles.css':'web/styles.css','/pricing.mjs':'pricing.mjs','/web-model.mjs':'web-model.mjs','/submission-client.mjs':'submission-client.mjs'};
 server=http.createServer(async(req,res)=>{
  try{
   // Never forward internal fixture SQL, owner/admin/staff endpoints or credentials from the customer server.
   if(req.headers.host!==`127.0.0.1:${server.address().port}`){res.writeHead(403);res.end('Invalid local host');return;}
   const url=new URL(req.url,origin),route=url.pathname;
   const headers={'x-content-type-options':'nosniff','referrer-policy':'no-referrer','permissions-policy':'camera=(), microphone=(), geolocation=()',
    'content-security-policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; font-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"};
   if(route==='/catalog.json'&&req.method==='GET'){
    if(req.headers['if-none-match']===snapshot.etag){res.writeHead(304,{...headers,etag:snapshot.etag});res.end();return;}
    const zipped=/\bgzip\b/.test(req.headers['accept-encoding']??'');res.writeHead(200,{...headers,'content-type':'application/json','cache-control':'public, max-age=60',etag:snapshot.etag,vary:'Accept-Encoding',...(zipped?{'content-encoding':'gzip'}:{})});res.end(zipped?snapshot.gzip:snapshot.plain);return;
   }
   if(route.startsWith('/api/')){
    const api=route.slice(5);if(!/^(public-config|orders|recover|orders\/[a-f0-9-]{36}\/(customer-view|edit|customer-cancel))$/.test(api)){res.writeHead(404,headers);res.end();return;}
    if(req.headers.origin&&req.headers.origin!==origin){res.writeHead(403,headers);res.end();return;}
    let body=Buffer.alloc(0);for await(const chunk of req){body=Buffer.concat([body,chunk]);if(body.length>32768){res.writeHead(413,headers);res.end();return;}}
    const forwarded={'content-type':req.headers['content-type']??'application/json',origin,'x-proof-client-ip':req.socket.remoteAddress??'loopback'};
    if(r.customerAbuseMode){
     forwarded['x-local-gateway-key']=r.gatewayKey;
     if(api==='orders'&&req.method==='POST'){
      // Trusted synthetic verifier only; not a real Turnstile widget/sender.
      // Host is loopback-only and Worker independently disables production.
      let session=req.headers.cookie?.match(/(?:^|;\s*)hwpos_order_session=([-_A-Za-z0-9.]+)/)?.[1];
      if(!session){session=anonymousSession();headers['set-cookie']=`hwpos_order_session=${session}; HttpOnly; SameSite=Strict; Path=/; Max-Age=3600`;}
      let parsed;try{parsed=JSON.parse(body.toString('utf8'));}catch{}
      if(parsed?.submissionId&&parsed?.capability)Object.assign(forwarded,challengeHeaders(parsed,{session}));
     }
    }
    if(req.headers['x-order-capability'])forwarded['x-order-capability']=req.headers['x-order-capability'];
    const response=await r.mf.dispatchFetch(`https://proof.invalid/shops/proof-shop/${api}`,{method:req.method,headers:forwarded,...(body.length?{body}: {})});
    res.writeHead(response.status,{...headers,'content-type':'application/json','cache-control':'no-store'});res.end(Buffer.from(await response.arrayBuffer()));return;
   }
   if(req.method!=='GET'||!Object.hasOwn(files,route)){res.writeHead(404,headers);res.end();return;}
   res.writeHead(200,{...headers,'content-type':mime[path.extname(files[route])],'cache-control':'no-cache'});res.end(await readFile(path.join(root,files[route])));
  }catch{res.writeHead(503,{'content-type':'application/json'});res.end(JSON.stringify({error:'Local service unavailable. Keep your cart and retry.'}));}
 });
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
 let closing;
 return {r,server,origin,refreshCatalog,get snapshot(){return snapshot;},close:()=>closing??=(async()=>{await new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve()));await r.close();})()};
 }catch(error){
  if(server?.listening)await new Promise(resolve=>server.close(resolve));
  await r.close();throw error;
 }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const app=await startWeb({directory:path.join(root,'.local-state','customer-preview')});console.log(`Local synthetic preview: ${app.origin}; ${app.snapshot.count} published variants. No production connection.`);
 for(const sig of ['SIGINT','SIGTERM'])process.on(sig,async()=>{await app.close();process.exit(0);});
}
