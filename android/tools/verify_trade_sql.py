"""Checks the real Room-exported v5 schema against upgrades and relational safeguards."""
from pathlib import Path
import json,re,sqlite3,unittest
root=Path(__file__).resolve().parents[1]
schemas=root/'app/schemas/com.hardwareshop.pos.data.local.database.AppDatabase'
v4=json.loads((schemas/'4.json').read_text())['database']
v5=json.loads((schemas/'5.json').read_text())['database']
migration=(root/'app/src/main/java/com/hardwareshop/pos/data/local/database/migrations/Migration4To5.kt').read_text()
sql=re.findall(r'db.execSQL\("([^"\n]+)"\)',migration)
def create(schema):
    db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
    for e in schema['entities']:
        db.execute(e['createSql'].replace('${TABLE_NAME}',e['tableName']))
        for i in e.get('indices',[]):db.execute(i['createSql'].replace('${TABLE_NAME}',e['tableName']))
        for t in e.get('contentSyncTriggers',[]):db.execute(t)
    return db
def insert(db,table,**values):
    e=next(e for e in v5['entities'] if e['tableName']==table)
    row={f['columnName']: '' if f['affinity']=='TEXT' else 0 for f in e['fields'] if f['notNull']}
    row.update(values)
    db.execute(f'INSERT INTO {table} ({",".join(row)}) VALUES ({",".join("?" for _ in row)})',list(row.values()))
def migrated():
    db=create(v4)
    for statement in sql:db.execute(statement)
    return db
class TradeSqlTests(unittest.TestCase):
    def test_upgrade_matches_real_room_export_for_every_table_index_and_fk(self):
        upgraded=migrated();fresh=create(v5)
        for table in [e['tableName'] for e in v5['entities']]:
            for pragma in ['table_info','foreign_key_list']:
                self.assertEqual(fresh.execute(f'PRAGMA {pragma}(`{table}`)').fetchall(),upgraded.execute(f'PRAGMA {pragma}(`{table}`)').fetchall(),table)
            def indexes(db):
                return sorted((row[1],row[2],db.execute(f'PRAGMA index_info(`{row[1]}`)').fetchall()) for row in db.execute(f'PRAGMA index_list(`{table}`)'))
            self.assertEqual(indexes(fresh),indexes(upgraded),table)
    def test_existing_settings_products_and_fts_survive_upgrade(self):
        db=create(v4)
        insert(db,'categories',id=1,name='Tools')
        insert(db,'product_types',id=1,categoryId=1,name='Pipe')
        insert(db,'products',id=1,categoryId=1,typeId=1,name='Copper elbow',unit='Piece',isActive=1,currentStock=7)
        insert(db,'settings',key='cart:owner',value='legacy cart')
        for statement in sql:db.execute(statement)
        self.assertEqual(('legacy cart',),db.execute("SELECT value FROM settings WHERE key='cart:owner'").fetchone())
        self.assertEqual((7.0,),db.execute('SELECT currentStock FROM products').fetchone())
        self.assertEqual(1,db.execute("SELECT count(*) FROM products_fts WHERE products_fts MATCH 'Copper'").fetchone()[0])
    def test_return_ids_are_unique_and_history_prevents_orphan_deletion(self):
        db=migrated();insert(db,'bills',id=1,billNumber='000001')
        insert(db,'bill_items',id=1,billId=1,productId=1,quantity=2)
        insert(db,'sale_returns',id='return-1',billId=1,method='CASH')
        insert(db,'sale_return_items',id=1,returnId='return-1',billItemId=1,quantity=1)
        with self.assertRaises(sqlite3.IntegrityError):insert(db,'sale_returns',id='return-1',billId=1,method='CASH')
        with self.assertRaises(sqlite3.IntegrityError):db.execute('DELETE FROM bills WHERE id=1')
        with self.assertRaises(sqlite3.IntegrityError):db.execute('DELETE FROM bill_items WHERE id=1')
    def test_payment_duplicate_and_missing_invoice_are_rejected(self):
        db=migrated();insert(db,'suppliers',id=1,name='Supplier');insert(db,'purchase_bills',id=1,supplierId=1,totalPaisa=1000)
        insert(db,'supplier_payments',id='payment-1',purchaseBillId=1,amountPaisa=500,method='CASH')
        with self.assertRaises(sqlite3.IntegrityError):insert(db,'supplier_payments',id='payment-1',purchaseBillId=1,amountPaisa=500,method='CASH')
        with self.assertRaises(sqlite3.IntegrityError):insert(db,'supplier_payments',id='payment-2',purchaseBillId=99,amountPaisa=500,method='CASH')
    def test_return_report_uses_event_date_and_refund_method(self):
        db=migrated();insert(db,'bills',id=1,billNumber='000001',createdAt=1)
        insert(db,'sale_returns',id='r1',billId=1,totalPaisa=1180,discountPaisa=50,taxPaisa=180,creditReductionPaisa=1000,refundPaisa=180,method='UPI',createdAt=150)
        insert(db,'sale_returns',id='r2',billId=1,totalPaisa=500,refundPaisa=500,method='CASH',createdAt=200)
        source=(root/'app/src/main/java/com/hardwareshop/pos/data/local/dao/TradeDao.kt').read_text()
        query=re.search(r'@Query\("([^"\n]+)"\)\s*suspend fun totals',source).group(1)
        self.assertEqual((1180,50,180,1000,0,180,0),db.execute(query,{'from':100,'to':200}).fetchone())
    def test_restore_dependency_order_accepts_new_history(self):
        db=migrated();insert(db,'suppliers',id=1,name='Supplier');insert(db,'purchase_bills',id=1,supplierId=1)
        insert(db,'supplier_payments',id='p',purchaseBillId=1,amountPaisa=1)
        insert(db,'bills',id=1,billNumber='000001');insert(db,'bill_items',id=1,billId=1)
        insert(db,'sale_returns',id='r',billId=1);insert(db,'sale_return_items',id=1,returnId='r',billItemId=1,quantity=1)
        for table in ['sale_return_items','sale_returns','supplier_payments','purchase_bills','suppliers','bill_items','bills']:db.execute(f'DELETE FROM {table}')
        self.assertEqual([],db.execute('PRAGMA foreign_key_check').fetchall())
if __name__=='__main__':unittest.main(verbosity=2)
