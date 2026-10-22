"""Generate draft v2 field rules from the actual Kotlin wire schema.
Run test_sync_protocol.py first; never deploy this file automatically.
References: https://firebase.google.com/docs/firestore/security/rules-fields
https://firebase.google.com/docs/firestore/manage-data/transactions#data_validation_for_atomic_operations
"""
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
specs=json.loads((root/'tools/rules-tests/wire-schema.generated.json').read_text())['entities']
quote=lambda x: json.dumps(x,separators=(',',':'))
parts=[]
for spec in specs:
    fields=spec['fields']
    checks=['f is map',f'f.keys().hasOnly({quote([x["name"] for x in fields])})',
            f'f.keys().hasAll({quote([x["name"] for x in fields if x["required"]])})']
    for field in fields:
        name=field['name']; value=f'f.get({quote(name)},null)'
        check={'TEXT':f'{value} is string && {value}.size() <= 50000',
               'INTEGER':f'{value} is int',
               'REAL':f'{value} is number && !math.isNaN({value} - {value})'}[field['kind']]
        if field['reference']: check+=f' && syncId({value})'
        if name in ['sellingPricePaisa','purchasePricePaisa','unitPricePaisa','creditLimitPaisa','minimumStock']:
            check+=f' && {value} >= 0'
        if name=='gstRateBp': check+=f' && {value} >= 0 && {value} <= 10000'
        if name in ['isActive','gstEnabled','restock','requireOwnerApprovalForDiscount']:
            check+=f' && {value} in [0,1]'
        checks.append(f'({value} == null || ({check}))' if not field['required'] else f'({check})')
    if spec['name']=='settings': checks.append("f.key in ['shop_name','shop_phone','shop_address','shop_gst_number']")
    if spec['name']=='stock_movements': checks.append("f.kind in ['OPENING','DELTA']")
    if spec['name']=='credit_transactions':
        checks.append("((f.type == 'CREDIT_CREATED' && f.amountPaisa > 0) || (f.type in ['PARTIAL_PAYMENT','FULL_PAYMENT'] && f.amountPaisa < 0) || f.type == 'ADJUSTMENT')")
        checks.append("f.previousBalancePaisa + f.amountPaisa == f.newBalancePaisa")
    parts.append(f'    function fields_{spec["name"]}(f) {{ return '+ ' &&\n      '.join(checks)+'; }')
