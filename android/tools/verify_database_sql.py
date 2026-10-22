"""Host SQLite regression checks. Run: python tests/verify_database_sql.py.
These execute the shipped migration SQL; Android/Room tests remain separate.
"""
import json, re, sqlite3, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/hardwareshop/pos'
SCHEMAS=ROOT/'app/schemas/com.hardwareshop.pos.data.local.database.AppDatabase'

def statements(path):
    text=path.read_text(encoding='utf-8-sig')
    return [m.group(1) or m.group(2) for m in re.finditer(r'db.execSQL\(\s*(?:"""([\s\S]*?)"""\.trimIndent\(\)|"([^"\n]+)")\s*\)',text)]

def create(version, missing=False):
    db=sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    schema=json.loads((SCHEMAS/f'{version}.json').read_text())['database']
    for e in schema['entities']:
        if missing and e['tableName']=='products_fts': continue
        db.execute(e['createSql'].replace('${TABLE_NAME}', e['tableName']))
        for index in e.get('indices',[]): db.execute(index['createSql'].replace('${TABLE_NAME}',e['tableName']))
        for trigger in e.get('contentSyncTriggers',[]): db.execute(trigger)
    return db

def seed(db):
    db.execute("INSERT INTO categories VALUES (1,'Hardware',0,1,0,0)")
    db.execute("INSERT INTO product_types VALUES (1,1,'Tools',0,1,0,0)")
    db.execute("""INSERT INTO products (id,categoryId,typeId,name,size,unit,sellingPricePaisa,
        purchasePricePaisa,gstRateBp,searchAliases,currentStock,minimumStock,imagePath,isActive,createdAt,updatedAt)
        VALUES(1,1,1,'hammer','','Piece',100,0,0,'tool',1,0,NULL,1,0,0)""")

def migrate(db, version):
    if version==2:
        for sql in statements(SRC/'data/local/database/migrations/Migration2To3.kt'): db.execute(sql)
    for sql in statements(SRC/'data/local/database/migrations/Migration3To4.kt'): db.execute(sql)

def hits(db, word):
    return db.execute('SELECT docid FROM products_fts WHERE products_fts MATCH ?', (word,)).fetchall()

class DatabaseChecks(unittest.TestCase):
    def test_upgrade_paths_and_search_sync(self):
        for version in (2,3):
            for missing in (False,True):
                with self.subTest(version=version,missing=missing):
                    db=create(version,missing); seed(db); migrate(db,version)
                    self.assertEqual([(1,)],hits(db,'hammer'))
                    db.execute("UPDATE products SET name='wrench',searchAliases='spanner' WHERE id=1")
                    self.assertEqual([],hits(db,'hammer')); self.assertEqual([],hits(db,'tool'))
                    self.assertEqual([(1,)],hits(db,'spanner'))
                    db.execute('DELETE FROM products WHERE id=1')
                    self.assertEqual([],hits(db,'wrench'))
                    db.execute("INSERT INTO products VALUES (2,1,1,'saw','','Piece',100,0,0,'blade',1,0,NULL,1,0,0)")
                    self.assertEqual([(2,)],hits(db,'blade'))
                    db.execute("INSERT INTO products_fts(products_fts) VALUES('integrity-check')")
                    db.close()

    def test_stale_index_rebuilt(self):
        db=create(3); seed(db)
        db.execute('DELETE FROM products_fts WHERE docid=1')
        self.assertEqual([],hits(db,'hammer'))
        migrate(db,3); self.assertEqual([(1,)],hits(db,'hammer'))

    def test_numeric_bill_query(self):
        db=sqlite3.connect(':memory:'); db.execute('CREATE TABLE bills(billNumber TEXT UNIQUE)')
        db.executemany('INSERT INTO bills VALUES (?)',[(x,) for x in ['000012','000099-R','','garbage','2147483648']])
        text=(SRC/'data/local/dao/Daos.kt').read_text(encoding='utf-8-sig')
        query=re.search(r'@Query\("(SELECT MAX\(CAST\(billNumber[^\n]+)"\)',text).group(1)
        self.assertEqual(2147483648,db.execute(query).fetchone()[0])
        # SQLite parses numeric prefixes, contrary to the original report's NULL claim.
        self.assertEqual((12,0),db.execute("SELECT CAST('000012-R' AS INTEGER),CAST('' AS INTEGER)").fetchone())

    def test_restore_stock_foreign_key_order(self):
        db=create(3); seed(db)
        schema=json.loads((SCHEMAS/'3.json').read_text())['database']
        ent=next(e for e in schema['entities'] if e['tableName']=='stock_adjustments')
        values=[]
        for field in ent['fields']:
            n=field['columnName']
            values.append(1 if n in ('id','productId') else ('' if field['affinity']=='TEXT' else 0))
        db.execute('INSERT INTO stock_adjustments VALUES ('+','.join('?' for _ in values)+')',values)
        with self.assertRaises(sqlite3.IntegrityError): db.execute('DELETE FROM products')
        db.execute('DELETE FROM stock_adjustments'); db.execute('DELETE FROM products')
        self.assertEqual([],db.execute('PRAGMA foreign_key_check').fetchall())

    def test_schema_four_is_version_only_change(self):
        old=json.loads((SCHEMAS/'3.json').read_text()); new=json.loads((SCHEMAS/'4.json').read_text())
        old['database']['version']=4
        self.assertEqual(old,new)

if __name__=='__main__': unittest.main(verbosity=2)
