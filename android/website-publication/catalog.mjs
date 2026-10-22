// Static browsing only. No order client, customer storage, pairing or mutation API.
import {buildFamilies,searchFamilies,formatMoney} from '/web-model.mjs';
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let groups=[],category='All',limit=36,lastFocus;
function render(){
 const query=$('#search').value,found=searchFamilies(groups,query,category);
 $('#results-title').textContent=query?'Search results':category==='All'?'All essentials':category;
 $('#result-count').textContent=`${found.length.toLocaleString('en-IN')} products · sizes grouped together`;
 $('#clear-search').hidden=!query;
 $('#products').innerHTML=found.length?found.slice(0,limit).map(g=>{
  const min=g.variants.reduce((n,p)=>{const v=BigInt(p.isPipe?p.lengthPricePaisa:p.sellingPricePaisa);return n===null||v<n?v:n;},null);
  return `<article class="product"><div class="product-copy"><h3>${esc(g.name)}</h3><p>${esc(g.type||g.category)}</p><span class="rate">${g.variants.length>1?'From ':''}${formatMoney(min)} <small>/ ${esc(g.unit)}</small></span><span class="size-tag">${g.variants.length} ${g.variants.length===1?'size':'sizes'}</span></div><button class="choose" data-family="${groups.indexOf(g)}" aria-label="View sizes for ${esc(g.name)}">View sizes</button></article>`;
 }).join(''):'<div class="empty"><strong>No products to display</strong><p>Please ask our team at the counter.</p></div>';
 $('#load-more').hidden=found.length<=limit;
}
function categories(){
 const names=['All',...new Set(groups.map(g=>g.category).filter(Boolean))];
 $('#categories').innerHTML=names.map(c=>`<button data-category="${esc(c)}" aria-pressed="${c===category}">${esc(c)}</button>`).join('');
}
async function load(){
 $('#catalog-error').hidden=true;
 try{
  const [settings,catalog]=await Promise.all(['/public-config.json','/catalog.json'].map(async u=>{const r=await fetch(u,{cache:'no-cache'});if(!r.ok)throw Error();return r.json();}));
  // Any accidental live-enabled configuration fails closed before rendering.
  if(settings.liveOrdering!==false||!['MAINTENANCE','CATALOG_ONLY'].includes(settings.mode))throw Error();
  $('#shop-name').textContent=settings.shopName;document.title=settings.shopName+' · Shop catalog';
  $('#ordering-status').textContent=settings.mode==='MAINTENANCE'?'Catalog being prepared · Ordering unavailable':'Catalog only · Ordering unavailable';
  $('#message').textContent=settings.message;$('#hours').textContent=settings.hours;
  $('#shop-contact').textContent=[settings.address,settings.phone].filter(Boolean).join(' · ');
  groups=buildFamilies(settings.mode==='CATALOG_ONLY'?catalog.products:[]);category='All';limit=36;categories();render();
 }catch{
  groups=[];render();$('#ordering-status').textContent='Ordering unavailable';
  $('#catalog-error').textContent='The catalog is unavailable. Please ask our team at the counter.';$('#catalog-error').hidden=false;
 }
}
$('#categories').onclick=e=>{const b=e.target.closest('[data-category]');if(!b)return;category=b.dataset.category;limit=36;categories();render();};
let timer;$('#search').oninput=()=>{clearTimeout(timer);timer=setTimeout(()=>{limit=36;render();},90);};
$('#clear-search').onclick=()=>{$('#search').value='';limit=36;render();$('#search').focus();};
$('#load-more').onclick=()=>{limit+=36;render();};$('#refresh-catalog').onclick=load;
$('#products').onclick=e=>{
 const b=e.target.closest('[data-family]');if(!b)return;const g=groups[Number(b.dataset.family)];if(!g)return;
 lastFocus=b;$('#panel-title').textContent=g.name;
 $('#panel-content').innerHTML=g.variants.map(p=>`<section class="variant"><div class="variant-top"><strong>${esc(p.size||p.name)}</strong><span>${formatMoney(p.isPipe?p.lengthPricePaisa:p.sellingPricePaisa)} / ${esc(p.unit)}</span></div>${p.isPipe?`<p>${p.feetPerLength} ft per full length · ${formatMoney(p.footPricePaisa)} / extra ft</p>`:''}</section>`).join('');
 document.body.style.overflow='hidden';$('#panel').showModal();$('#panel-content').scrollTop=0;
};
function close(){$('#panel').close();document.body.style.overflow='';lastFocus?.focus();}
$('#close-panel').onclick=close;$('#done').onclick=close;
$('#panel').addEventListener('cancel',()=>{document.body.style.overflow='';lastFocus?.focus();});
await load();
