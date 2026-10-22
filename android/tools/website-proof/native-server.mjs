// LOCAL SYNTHETIC ANDROID BRIDGE. Kept separate from the customer server.
import http from 'node:http';
import path from 'node:path';
import {startWeb} from './web-server.mjs';
import {root} from './runtime.mjs';
import {challengeHeaders} from './customer-abuse-fixture.mjs';
// Fresh state by default: older proof schemas are not implicitly migrated.
// Supply the same explicit UUID to rehearse a server restart with this schema.
const stateId=process.argv[2]??crypto.randomUUID();
if(!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(stateId))throw Error('Synthetic state identity must be a UUID');
const app=await startWeb({port:4320,directory:path.join(root,'.local-state','native-'+stateId)});
// Miniflare's in-process D1 fixture owns one SQLite connection. Keep bridge
// requests ordered so concurrent Android retries cannot corrupt its response
// stream; backend race/CAS behavior is exercised by the dedicated Node suite.
let dispatchTail=Promise.resolve();
const dispatch=async(...args)=>{const run=dispatchTail.then(()=>app.r.mf.dispatchFetch(...args));dispatchTail=run.catch(()=>{});return run;};
const bridge=http.createServer(async(req,res)=>{
 try{
  if(req.headers.host!=='127.0.0.1:4316'||req.headers.origin){res.writeHead(403);res.end();return;}
  const u=new URL(req.url,'http://127.0.0.1:4316');
  const fixture=u.pathname==='/fixture/orders';
  if(fixture&&!['Bearer fixture-owner-a','Bearer fixture-owner-b'].includes(req.headers.authorization)){res.writeHead(403);res.end();return;}
  if(!fixture&&!/^\/(pos\/events|owner\/(storage|public-config)|catalog\/publish(?:-batch)?|orders\/[a-f0-9-]{36}\/(accept|prepare|ready|adjust|cancel|billing-claim|complete))$/.test(u.pathname)){res.writeHead(404);res.end();return;}
  let body=Buffer.alloc(0);for await(const c of req){body=Buffer.concat([body,c]);if(body.length>32768){res.writeHead(413);res.end();return;}}
  const response=await dispatch(`https://proof.invalid/shops/proof-shop${fixture?'/orders':u.pathname}${u.search}`,{method:req.method,headers:{'content-type':'application/json',authorization:req.headers.authorization??'',...(fixture?{'x-proof-client-ip':crypto.randomUUID(),'x-local-gateway-key':app.r.gatewayKey,...challengeHeaders(JSON.parse(body.toString('utf8')))}:{})},...(body.length?{body}:{})});
  const responseBytes=Buffer.from(await response.arrayBuffer());
  if(response.ok && ['/catalog/publish','/catalog/publish-batch','/owner/public-config'].includes(u.pathname))await app.refreshCatalog();
  res.writeHead(response.status,{'content-type':'application/json','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(responseBytes);
 }catch(error){console.error('Synthetic bridge request failed:',error);if(res.headersSent){res.destroy();return;}res.writeHead(503,{'content-type':'application/json'});res.end(JSON.stringify({error:'Local bridge unavailable; retry original operation'}));}
});
await new Promise(resolve=>bridge.listen(4316,'127.0.0.1',resolve));console.log('Synthetic native bridge 127.0.0.1:4316; customer website http://127.0.0.1:4320. No production credentials.');
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{await new Promise(r=>bridge.close(r));await app.close();process.exit(0);});
