import {priceLine} from './pricing.mjs';
export const normalise=s=>String(s).normalize('NFKC').toLocaleLowerCase('en-IN').replace(/\[\[pos-[^\]]*\]\]/g,'').replace(/\s+/g,' ').trim();
export const familyKey=p=>JSON.stringify([p.name,p.category,p.type,p.unit,p.isPipe,p.feetPerLength??null]);
export function buildFamilies(products){
 const groups=new Map();for(const p of products){const key=familyKey(p);let group=groups.get(key);
  if(!group){group={key,name:p.name,category:p.category,type:p.type,unit:p.unit,variants:[]};groups.set(key,group);}group.variants.push(p);
 }
 return [...groups.values()].map(g=>({...g,variants:g.variants.sort((a,b)=>a.size.localeCompare(b.size,undefined,{numeric:true})),search:normalise([g.name,g.category,g.type,...g.variants.flatMap(p=>[p.size,p.aliases])].join(' '))}));
}
export function searchFamilies(groups,query='',category='All'){
 const terms=normalise(query).split(' ').filter(Boolean);
 return groups.filter(g=>(category==='All'||g.category===category)&&terms.every(t=>g.search.includes(t)));
}
export function validateCart(lines,products){
 if(lines.length<1||lines.length>25)throw Error('Choose between 1 and 25 sizes');
 return lines.map(l=>{const p=products.get(l.productId);if(!p)throw Error('Refresh catalog: an item is no longer listed');return priceLine(p,l);});
}
export function totalPaisa(lines,products){return validateCart(lines,products).reduce((n,l)=>n+BigInt(l.estimatedLineTotalPaisa),0n).toString();}
export function formatMoney(value){const n=BigInt(value);return '₹'+(n/100n).toLocaleString('en-IN')+'.'+(n%100n).toString().padStart(2,'0');}
export const hoursLabel=c=>{const fmt=n=>n===1440?'24:00':`${String(Math.floor(n/60)).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`;return `${fmt(c.openMinute)}–${fmt(c.closeMinute)} IST`;};
export function pickupISO(local){return local?new Date(local+':00+05:30').toISOString().replace('.000Z','Z'):null;}
