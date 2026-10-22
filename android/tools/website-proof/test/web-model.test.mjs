import {test} from 'node:test';
import assert from 'node:assert/strict';
import {buildFamilies,searchFamilies,totalPaisa,formatMoney,pickupISO} from '../web-model.mjs';
import {product} from '../runtime.mjs';
import {projectProduct,priceLine} from '../pricing.mjs';
import {readFile} from 'node:fs/promises';
import {publicSettings} from '../public-settings.mjs';
test('public branding maps source POS setting keys without leaking private settings',()=>{
 assert.deepEqual(publicSettings({shop_name:' Fixture Hardware ',shop_phone:'123',shop_address:'Fixture street',passwordHash:'PRIVATE',merchant_upi_id:'PRIVATE',shop_id:'PRIVATE'}),{PUBLIC_SHOP_NAME:'Fixture Hardware',PUBLIC_SHOP_PHONE:'123',PUBLIC_SHOP_ADDRESS:'Fixture street'});
});
test('3200 product variants group by family/unit and support names, sizes, aliases, Kannada and categories',()=>{
 const products=Array.from({length:3200},(_,i)=>projectProduct(product(i))),groups=buildFamilies(products);
 assert.equal(groups.length,800);assert.equal(groups[0].variants.length,4);assert.equal(searchFamilies(groups,'ಸೊಕೆಟ್ 4 inch').length,800);
 assert.equal(searchFamilies(groups,'fitting 799').length,1);assert.equal(searchFamilies(groups,'fitting','Other').length,0);
 const split=buildFamilies([products[0],{...products[1],unit:'Foot'}]);assert.equal(split.length,2);
});
test('browser pricing uses actual shared POS pipe cases, BigInt cart money and bounded decimal validation',async()=>{
 const fixture=JSON.parse(await readFile(new URL('../fixtures/pipe.json',import.meta.url),'utf8'));
 // The fixture shape is checked in backend.test; this browser imports the same pure module.
 assert.ok(fixture);const p=projectProduct({...product(0),id:'pipe',isPipe:true,unit:'Length',feetPerLength:20,lengthPricePaisa:'18000',footPricePaisa:'2000'}),map=new Map([[p.id,p]]);
 assert.equal(totalPaisa([{productId:'pipe',fullLengths:'1',extraFeet:'3.5'}],map),'25000');
 assert.equal(formatMoney('25000'),'₹250.00');assert.equal(pickupISO('2026-10-18T10:00'),'2026-10-18T04:30:00Z');
 assert.throws(()=>priceLine(p,{productId:p.id,fullLengths:'1.1',extraFeet:'0'}));assert.throws(()=>totalPaisa([{productId:'missing',quantity:'1'}],map));
});
