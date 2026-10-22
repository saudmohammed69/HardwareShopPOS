import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertSucceeds,assertFails} from '@firebase/rules-unit-testing';
import {doc,setDoc,getDoc,updateDoc,deleteDoc,writeBatch,serverTimestamp,Timestamp} from 'firebase/firestore';

const projectId='demo-hardware-shop-sync';
assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:61907','Only the local demo emulator may run these tests');
const schema=JSON.parse(await readFile(new URL('./wire-schema.generated.json',import.meta.url),'utf8')).entities;
let env;
before(async()=> { env=await initializeTestEnvironment({projectId,firestore:{host:'127.0.0.1',port:61907,
  rules:await readFile(new URL('../../firestore.sync-v2.rules',import.meta.url),'utf8')}}); });
after(async()=> { await env?.cleanup(); });
beforeEach(async()=> {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx=> {
    const db=ctx.firestore();
    await Promise.all([
      setDoc(doc(db,'shops/shop-a/members/owner'),{enabled:true,role:'OWNER'}),
      setDoc(doc(db,'shops/shop-a/members/worker'),{enabled:true,role:'WORKER'}),
      setDoc(doc(db,'shops/shop-a/members/disabled'),{enabled:false,role:'OWNER'}),
      setDoc(doc(db,'shops/shop-a/members/legacy'),{enabled:true}),
      setDoc(doc(db,'shops/shop-b/members/other-owner'),{enabled:true,role:'OWNER'})
    ]);
  });
});
const dbFor=uid=> uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const passwordDb = uid => env.authenticatedContext(uid,{firebase:{sign_in_provider:'password'}}).firestore();
function createAccountShop(db,uid,shop=`account_${uid}`) {
  const batch=writeBatch(db);
  batch.set(doc(db,`shops/${shop}`),{ownerUid:uid});
  batch.set(doc(db,`shops/${shop}/members/${uid}`),{enabled:true,role:'OWNER',printer:true});
  batch.set(doc(db,`cloudAccounts/${uid}`),{shopId:shop});
  return batch.commit();
}
test('password account atomically creates and restores its own shop membership',async()=> {
  const db=passwordDb('new-owner');
  await assertSucceeds(createAccountShop(db,'new-owner'));
  assert.equal((await getDoc(doc(db,'cloudAccounts/new-owner'))).data().shopId,'account_new-owner');
  await assertSucceeds(upload(db,mutation('products','new-owner'),{shop:'account_new-owner'}));
});
test('anonymous account cannot bootstrap owner access',async()=> {
  await assertFails(createAccountShop(dbFor('anonymous-new'),'anonymous-new'));
});
test('account cannot claim another namespace, identity or existing shop',async()=> {
  const db=passwordDb('new-owner');
  await assertFails(createAccountShop(db,'new-owner','account_someone-else'));
  await assertFails(createAccountShop(db,'new-owner','shop-a'));
  await assertFails(createAccountShop(db,'someone-else'));
  await assertFails(setDoc(doc(db,'shops/account_new-owner/members/new-owner'),{enabled:true,role:'OWNER',printer:true}));
});
test('account directory is private and cannot authorize nonmembers or change shops',async()=> {
  const db=passwordDb('new-owner');
  await assertFails(setDoc(doc(db,'cloudAccounts/new-owner'),{shopId:'shop-a'}));
  await assertSucceeds(createAccountShop(db,'new-owner'));
  await assertFails(getDoc(doc(passwordDb('other'),'cloudAccounts/new-owner')));
  await assertFails(setDoc(doc(db,'cloudAccounts/new-owner'),{shopId:'shop-a'}));
  await assertFails(setDoc(doc(db,'shops/account_new-owner/members/new-owner'),{enabled:true,role:'OWNER',printer:false}));
});
test('approved legacy owner can retain its shop when linking password credentials',async()=> {
  await assertSucceeds(setDoc(doc(passwordDb('owner'),'cloudAccounts/owner'),{shopId:'shop-a'}));
  await assertFails(setDoc(doc(passwordDb('worker'),'cloudAccounts/worker'),{shopId:'shop-a'}));
});
test('second phone on same account can register the worker printer and acknowledge a receipt',async()=> {
  const owner=passwordDb('shop-account'),workerPhone=passwordDb('shop-account');
  await assertSucceeds(createAccountShop(owner,'shop-account'));
  const path='shops/account_shop-account';
  await assertSucceeds(setDoc(doc(workerPhone,`${path}/config/device`),{deviceId:'worker-phone',deviceName:'Counter'}));
  const job=doc(owner,`${path}/printJobs/job-one`);
  await assertSucceeds(setDoc(job,{jobId:'job-one',status:'PENDING',receiptPayload:'base64-receipt',deviceId:'worker-phone'}));
  const remote=doc(workerPhone,`${path}/printJobs/job-one`);
  assert.equal((await getDoc(remote)).data().status,'PENDING');
  await assertSucceeds(updateDoc(remote,{status:'RECEIVED',claimToken:'lease-one',leaseUntil:1000}));
  await assertSucceeds(updateDoc(remote,{status:'PRINTING'}));
  await assertFails(updateDoc(remote,{status:'PRINTED',receiptPayload:'changed'}));
  await assertSucceeds(updateDoc(remote,{status:'PRINTED',printedAt:serverTimestamp()}));
  assert.equal((await getDoc(job)).data().status,'PRINTED');
  await assertFails(getDoc(doc(passwordDb('unrelated'),`${path}/printJobs/job-one`)));
});
function mutation(entity='products',authorUid='owner',overrides={}) {
  const spec=schema.find(s=>s.name===entity);
  const fields=Object.fromEntries(spec.fields.filter(f=>f.required).map(f=>[f.name,
    f.reference ? 'global_reference_000001' : f.kind==='TEXT' ? 'Value' : 0]));
  if(entity==='stock_movements') Object.assign(fields,{kind:'DELTA',delta:-3});
  if(entity==='settings') fields.key='shop_name';
  if(entity==='bills') Object.assign(fields,{status:'ACTIVE',paymentMethod:'CASH'});
  if(entity==='credit_transactions') Object.assign(fields,{type:'CREDIT_CREATED',amountPaisa:100,newBalancePaisa:100});
  return {schemaVersion:2,operationId:`operation_${entity}_0001`,recordId:`record_${entity}_0001`,entity,authorUid,
    baseVersion:0,version:1,deleted:false,fields,...overrides};
}
function paths(m,shop='shop-a',scope) {
  const collection=scope ?? (schema.find(s=>s.name===m.entity).scope==='PRIVATE' ? 'sync_v2_private' : 'sync_v2_public');
  return [`shops/${shop}/${collection}/${m.recordId}`,`shops/${shop}/${collection}_changes/${m.operationId}`];
}
function upload(db,m,{shop='shop-a',scope,receiptFields}={}) {
  const [head,change]=paths(m,shop,scope);const batch=writeBatch(db);
  batch.set(doc(db,head),{...m,lastOperationId:m.operationId});
  batch.set(doc(db,change),{...m,...receiptFields,committedAt:serverTimestamp()});
  return batch.commit();
}

