// Local client proof. Retry only a read or a mutation backed by durable atomic
// request identity/receipt. Fresh nonce/signature each retry, same business body.
import {preparePaired} from './pairing-fixture.mjs';
import {call} from './runtime.mjs';
export function recoveryClient(rt){
 const metrics={transportFailures:0,recoveryAttempts:0,unrecoverable:0,transportEvents:[]};
 async function send(device,route,options={}){
  const stable={...options,body:options.body===undefined?undefined:JSON.parse(JSON.stringify(options.body)),operationId:options.operationId??crypto.randomUUID()};
  const recoverable=stable.method==='GET'||/^orders\/[a-f0-9-]{36}\//.test(route)||['catalog/publish','catalog/publish-batch','owner/public-config'].includes(route);
  for(let attempt=0;;attempt++)try{
   const beforeTransmit=()=>{const p=preparePaired(device,route,stable);rt.clientTraces?.push({operation:stable.operationId,stage:'signed',clientMonoMs:performance.now(),signatureWallSeconds:Number(p.options.headers['x-device-time'])});return {headers:p.options.headers,body:p.options.raw};};
   if(rt.fixtureTransport?.kind==='LOCAL_WORKERD_KEEP_ALIVE')return await call(rt,route,{method:stable.method??'POST',headers:{'x-proof-trace-id':stable.operationId,...(stable.body===undefined?{}:{'content-type':'application/json'})},beforeTransmit});
   const p=preparePaired(device,route,stable);return await call(rt,p.route,p.options);
  }
  catch(e){metrics.transportFailures++;const code=e.code??e.cause?.code;
   metrics.transportEvents.push({route,method:stable.method??'POST',operationId:stable.operationId,attempt:attempt+1,code:code??'UNKNOWN'});
   if(!recoverable||!['ECONNRESET','UND_ERR_SOCKET','EPIPE'].includes(code)||attempt>=2){metrics.unrecoverable++;throw e;}
   metrics.recoveryAttempts++;
  }
 }
 return {send,metrics};
}
