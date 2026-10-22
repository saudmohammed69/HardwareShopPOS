// LOCAL PROOF ONLY. No Firebase/Cloudflare secrets, outbound calls or deploy configuration.
const MAX = 9223372036854775807n;
export class Fault extends Error { constructor(status,message){super(message);this.status=status;} }
export const fail=(status,message)=>{throw new Fault(status,message);};
export const exact=(obj,allowed,required=allowed)=>{
  if(!obj || Array.isArray(obj) || typeof obj!=="object" || Object.keys(obj).some(k=>!allowed.includes(k)) || required.some(k=>!Object.hasOwn(obj,k)))fail(400,"Invalid request fields");
};
export const text=(v,max,min=0)=>{if(typeof v!=="string" || v.length>max || v.trim().length<min || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(v))fail(400,"Invalid text");return v.trim();};
export const uuid=v=>{if(typeof v!=="string" || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v))fail(400,"Invalid identity");return v;};
export const capability=v=>{if(typeof v!=="string" || !/^[a-f0-9]{64}$/.test(v))fail(400,"Invalid capability");return v;};
export const canonical=value=>JSON.stringify(value,(_,v)=>v && !Array.isArray(v) && typeof v==="object" ? Object.fromEntries(Object.entries(v).sort(([a],[b])=>a.localeCompare(b))) : v);
export const hash=async value=>Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(value))),b=>b.toString(16).padStart(2,"0")).join("");
const decimal=(v,allowZero=false)=>{
  if(typeof v!=="string" || !/^(0|[1-9]\d{0,8})(\.\d{1,2})?$/.test(v))fail(400,"Use a bounded decimal with at most two decimal places");
  const [whole,fraction=""]=v.split(".");const n=BigInt(whole)*100n+BigInt(fraction.padEnd(2,"0"));
  if(!allowZero && n===0n)fail(400,"Quantity must be positive");return n;
};
const money=v=>{if(typeof v!=="string" || !/^\d{1,19}$/.test(v) || BigInt(v)>MAX)fail(400,"Invalid price");return BigInt(v);};
export const bounded=v=>{if(v<0n || v>MAX)fail(400,"Amount overflow");return v;};
const scaled=(n,scale)=>{const s=n.toString().padStart(scale+1,"0");return (s.slice(0,-scale)+"."+s.slice(-scale)).replace(/\.?0+$/,"") || "0";};

export function projectProduct(source){
  // Projection is constructed from an allowlist; never spread a private POS row.
  const p={id:text(source.id,100,1),name:text(source.name,200,1),category:text(source.category,100),
    type:text(source.type,100),size:text(source.size,100),unit:text(source.unit,40,1),
    aliases:text(source.aliases??"",1000).replace(/\[\[pos-[^\]]*\]\]/g,""),sellingPricePaisa:money(source.sellingPricePaisa).toString(),
    gstRateBp:source.gstRateBp??0,isPipe:source.isPipe===true};
  if(!Number.isInteger(p.gstRateBp) || p.gstRateBp<0 || p.gstRateBp>10000)fail(400,"Invalid tax rate");
  if(p.isPipe){
    if(!Number.isInteger(source.feetPerLength) || source.feetPerLength<1 || source.feetPerLength>10000 || !["piece","length","nos","foot","feet","ft"].includes(p.unit.toLowerCase()))fail(400,"Invalid pipe configuration");
    p.feetPerLength=source.feetPerLength;p.lengthPricePaisa=money(source.lengthPricePaisa).toString();p.footPricePaisa=money(source.footPricePaisa).toString();
    if(BigInt(p.lengthPricePaisa)<=0 || BigInt(p.footPricePaisa)<=0)fail(400,"Both pipe rates are required");
  }
  return p;
}
export function priceLine(p,line){
  exact(line,["productId","quantity","fullLengths","extraFeet","note"],["productId"]);
  let amount,stockEquivalent;
  if(p.isPipe){
    if(Object.hasOwn(line,"quantity") || typeof line.fullLengths!=="string" || !/^(0|[1-9]\d{0,8})$/.test(line.fullLengths))fail(400,"Use lengths and feet for pipes");
    const lengths=BigInt(line.fullLengths),feet=decimal(line.extraFeet,true);
    if(lengths===0n && feet===0n)fail(400,"Quantity must be positive");
    amount=bounded(lengths*money(p.lengthPricePaisa)+bounded((feet*money(p.footPricePaisa)+50n)/100n));
    const allFeet100=lengths*BigInt(p.feetPerLength)*100n+feet;
    if(["ft","feet","foot"].includes(p.unit.toLowerCase()))stockEquivalent=scaled(allFeet100,2);
    else {
      const numerator=allFeet100*100000000n,denominator=BigInt(p.feetPerLength)*100n;
      if(numerator%denominator!==0n)fail(400,"Pipe inventory conversion has unsupported precision");
      stockEquivalent=scaled(numerator/denominator,8);
    }
  }else{
    if(Object.hasOwn(line,"fullLengths") || Object.hasOwn(line,"extraFeet"))fail(400,"Lengths and feet require a pipe");
    const qty=decimal(line.quantity);amount=bounded((qty*money(p.sellingPricePaisa)+50n)/100n);stockEquivalent=scaled(qty,2);
  }
  // Quantity equivalent is a calculation fixture, not a stock check or reservation.
  return {productId:p.id,name:p.name,size:p.size,unit:p.unit,isPipe:p.isPipe,
    quantity:line.quantity??null,fullLengths:line.fullLengths??null,extraFeet:line.extraFeet??null,
    inventoryUnitEquivalent:stockEquivalent,unitPricePaisa:p.isPipe?p.lengthPricePaisa:p.sellingPricePaisa,
    footPricePaisa:p.isPipe?p.footPricePaisa:null,estimatedLineTotalPaisa:amount.toString(),gstRateBp:p.gstRateBp,
    note:text(line.note??"",300)};
}
