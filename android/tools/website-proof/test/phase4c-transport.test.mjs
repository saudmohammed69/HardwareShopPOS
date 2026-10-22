import {test} from 'node:test';
import assert from 'node:assert/strict';
import {signedFixture} from '../security-fixture.mjs';
import {call} from '../runtime.mjs';
import {enablePooledFixture} from '../pooled-fixture.mjs';
test('bounded local keep-alive preserves native workerd signatures, mutation replay, revocation and 100 concurrent reads',async()=>{
 const r=await signedFixture(),pool=r.fixtureTransport?null:await enablePooledFixture(r.mf),stats=r.fixtureTransport??pool.stats;
 try{
  const reads=await Promise.all(Array.from({length:100},()=>r.send('pos/events?cursor=0',{method:'GET'})));
  for(const response of reads){assert.equal(response.status,200);assert.equal(response.headers.get('x-proof-db-rows-written'),'0');}
  const p=r.prepare('owner/public-config',{uid:'synthetic-owner',device:r.owner,body:{name:'Pooled synthetic',phone:'',address:'',openMinute:0,closeMinute:1440,maintenance:false}});
  assert.equal((await call(r,p.route,p.options)).status,200);assert.equal((await call(r,p.route,p.options)).status,403);
  const changed=r.prepare('pos/events?cursor=0',{method:'GET'});assert.equal((await call(r,'pos/events?cursor=1',changed.options)).status,403);
  assert.equal((await r.send('auth/revoke-device',{uid:'synthetic-owner',device:r.owner,body:{deviceId:r.worker.deviceId}})).status,200);
  assert.equal((await r.send('pos/events?cursor=0',{method:'GET'})).status,403);
  assert.ok(stats.requests>=105);assert.ok(stats.peakConnected>=2&&stats.peakConnected<=100);
 }finally{await r.close();await pool?.close();}
});
