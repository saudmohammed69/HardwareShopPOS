from pathlib import Path
import json,re,sqlite3,unittest
root=Path(__file__).resolve().parents[1]
schema=json.loads((root/'app/schemas/com.hardwareshop.pos.data.local.database.AppDatabase/4.json').read_text())['database']
dao=(root/'app/src/main/java/com/hardwareshop/pos/data/local/dao/Daos.kt').read_text(encoding='utf-8')
def query(name):
    matches=list(re.finditer(r'@Query\((?:"""([\s\S]*?)"""|"([^"\n]+)")\)\s*(?:suspend )?fun (\w+)',dao))
    return next((m.group(1) or m.group(2)) for m in matches if m.group(3)==name)
def database():
    db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
    for e in schema['entities']:
        db.execute(e['createSql'].replace('${TABLE_NAME}',e['tableName']))
        for i in e.get('indices',[]):db.execute(i['createSql'].replace('${TABLE_NAME}',e['tableName']))
        for t in e.get('contentSyncTriggers',[]):db.execute(t)
    migration=(root/'app/src/main/java/com/hardwareshop/pos/data/local/database/migrations/Migration4To5.kt').read_text()
    for sql in re.findall(r'db.execSQL\("([^"\n]+)"\)',migration): db.execute(sql)
    return db
def insert(db,table,**values):
    e=next(e for e in schema['entities'] if e['tableName']==table)
    row={f['columnName']: '' if f['affinity']=='TEXT' else 0 for f in e['fields'] if f['notNull']}
    row.update(values)
    db.execute(f'INSERT INTO {table} ({",".join(row)}) VALUES ({",".join("?" for _ in row)})',list(row.values()))
class OwnerSqlTests(unittest.TestCase):
    def test_all_dao_queries_prepare_against_schema(self):
        db=database();count=0
        for file in (root/'app/src/main/java/com/hardwareshop/pos/data/local/dao').glob('*.kt'):
            for m in re.finditer(r'@Query\((?:"""([\s\S]*?)"""|"([^"\n]+)")\)',file.read_text(encoding='utf-8')):
                sql=m.group(1) or m.group(2)
                params={p: 1 for p in re.findall(r':(\w+)',sql)}
                with self.subTest(file=file.name,sql=sql):db.execute('EXPLAIN '+sql,params)
                count+=1
        print('Prepared DAO queries:',count)
    def test_credit_balances_do_not_offset_other_customers(self):
        db=database()
        for i in [1,2]:insert(db,'customers',id=i,name=f'Person{i}')
        for i,c,a in [(1,1,1000),(2,1,-250),(3,2,-500)]:insert(db,'credit_transactions',id=i,customerId=c,amountPaisa=a)
        self.assertEqual(750,db.execute(query('getTotalOutstanding')).fetchone()[0])
    def test_customer_settlements_are_scoped_and_include_repayments_and_adjustments(self):
        db=database()
        for i in [1,2,3]:insert(db,'customers',id=i,name=f'Person{i}')
        insert(db,'credit_transactions',id=1,customerId=1,amountPaisa=100000,type='CREDIT_CREATED')
        insert(db,'credit_transactions',id=2,customerId=2,amountPaisa=-40000,type='PARTIAL_PAYMENT')
        insert(db,'credit_transactions',id=3,customerId=3,amountPaisa=-10000,type='ADJUSTMENT')
        sql=query('hasCustomerSettlements')
        self.assertEqual(0,db.execute(sql,{'customerId':1}).fetchone()[0])
        self.assertEqual(1,db.execute(sql,{'customerId':2}).fetchone()[0])
        self.assertEqual(1,db.execute(sql,{'customerId':3}).fetchone()[0])
        insert(db,'credit_transactions',id=4,customerId=1,amountPaisa=-40000,type='PARTIAL_PAYMENT')
        self.assertEqual(1,db.execute(sql,{'customerId':1}).fetchone()[0])
        self.assertEqual(60000,db.execute(query('getBalanceForCustomer'),{'customerId':1}).fetchone()[0])
    def test_report_partial_credit_cancelled_and_period_boundary(self):
        db=database()
        for i,method,total,paid,credit,status,ts in [(1,'CREDIT',1000,200,800,'ACTIVE',100),(2,'UPI',500,500,0,'ACTIVE',150),(3,'CASH',300,300,0,'CANCELLED',160),(4,'CASH',400,400,0,'ACTIVE',200)]:
            insert(db,'bills',id=i,billNumber=str(i),paymentMethod=method,totalPaisa=total,paidPaisa=paid,creditPaisa=credit,status=status,createdAt=ts)
        self.assertEqual((2,1500,0,0,200,500,0,800),db.execute(query('getReportTotals'),{'from':100,'to':200}).fetchone())
    def test_history_literal_search_and_pagination(self):
        db=database()
        for i in range(1,61):insert(db,'bills',id=i,billNumber=f'{i:06}',customerName='A_100%',status='ACTIVE',createdAt=i)
        params=dict(query='A_100%',method='',status='',workerId=0,from_=0,to=0,limit=50,offset=0)
        params['from']=params.pop('from_')
        self.assertEqual(50,len(db.execute(query('getBillsFiltered'),params).fetchall()))
        params['offset']=50
        self.assertEqual(10,len(db.execute(query('getBillsFiltered'),params).fetchall()))
    def test_zero_minimum_not_low_stock(self):
        db=database();insert(db,'categories',id=1,name='Hardware');insert(db,'product_types',id=1,categoryId=1,name='Pipe')
        insert(db,'products',id=1,categoryId=1,typeId=1,name='Pipe',isActive=1,currentStock=0,minimumStock=0)
        self.assertEqual([],db.execute(query('observeLowStockProducts')).fetchall())
        db.execute('UPDATE products SET minimumStock=1')
        self.assertEqual(1,len(db.execute(query('observeLowStockProducts')).fetchall()))
if __name__=='__main__':unittest.main(verbosity=2)
