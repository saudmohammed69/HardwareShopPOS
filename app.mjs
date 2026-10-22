const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const normal=s=>String(s).normalize('NFKC').toLocaleLowerCase('en-IN').replace(/\s+/g,' ').trim();
const money=p=>{const n=BigInt(p);const whole=n/100n,frac=n%100n;return '₹'+whole.toLocaleString('en-IN')+(frac===0n?'':'.'+frac.toString().padStart(2,'0'));};
const per=u=>`per ${u.toLocaleLowerCase('en-IN')}`;
let families=[],limit=40,timer;
function draw(){
 const q=normal($('#search').value);const found=families.filter(g=>!q||g.search.includes(q));
 $('#catalog-status').textContent=`${found.length.toLocaleString('en-IN')} items`;
 $('#clear-search').hidden=!$('#search').value;
 $('#products').innerHTML=found.slice(0,limit).map(g=>{
  if(g.products.length===1){const p=g.products[0];return `<article class="item"><h2>${esc(g.name)}${p.size?` <span class="size">${esc(p.size)}</span>`:''}</h2><p class="price">${money(p.sellingPricePaisa)} <small>${esc(per(p.unit))}</small></p></article>`;}
  return `<article class="item"><details><summary><span>${esc(g.name)}</span><span class="open-label">View sizes and prices</span></summary><ul>${g.products.map(p=>`<li><span>${esc([p.size,p.unit].filter(Boolean).join(' · ')||g.name)}</span><strong>${money(p.sellingPricePaisa)} <small>${esc(per(p.unit))}</small></strong></li>`).join('')}</ul></details></article>`;
 }).join('')||'<p class="empty">No matching items.</p>';
 $('#load-more').hidden=found.length<=limit;
}
async function load(){try{
 const [config,catalog]=await Promise.all(['/public-config.json','/catalog.json'].map(async u=>{const r=await fetch(u,{cache:'no-cache'});if(!r.ok)throw Error('fetch');return r.json();}));
 if(config.mode!=='CATALOG_ONLY'||config.liveOrdering!==false||!Array.isArray(catalog.products))throw Error('mode');
 const keys=['name','size','unit','sellingPricePaisa'];const seen=new Set();
 for(const p of catalog.products){if(Object.keys(p).some(k=>!keys.includes(k))||keys.some(k=>typeof p[k]!=='string')||!p.name.trim()||!p.unit.trim()||!/^[1-9]\d*$/.test(p.sellingPricePaisa))throw Error('schema');const k=JSON.stringify([normal(p.name),normal(p.size),normal(p.unit)]);if(seen.has(k))throw Error('duplicate');seen.add(k);}
 const map=new Map();for(const p of catalog.products){const k=normal(p.name);let g=map.get(k);if(!g){g={name:p.name,products:[],search:''};map.set(k,g);}g.products.push(p);}
 families=[...map.values()].map(g=>{g.products.sort((a,b)=>a.size.localeCompare(b.size,'en-IN',{numeric:true})||a.unit.localeCompare(b.unit,'en-IN'));g.search=normal([g.name,...g.products.flatMap(p=>[p.size,p.unit])].join(' '));return g;}).sort((a,b)=>a.name.localeCompare(b.name,'en-IN'));
 $('#catalog-error').hidden=true;draw();
 }catch{families=[];draw();$('#catalog-status').textContent='Catalog unavailable';$('#catalog-error').hidden=false;}
}
$('#search').addEventListener('input',()=>{clearTimeout(timer);timer=setTimeout(()=>{limit=40;draw();},75);});
$('#clear-search').addEventListener('click',()=>{$('#search').value='';limit=40;draw();$('#search').focus();});
$('#load-more').addEventListener('click',()=>{limit+=40;draw();});
await load();
