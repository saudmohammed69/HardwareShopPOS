import fs from 'node:fs/promises';
import {signedFixture} from './security-fixture.mjs';
import {runtime,seed,call} from './runtime.mjs';
const signed=await signedFixture(),fixture=await runtime();await seed(fixture,30);
const size=async r=>Number((await r.db.prepare('SELECT id FROM catalog LIMIT 1').all()).meta.size_after);
const summary=r=>({requests:r.metrics.requests,rowsRead:r.metrics.read,rowsWritten:r.metrics.written,allocatedBytes:r.metrics.size,wallP50Ms:[...r.metrics.latencies].sort((a,b)=>a-b)[49],wallP95Ms:[...r.metrics.latencies].sort((a,b)=>a-b)[94]});
try {
 const before={signed:await size(signed),fixture:await size(fixture)};
 for(let i=0;i<100;i++){
  const a=await signed.send('pos/events?cursor=0',{method:'GET'}),b=await call(fixture,'pos/events?cursor=0',{method:'GET',auth:'fixture-worker-a'});
  if(a.status!==200||b.status!==200)throw Error('Local security overhead request failed');
 }
 const measured={signed:summary(signed),fixture:summary(fixture)},after={signed:await size(signed),fixture:await size(fixture)};
 const nonces=(await signed.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n;
 // Test-only expiry simulation, followed by the normal signed request cleanup.
 await signed.db.prepare('UPDATE auth_nonces SET expires_at=1').run();
 if((await signed.send('pos/events?cursor=0',{method:'GET'})).status!==200)throw Error('Nonce recovery failed');
 const remaining=(await signed.db.prepare('SELECT COUNT(*) n FROM auth_nonces').first()).n;
 if(nonces!==100||remaining!==1)throw Error('Nonce expiry was not bounded');
 const result={scope:'100 sequential empty incremental sync requests, native local D1; not final 1000-order capacity or provider CPU verification',before,after,measured,nonceRows:{beforeExpiry:nonces,afterExpiry:remaining},
  extraReadPerRequest:(measured.signed.rowsRead-measured.fixture.rowsRead)/100,extraWritePerRequest:(measured.signed.rowsWritten-measured.fixture.rowsWritten)/100,
  caveat:'Expired proof nonces are deleted only outside the accepted replay window. Security audit and enrollment records need a separately verified owner archive/retention design before production. Previous Phase 4A estimates exclude all security overhead.'};
 await fs.writeFile('evidence/phase4b-security-overhead.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
}finally{await signed.close();await fixture.close();}