test('owner can create every field-validated entity at its correct privacy boundary',async()=> {
  for(const spec of schema) await assertSucceeds(upload(dbFor('owner'),mutation(spec.name)));
});
test('upload requires both matching head and immutable receipt',async()=> {
  const db=dbFor('owner'),m=mutation();const [head,change]=paths(m);
  await assertFails(setDoc(doc(db,head),{...m,lastOperationId:m.operationId}));
  await assertFails(setDoc(doc(db,change),{...m,committedAt:serverTimestamp()}));
  await assertFails(upload(db,m,{receiptFields:{fields:{...m.fields,sellingPricePaisa:500}}}));
  assert.equal((await getDoc(doc(db,head))).exists(),false);
});
test('version conflicts and receipt rewrites are rejected',async()=> {
  const db=dbFor('owner'),m=mutation();await assertSucceeds(upload(db,m));
  await assertFails(upload(db,{...m,operationId:'operation_stale_00001',fields:{...m.fields,sellingPricePaisa:1500}}));
  await assertSucceeds(upload(db,{...m,operationId:'operation_next_000001',baseVersion:1,version:2,
    fields:{...m.fields,sellingPricePaisa:1500}}));
  await assertFails(setDoc(doc(db,paths(m)[1]),{...m,committedAt:serverTimestamp()}));
  await assertFails(deleteDoc(doc(db,paths(m)[1])));
});
test('worker cannot read or write buying prices or supplier records',async()=> {
  for(const entity of ['product_costs','suppliers','purchase_bills','purchase_bill_items','supplier_payments']) {
    const m=mutation(entity);await assertSucceeds(upload(dbFor('owner'),m));
    await assertFails(getDoc(doc(dbFor('worker'),paths(m)[0])));
    await assertFails(getDoc(doc(dbFor('worker'),paths(m)[1])));
    await assertFails(upload(dbFor('worker'),mutation(entity,'worker',{recordId:`worker_${entity}_001`,operationId:`worker_op_${entity}_001`})));
  }
});
test('no public cost, password, salt or arbitrary field can be introduced',async()=> {
  for(const key of ['purchasePricePaisa','passwordHash','passwordSalt','admin']) {
    const m=mutation();await assertFails(upload(dbFor('owner'),{...m,fields:{...m.fields,[key]:'secret'}}));
  }
  await assertFails(upload(dbFor('owner'),mutation('product_costs'),{scope:'sync_v2_public'}));
});
test('membership promotion and cross-shop access are rejected',async()=> {
  const m=mutation();await assertSucceeds(upload(dbFor('owner'),m));
  await assertFails(setDoc(doc(dbFor('worker'),'shops/shop-a/members/worker'),{enabled:true,role:'OWNER'}));
  await assertFails(getDoc(doc(dbFor('other-owner'),paths(m)[0])));
  await assertFails(upload(dbFor('owner'),m,{shop:'shop-b'}));
  for(const uid of [null,'disabled','legacy']) {
    await assertFails(getDoc(doc(dbFor(uid),paths(m)[0])));
    await assertFails(upload(dbFor(uid),mutation('categories',uid??'anonymous')));
  }
});
test('worker can add a pending product but cannot price or edit one',async()=> {
  const db=dbFor('worker'),m=mutation('products','worker');
  await assertSucceeds(upload(db,m));
  await assertFails(upload(db,{...m,operationId:'worker_edit_operation_01',baseVersion:1,version:2,fields:{...m.fields,sellingPricePaisa:1000}}));
  const priced=mutation('products','worker',{recordId:'worker_priced_product_01',operationId:'worker_priced_operation_01'});
  await assertFails(upload(db,{...priced,fields:{...priced.fields,sellingPricePaisa:1000}}));
});
test('worker can create customers but cannot set credit limits or disable them',async()=> {
  const db=dbFor('worker'),m=mutation('customers','worker');m.fields.isActive=1;
  await assertSucceeds(upload(db,m));
  await assertFails(upload(db,{...m,operationId:'customer_limit_operation_1',baseVersion:1,version:2,fields:{...m.fields,creditLimitPaisa:10000}}));
  await assertFails(upload(db,{...m,operationId:'customer_disable_operation_1',baseVersion:1,version:2,fields:{...m.fields,isActive:0}}));
});
test('worker cannot create owner-controlled profiles and categories',async()=> {
  for(const type of ['workers','categories','product_types','sale_returns','shop_profile'])
    await assertFails(upload(dbFor('worker'),mutation(type,'worker')));
});
test('worker cannot increase stock, create opening stock or impersonate owner',async()=> {
  const db=dbFor('worker'),m=mutation('stock_movements','worker');
  await assertSucceeds(upload(db,m));
  await assertFails(upload(db,{...m,recordId:'stock_increase_record_001',operationId:'stock_increase_operation_1',fields:{...m.fields,delta:3}}));
  await assertFails(upload(db,{...m,recordId:'stock_opening_record_0001',operationId:'stock_opening_operation_1',fields:{...m.fields,kind:'OPENING'}}));
  await assertFails(upload(db,mutation('customers','owner')));
});
test('financial entries cannot be overwritten or deleted by an owner',async()=> {
  const db=dbFor('owner'),m=mutation('payments');await assertSucceeds(upload(db,m));
  await assertFails(upload(db,{...m,operationId:'changed_payment_operation_1',baseVersion:1,version:2,fields:{...m.fields,amountPaisa:99}}));
  await assertFails(upload(db,{...m,operationId:'deleted_payment_operation_1',baseVersion:1,version:2,deleted:true,fields:{}}));
  await assertFails(deleteDoc(doc(db,paths(m)[0])));
});
test('types, required fields, settings allowlist and head identity are enforced',async()=> {
  const db=dbFor('owner'),m=mutation();
  await assertFails(upload(db,{...m,fields:{...m.fields,categoryId:1}}));
  await assertFails(upload(db,{...m,fields:{...m.fields,isActive:2}}));
  await assertFails(upload(db,{...m,fields:{...m.fields,sellingPricePaisa:-1}}));
  const {name,...missing}=m.fields;await assertFails(upload(db,{...m,fields:missing}));
  const s=mutation('settings');await assertFails(upload(db,{...s,fields:{...s.fields,key:'printer_mac'}}));
});
test('server commit time cannot be supplied by a client clock',async()=> {
  const db=dbFor('owner'),m=mutation(),[head,change]=paths(m);const batch=writeBatch(db);
  batch.set(doc(db,head),{...m,lastOperationId:m.operationId});
  batch.set(doc(db,change),{...m,committedAt:new Timestamp(0,0)});
  await assertFails(batch.commit());
});