branches=[f'(d.entity == {quote(x["name"])} && privateScope == {str(x["scope"]=="PRIVATE").lower()} && (d.deleted ? d.fields.size()==0 : fields_{x["name"]}(d.fields)))' for x in specs]
parts.append('    function syncFields(d,privateScope) { return '+' ||\n      '.join(branches)+'; }')
immutable=[x['name'] for x in specs if x['immutable']]
owner_only=[x['name'] for x in specs if x['ownerOnly']]
no_delete=sorted(set(immutable+['bills','purchase_bills','sale_returns','day_closings']))
template=r'''
    // V2 is structured and versioned. Changes and the matching head must commit together.
    function syncId(id) { return id is string && id.matches('[A-Za-z0-9_-]{16,120}'); }
    function syncMember(shopId) {
      return member(shopId) && get(/databases/$(database)/documents/shops/$(shopId)/members/$(request.auth.uid)).data.get('role','') in ['OWNER','WORKER'];
    }
    function syncEnvelope(d,privateScope) {
      return d.schemaVersion == 2 && syncId(d.recordId) && syncId(d.operationId) &&
        d.entity is string && d.authorUid == request.auth.uid && d.baseVersion is int && d.baseVersion >= 0 &&
        d.version is int && d.version > 0 && d.version == d.baseVersion+1 && d.deleted is bool && d.fields is map &&
        (!d.deleted || !(d.entity in __NO_DELETE__)) && syncFields(d,privateScope);
    }
    function syncRole(shopId,d,old,existing,privateScope) {
      return owner(shopId) || (!privateScope && !(d.entity in __OWNER_ONLY__) && !d.deleted &&
        (d.entity != 'products' || (!existing && d.fields.sellingPricePaisa == 0)) &&
        (d.entity != 'bills' || (existing ? d.fields == old.fields : d.fields.status == 'ACTIVE')) &&
        (d.entity != 'customers' || (d.fields.creditLimitPaisa == (existing ? old.fields.creditLimitPaisa : 0) &&
          d.fields.isActive == (existing ? old.fields.isActive : 1))) &&
        (d.entity != 'stock_movements' || (d.fields.kind == 'DELTA' && d.fields.delta <= 0)) &&
        (d.entity != 'credit_transactions' || d.fields.type in ['CREDIT_CREATED','PARTIAL_PAYMENT','FULL_PAYMENT']));
    }
    function syncTransition(d,old,existing) {
      return d.baseVersion == (existing ? old.version : 0) &&
        (!existing || (old.recordId == d.recordId && old.entity == d.entity &&
          (!old.deleted || d.deleted) &&
          (!(d.entity in __IMMUTABLE__) || (!d.deleted && old.fields == d.fields)) &&
          (d.entity != 'bills' || d.deleted || old.fields.billNumber == d.fields.billNumber)));
    }
    function sameOperation(head,change) {
      return head.schemaVersion==change.schemaVersion && head.operationId==change.operationId &&
        head.recordId==change.recordId && head.entity==change.entity && head.authorUid==change.authorUid &&
        head.baseVersion==change.baseVersion && head.version==change.version &&
        head.deleted==change.deleted && head.fields==change.fields;
    }
    function syncHead(shopId,collection,recordId,privateScope) {
      let d=request.resource.data;
      let path=/databases/$(database)/documents/shops/$(shopId)/$(collection)/$(recordId);
      let existing=exists(path);
      let old=existing ? get(path).data : {};
      let changePath=/databases/$(database)/documents/shops/$(shopId)/$(collection+'_changes')/$(d.operationId);
      return syncMember(shopId) && (!privateScope || owner(shopId)) &&
        d.keys().hasOnly(['schemaVersion','operationId','recordId','entity','authorUid','baseVersion','version','deleted','fields','lastOperationId']) &&
        d.recordId==recordId && d.lastOperationId==d.operationId && syncEnvelope(d,privateScope) &&
        syncRole(shopId,d,old,existing,privateScope) && syncTransition(d,old,existing) &&
        !exists(changePath) && sameOperation(d,getAfter(changePath).data) && getAfter(changePath).data.committedAt==request.time;
    }
    function syncChange(shopId,collection,operationId,privateScope) {
      let d=request.resource.data;
      let path=/databases/$(database)/documents/shops/$(shopId)/$(collection)/$(d.recordId);
      let existing=exists(path);
      let old=existing ? get(path).data : {};
      let head=getAfter(path).data;
      return syncMember(shopId) && (!privateScope || owner(shopId)) &&
        d.keys().hasOnly(['schemaVersion','operationId','recordId','entity','authorUid','baseVersion','version','deleted','fields','committedAt']) &&
        d.operationId==operationId && d.committedAt==request.time && syncEnvelope(d,privateScope) &&
        syncRole(shopId,d,old,existing,privateScope) && syncTransition(d,old,existing) &&
        head.lastOperationId==operationId && sameOperation(head,d);
    }
    function syncBundle(shopId,collection,bundleId,privateScope) {
      let d=request.resource.data;
      let root=get(/databases/$(database)/documents/shops/$(shopId)/$(collection+'_changes')/$(bundleId)).data;
      return syncMember(shopId) && (!privateScope || owner(shopId)) &&
        d.keys().hasOnly(['schemaVersion','bundleId','rootId','rootEntity','authorUid','committedAt','members']) &&
        d.schemaVersion==2 && d.bundleId==bundleId && syncId(bundleId) && syncId(d.rootId) &&
        d.authorUid==request.auth.uid && d.committedAt==request.time && d.members is list &&
        d.members.size()>=2 && d.members.size()<=2000 &&
        d.members.toSet().size()==d.members.size() && d.members.join(',').size()<=700000 &&
        d.members.join(',').matches('[A-Za-z0-9_-]{16,120}:[A-Za-z0-9_-]{16,120}:(bills|bill_items|payments|credit_transactions|sale_returns|sale_return_items|purchase_bills|purchase_bill_items|supplier_payments):[1-9][0-9]{0,18}(,[A-Za-z0-9_-]{16,120}:[A-Za-z0-9_-]{16,120}:(bills|bill_items|payments|credit_transactions|sale_returns|sale_return_items|purchase_bills|purchase_bill_items|supplier_payments):[1-9][0-9]{0,18})*') &&
        d.rootEntity==(privateScope ? 'purchase_bills' : 'bills') &&
        d.members.hasAll([bundleId+':'+d.rootId+':'+d.rootEntity+':'+string(root.version)]) &&
        root.authorUid==request.auth.uid && root.recordId==d.rootId && root.entity==d.rootEntity && !root.deleted;
    }
'''.replace('__NO_DELETE__',quote(no_delete)).replace('__OWNER_ONLY__',quote(owner_only)).replace('__IMMUTABLE__',quote(immutable))
parts.append(template)
for scope in ['public','private']:
    collection='sync_v2_'+scope
    private=str(scope=='private').lower()
    read='owner(shopId)' if scope=='private' else 'syncMember(shopId)'
    parts.append(f'''
    match /shops/{{shopId}}/{collection}/{{recordId}} {{
      allow read: if {read};
      allow create,update: if syncHead(shopId,'{collection}',recordId,{private});
      allow delete: if false;
    }}
    match /shops/{{shopId}}/{collection}_changes/{{operationId}} {{
      allow read: if {read};
      allow create: if syncChange(shopId,'{collection}',operationId,{private});
      allow update,delete: if false;
    }}
    match /shops/{{shopId}}/{collection}_bundles/{{bundleId}} {{
      allow read: if {read};
      allow create: if syncBundle(shopId,'{collection}',bundleId,{private});
      allow update,delete: if false;
    }}''')
base=(root/'firestore.rules').read_text(encoding='utf-8').rstrip()
assert base.endswith('  }\n}')
output=base[:-len('  }\n}')]+ '\n'.join(parts)+'\n  }\n}\n'
(root/'firestore.sync-v2.rules').write_text(output,encoding='utf-8')
(root/'tools/rules-tests/firestore.rules').write_text(output,encoding='utf-8')
print(f'Generated draft field rules for {len(specs)} entities ({len(output.encode())} bytes). Not deployed.')
