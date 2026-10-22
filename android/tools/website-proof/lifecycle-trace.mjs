// Local diagnostic evidence only. Never records bodies, signatures or credentials.
const records=[];
export function requestTrace(request,env){
 if(env.LOCAL_PROOF_ONLY!=='true'||env.PROOF_TRACE!=='true')return ()=>{};
 const operation=request.headers.get('x-operation-id')??request.headers.get('x-proof-trace-id');
 if(!operation||!/^[a-f0-9-]{36}$/.test(operation))return ()=>{};
 const attempt=request.headers.get('x-proof-attempt-id');
 // Workers uses an epoch origin and I/O-sensitive timers. This is not a Node
 // monotonic clock or a measurement of provider CPU; keep the wall proof clock
 // explicit and never subtract server timestamps from client monotonic time.
 return (stage,detail={})=>{if(records.length>=250000)throw Error('Local trace buffer full; drain evidence');records.push({operation,...(/^[a-f0-9-]{36}$/.test(attempt??'')?{attempt}:{}),stage,serverRuntimeMs:performance.now(),serverClock:'WORKER_RUNTIME_EPOCH',serverWallMs:Date.now(),...detail});};
}
export function drainTraces(){return records.splice(0);}