test('nonfinite quantities and inconsistent credit entries are rejected',async()=> {
  const db=dbFor('owner'),m=mutation('stock_movements');
  for(const delta of [NaN,Infinity,-Infinity]) await assertFails(upload(db,{...m,fields:{...m.fields,delta}}));
  const credit=mutation('credit_transactions');
  await assertFails(upload(db,{...credit,fields:{...credit.fields,newBalancePaisa:999}}));
  await assertFails(upload(db,{...credit,fields:{...credit.fields,type:'FULL_PAYMENT'}}));
  await assertSucceeds(upload(db,{...credit,fields:{...credit.fields,type:'PARTIAL_PAYMENT',amountPaisa:-100,previousBalancePaisa:500,newBalancePaisa:400}}));
});
test('bundle completion requires its authored root and typed receipt references',async()=> {
  const db=dbFor('owner'),m=mutation('bills');await assertSucceeds(upload(db,m));
  const path=`shops/shop-a/sync_v2_public_bundles/${m.operationId}`;
  const bundle={schemaVersion:2,bundleId:m.operationId,rootId:m.recordId,rootEntity:'bills',authorUid:'owner',
    committedAt:serverTimestamp(),members:[`${m.operationId}:${m.recordId}:bills:1`,'operation_bill_line_01:record_bill_line_0001:bill_items:1']};
  await assertFails(setDoc(doc(db,path),{...bundle,members:[bundle.members[0],{passwordHash:'secret'}]}));
  await assertFails(setDoc(doc(db,path),{...bundle,members:[bundle.members[0],42]}));
  await assertFails(setDoc(doc(dbFor('worker'),path),{...bundle,authorUid:'worker'}));
  await assertSucceeds(setDoc(doc(db,path),bundle));
  await assertFails(setDoc(doc(db,path),bundle));
  await assertFails(deleteDoc(doc(db,path)));
});

test('owner pipe rates and immutable two-quantity sales are accepted', async()=> {
 const p=mutation('products'); Object.assign(p.fields,{isPipe:1,pipeLengthPricePaisa:40000,pipeFootPricePaisa:3500,pipeFeetPerLength:20});
 await assertSucceeds(upload(dbFor('owner'),p));
 const b=mutation('bill_items');Object.assign(b.fields,{isPipe:1,pipeLengths:1,pipeFeet:10,pipeFootPricePaisa:3500,unitPricePaisa:40000,itemTotalPaisa:75000});
 await assertSucceeds(upload(dbFor('owner'),b));
 const invalid=mutation('bill_items','owner',{operationId:'pipe_bad_operation_001',recordId:'pipe_bad_record_000001'});
 Object.assign(invalid.fields,{isPipe:1,pipeLengths:-1,pipeFeet:10,pipeFootPricePaisa:3500});
 await assertFails(upload(dbFor('owner'),invalid));
});
