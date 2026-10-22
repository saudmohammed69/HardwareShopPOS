// Fixture-only diagnostics. No request bodies, signatures, tokens or private keys.
import {writeFile} from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
export function settledSummary(results){
 return results.map((r,index)=>r.status==='fulfilled'?{index,settled:'fulfilled',httpStatus:r.value?.status??null}:
 {index,settled:'rejected',errorName:r.reason?.name??'Error',code:r.reason?.code??r.reason?.cause?.code??'UNKNOWN',socket:r.reason?.cause?.socket??null});
}
export async function preserveTransportEvidence(rt,label,{outcomes=[],trace=null,recovery=null}={}){
 if(!/^[a-z0-9-]+$/.test(label))throw Error('Invalid local evidence label');
 const file=path.join(rt.directory,label+'-'+randomUUID()+'.json');
 const evidence={classification:'DIAGNOSTIC_ONLY_NOT_ACCEPTANCE',label,node:process.version,platform:process.platform,
  outcomes,recovery,firstFailures:[...(rt.clientFailures??[])].sort((a,b)=>a.clientMonoMs-b.clientMonoMs),client:[...(rt.clientTraces??[])],transport:rt.fixtureTransport??null,server:trace?.server??null,sockets:trace?.sockets??null};
 // Preserve the Node side first: even failure of a final Worker request must not erase it.
 await writeFile(file,JSON.stringify(evidence),{flag:'wx'});
 if(!trace)try{trace=await rt.lifecycle({signal:AbortSignal.timeout(5000)});}
 catch(error){evidence.serverCaptureError={name:error?.name??'Error',code:error?.code??error?.cause?.code??'UNKNOWN'};}
 if(trace){evidence.client=[...trace.client];evidence.server=trace.server;evidence.sockets=trace.sockets;}
 await writeFile(file,JSON.stringify(evidence));
 return file;
}
