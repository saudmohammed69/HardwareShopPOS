// LOCAL client proof: retry immutable submissions; reconcile uncertain edits
// against the capability-protected stored order. No blind edit replay.
import {call} from './runtime.mjs';
import {canonical} from './pricing.mjs';
const transient=e=>['ECONNRESET','UND_ERR_SOCKET','EPIPE'].includes(e.code??e.cause?.code);
function failure(metrics,e,route,identity,attempt){
 metrics.transportFailures++;(metrics.transportEvents??=[]).push({route,method:'POST',operationId:identity,attempt:attempt+1,code:e.code??e.cause?.code??'UNKNOWN'});
 if(!transient(e)||attempt>=2){metrics.unrecoverable++;throw e;}metrics.recoveryAttempts++;
}
export async function submitRecoverably(rt,body,options={},metrics){
 const frozen=JSON.parse(JSON.stringify(body));
 for(let attempt=0;;attempt++)try{return await call(rt,'orders',{...options,body:frozen});}
 catch(e){failure(metrics,e,'orders',frozen.submissionId,attempt);}
}
function selection(order){
 const clean=x=>(x??'').trim(),value=x=>x==null?null:String(x);
 return {customer:{name:clean(order.customer.name),phone:clean(order.customer.phone)},note:clean(order.note),missingRequest:clean(order.missingRequest),pickupAt:order.pickupAt??null,
  items:order.items.map(i=>({productId:i.productId,quantity:value(i.quantity),fullLengths:value(i.fullLengths),extraFeet:value(i.extraFeet),note:clean(i.note)}))};
}
export async function editRecoverably(rt,id,expectedRevision,order,capability,metrics){
 const frozen=JSON.parse(JSON.stringify(order)),route=`orders/${id}/edit`;
 for(let attempt=0;;attempt++)try{return await call(rt,route,{body:{expectedRevision,order:frozen},capability});}
 catch(e){
  failure(metrics,e,route,`edit:${id}:${expectedRevision}`,attempt);
  let view;
  for(let readAttempt=0;;readAttempt++)try{view=await call(rt,`orders/${id}/customer-view`,{body:{},capability});break;}
  catch(readError){failure(metrics,readError,`orders/${id}/customer-view`,`view:${id}`,readAttempt);}
  if(view.status!==200||view.body.id!==id||view.body.confirmed!==true||view.body.status!=='NEW')throw Error('Edit is uncertain; keep the order identity and review its current status');
  if(view.body.revision===expectedRevision+1&&view.body.order&&canonical(selection(view.body.order))===canonical(selection(frozen)))return view;
  if(view.body.revision!==expectedRevision)throw Error('Edit is uncertain; do not overwrite a newer revision');
  // Original revision still exists. The same CAS edit can commit at most once.
 }
}
