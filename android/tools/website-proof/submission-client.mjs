// Browser-compatible recovery state machine; no automatic background send.
export const validReceipt=r=>r?.confirmed===true && typeof r.id==='string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(r.id)
  && typeof r.orderNumber==='string' && r.orderNumber==='WO-'+r.id.toUpperCase()
  && typeof r.totalPaisa==='string' && /^\d{1,19}$/.test(r.totalPaisa) && Number.isInteger(r.revision) && r.revision>0
  && ['NEW','ACCEPTED','PREPARING','READY_FOR_PICKUP','BILLING_IN_PROGRESS','COMPLETED','CANCELLED'].includes(r.status);
export class SubmissionClient {
  constructor(storage,send){this.storage=storage;this.send=send;this.state=JSON.parse(storage.getItem("pending-proof-order")??"null");}
  persist(){this.storage.setItem("pending-proof-order",JSON.stringify(this.state));}
  freeze(cart){
    // A checked revision of an unresolved cart keeps its original identity. A
    // delayed earlier request may still commit after a recovery 404; never mint
    // a new key in that window. Payload conflict is recovered under this key.
    if(this.state?.phase==="revising"){this.state={...this.state,phase:"editing",cart:structuredClone(cart)};this.persist();return;}
    if(this.state && this.state.phase!=="confirmed")throw Error("Recover the original submission before making another checkout");
    const bytes=crypto.getRandomValues(new Uint8Array(32));
    this.state={phase:"editing",cart:structuredClone(cart),submissionId:crypto.randomUUID(),capability:Array.from(bytes,b=>b.toString(16).padStart(2,"0")).join("")};this.persist();
  }
  async submit(){
    if(!this.state || this.state.phase==="confirmed")throw Error("No unresolved submission");
    if(this.state.phase==="unknown"){
      const recovered=await this.send("recover",{submissionId:this.state.submissionId,capability:this.state.capability});
      if(recovered.status===200 && !validReceipt(recovered.body))return {status:503,body:{error:'Recovery response is incomplete. Keep your cart and retry the original submission.',retryable:true}};
      if(recovered.status===200){this.state={...this.state,phase:"confirmed",receipt:recovered.body,cart:null};this.persist();return recovered;}
      if(recovered.status!==404)return recovered;
    }
    this.state.phase="sending";this.persist();
    try{
      const response=await this.send("orders",{...this.state.cart,submissionId:this.state.submissionId,capability:this.state.capability});
      if(response.status<300 && validReceipt(response.body)){this.state={...this.state,phase:"confirmed",receipt:response.body,cart:null};}
      else this.state.phase="unknown";
      this.persist();return response;
    }catch(e){this.state.phase="unknown";this.persist();throw e;}
  }
}
