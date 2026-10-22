"""Run actual Kotlin-generated migration/trigger SQL against SQLite with v5 Room tables.

Usage: python tools/test_sync_journal.py --java <java.exe> --gradle-cache <.gradle>
This tests SQLite semantics; it does not replace Android Room/Hilt/emulator tests.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENTITIES = json.loads((ROOT / 'app/schemas/com.hardwareshop.pos.data.local.database.AppDatabase/5.json').read_text(encoding='utf-8'))['database']['entities']
TABLES = {e['tableName']: e for e in ENTITIES}
SQL = []
ACK_SQL = ''


def prepare(db):
    db.execute('PRAGMA foreign_keys=ON')
    for e in ENTITIES:
        db.execute(e['createSql'].replace('${TABLE_NAME}', e['tableName']))
        for index in e.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', e['tableName']))


def row(db, table, **values):
    data = {f['columnName']: ('' if f['affinity'] == 'TEXT' else 0) if f['notNull'] else None for f in TABLES[table]['fields']}
    data.update(values)
    db.execute(f"INSERT INTO `{table}` ({','.join('`'+k+'`' for k in data)}) VALUES ({','.join('?' for _ in data)})", list(data.values()))


def seed(db):
    row(db, 'categories', id=1, name='Plumbing', isActive=1)
    row(db, 'product_types', id=1, categoryId=1, name='PVC Elbow', isActive=1)
    row(db, 'products', id=1, categoryId=1, typeId=1, name='Elbow', size='1/2', unit='Piece', currentStock=100.0, isActive=1)


def install(db):
    for statement in SQL:
        db.execute(statement)


class JournalTests(unittest.TestCase):
    def test_room_abort_updates_preserve_identity_and_track_repeated_edits(self):
        identity = self.db.execute("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'").fetchone()[0]
        for price in (100, 200, 300):
            self.db.execute('UPDATE OR ABORT products SET sellingPricePaisa=? WHERE id=1', (price,))
        for quantity in (99, 98, 97):
            self.db.execute('UPDATE OR ABORT products SET currentStock=? WHERE id=1', (quantity,))
        self.assertEqual(identity, self.db.execute("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'").fetchone()[0])
        self.assertEqual(4, self.scalar("SELECT revision FROM sync_dirty WHERE syncId='" + identity + "'"))
        self.assertEqual(3, self.scalar("SELECT revision FROM sync_stock_revisions WHERE localKey='1'"))
        self.assertEqual(3, self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='DELTA'"))

    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        prepare(self.db)
        seed(self.db)
        install(self.db)
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def scalar(self, sql, args=()):
        return self.db.execute(sql, args).fetchone()[0]

    def test_migration_preserves_existing_stock_and_relations(self):
        self.assertEqual(self.scalar('SELECT currentStock FROM products WHERE id=1'), 100)
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(), [])
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty'), 3)

    def test_existing_financial_rows_survive_journal_install(self):
        legacy = sqlite3.connect(':memory:')
        try:
            prepare(legacy); seed(legacy)
            row(legacy, 'customers', id=1, name='Customer')
            row(legacy, 'bills', id=1, billNumber='000001', customerId=1, subtotalPaisa=1500, totalPaisa=1500, paymentMethod='CREDIT', creditPaisa=1500)
            row(legacy, 'bill_items', id=1, billId=1, productId=1, productName='Elbow', productUnit='Piece', quantity=1, unitPricePaisa=1500, itemTotalPaisa=1500)
            row(legacy, 'credit_transactions', id=1, customerId=1, billId=1, type='CREDIT_CREATED', amountPaisa=1500, newBalancePaisa=1500)
            row(legacy, 'suppliers', id=1, name='Supplier')
            row(legacy, 'purchase_bills', id=1, supplierId=1, supplierBillNumber='INV-A', totalPaisa=10000)
            row(legacy, 'purchase_bill_items', id=1, purchaseBillId=1, productId=1, quantity=10, purchasePricePaisa=1000, itemTotalPaisa=10000)
            row(legacy, 'supplier_payments', id='payment-1', purchaseBillId=1, amountPaisa=10000, method='CASH')
            before = {t: legacy.execute(f'SELECT * FROM `{t}`').fetchall() for t in TABLES if t != 'products_fts'}
            install(legacy)
            after = {t: legacy.execute(f'SELECT * FROM `{t}`').fetchall() for t in before}
            self.assertEqual(before, after)
            self.assertEqual(legacy.execute('PRAGMA foreign_key_check').fetchall(), [])
        finally:
            legacy.close()

    def test_reopen_does_not_repeat_bootstrap_or_change_ids(self):
        before = self.db.execute('SELECT * FROM sync_identity ORDER BY tableName').fetchall()
        install(self.db)
        self.assertEqual(before, self.db.execute('SELECT * FROM sync_identity ORDER BY tableName').fetchall())
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='OPENING'"), 1)

    def test_two_phones_same_local_id_get_different_global_ids(self):
        second = sqlite3.connect(':memory:')
        try:
            prepare(second); seed(second); install(second)
            a = self.scalar("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'")
            b = second.execute("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'").fetchone()[0]
            self.assertNotEqual(a, b)
        finally:
            second.close()

    def test_sale_captures_delta_in_same_transaction(self):
        self.db.execute('UPDATE products SET currentStock=currentStock-5 WHERE id=1')
        self.assertEqual(self.scalar("SELECT delta FROM sync_stock_movements WHERE kind='DELTA'"), -5)
        self.db.rollback()
        self.assertEqual(self.scalar('SELECT currentStock FROM products WHERE id=1'), 100)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='DELTA'"), 0)

    def test_stock_review_detects_net_zero_changes_and_remote_changes(self):
        version=self.scalar("SELECT revision FROM sync_stock_revisions WHERE localKey='1'")
        self.db.execute('UPDATE products SET currentStock=currentStock-1 WHERE id=1')
        self.db.execute('UPDATE sync_journal_control SET suppressTracking=1 WHERE id=1')
        self.db.execute('UPDATE products SET currentStock=currentStock+1 WHERE id=1')
        self.assertEqual(self.scalar('SELECT currentStock FROM products WHERE id=1'),100)
        self.assertEqual(self.scalar("SELECT revision FROM sync_stock_revisions WHERE localKey='1'"),version+2)
        self.db.rollback()
        self.assertEqual(self.scalar("SELECT revision FROM sync_stock_revisions WHERE localKey='1'"),version)

    def test_price_edit_has_no_stock_movement(self):
        self.db.execute('UPDATE products SET sellingPricePaisa=1500 WHERE id=1')
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='DELTA'"), 0)
        self.assertEqual(self.scalar("SELECT revision FROM sync_identity WHERE tableName='products'"), 2)

    def test_delayed_ack_does_not_clear_new_edit(self):
        sync_id, rev = self.db.execute("SELECT syncId,revision FROM sync_identity WHERE tableName='products'").fetchone()
        self.db.execute('UPDATE products SET sellingPricePaisa=1500 WHERE id=1')
        self.db.execute('DELETE FROM sync_dirty WHERE syncId=? AND revision=?', (sync_id, rev))
        self.assertEqual(self.scalar('SELECT revision FROM sync_dirty WHERE syncId=?', (sync_id,)), rev + 1)

    def test_delete_keeps_tombstone_and_identity(self):
        row(self.db, 'customers', id=7, name='Customer')
        sync_id = self.scalar("SELECT syncId FROM sync_identity WHERE tableName='customers' AND localKey='7'")
        self.db.execute('DELETE FROM customers WHERE id=7')
        self.assertEqual(self.scalar('SELECT deleted FROM sync_dirty WHERE syncId=?', (sync_id,)), 1)
        self.assertEqual(self.scalar('SELECT deleted FROM sync_identity WHERE syncId=?', (sync_id,)), 1)

    def test_local_settings_do_not_enter_journal(self):
        for key in ['printer_mac', 'bill_sequence', 'cart:1', 'checkout:1', 'remote_print_enabled', 'shop_id', 'purchase:request:123']:
            row(self.db, 'settings', key=key, value='private')
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_identity WHERE tableName='settings'"), 0)
        row(self.db, 'settings', key='shop_name', value='Shop')
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_identity WHERE tableName='settings'"), 1)

    def test_remote_apply_does_not_echo_as_local_movement(self):
        self.db.execute('DELETE FROM sync_dirty')
        self.db.execute('UPDATE sync_journal_control SET suppressTracking=1 WHERE id=1')
        self.db.execute('UPDATE products SET currentStock=currentStock-3 WHERE id=1')
        self.db.execute('UPDATE sync_journal_control SET suppressTracking=0 WHERE id=1')
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty'), 0)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='DELTA'"), 0)

    def test_insert_product_captures_exactly_one_opening(self):
        row(self.db, 'products', id=2, categoryId=1, typeId=1, name='Tee', unit='Piece', currentStock=12.5)
        install(self.db)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='OPENING'"), 2)
        self.assertEqual(self.scalar("SELECT SUM(delta) FROM sync_stock_movements WHERE kind='OPENING'"), 112.5)

    def test_rollback_also_rolls_back_inserted_identity(self):
        row(self.db, 'customers', id=9, name='Uncommitted customer')
        self.db.rollback()
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_identity WHERE tableName='customers'"), 0)

    def test_reused_local_id_keeps_old_tombstone_and_allocates_new_identity(self):
        row(self.db, 'customers', id=7, name='Old customer')
        old = self.scalar("SELECT syncId FROM sync_identity WHERE tableName='customers' AND localKey='7'")
        self.db.execute('DELETE FROM customers WHERE id=7')
        row(self.db, 'customers', id=7, name='Different customer')
        new = self.scalar("SELECT syncId FROM sync_identity WHERE tableName='customers' AND localKey='7'")
        self.assertNotEqual(old, new)
        self.assertEqual(self.scalar('SELECT deleted FROM sync_dirty WHERE syncId=?', (old,)), 1)
        self.assertEqual(self.scalar('SELECT deleted FROM sync_dirty WHERE syncId=?', (new,)), 0)

    def test_product_id_reuse_opening_belongs_to_new_identity(self):
        old = self.scalar("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'")
        self.db.execute('DELETE FROM products WHERE id=1')
        row(self.db, 'products', id=1, categoryId=1, typeId=1, name='Different product', unit='Piece', currentStock=12)
        new = self.scalar("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'")
        self.assertNotEqual(old, new)
        self.assertEqual(self.scalar('SELECT delta FROM sync_stock_movements WHERE productSyncId=?', (new,)), 12)

    def test_three_thousand_products_bootstrap_is_idempotent(self):
        large = sqlite3.connect(':memory:')
        try:
            prepare(large); seed(large)
            for product_id in range(2, 3001):
                row(large, 'products', id=product_id, categoryId=1, typeId=1, name=f'Product {product_id}', unit='Piece', currentStock=product_id)
            install(large); install(large)
            self.assertEqual(large.execute("SELECT COUNT(*) FROM sync_identity WHERE tableName='products'").fetchone()[0], 3000)
            self.assertEqual(large.execute('SELECT COUNT(DISTINCT productSyncId) FROM sync_stock_movements').fetchone()[0], 3000)
            self.assertEqual(large.execute('PRAGMA foreign_key_check').fetchall(), [])
        finally:
            large.close()

    def test_pending_stock_movements_survive_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'shop.db'
            first = sqlite3.connect(path)
            prepare(first); seed(first); install(first)
            first.execute('UPDATE products SET currentStock=currentStock-5 WHERE id=1')
            first.commit(); first.close()
            second = sqlite3.connect(path)
            install(second)
            self.assertEqual(second.execute("SELECT delta FROM sync_stock_movements WHERE kind='DELTA'").fetchone()[0], -5)
            second.close()

    def test_product_requires_public_and_private_acknowledgements(self):
        record, revision = self.db.execute("SELECT syncId,revision FROM sync_dirty WHERE tableName='products'").fetchone()
        for operation in ('public-product','private-cost'):
            self.db.execute('INSERT INTO sync_transfer_projections VALUES(?,?,?,0)',(operation,record,revision))
        self.db.execute("UPDATE sync_transfer_projections SET acknowledged=1 WHERE operationId='public-product'")
        self.db.execute(ACK_SQL,('public-product',))
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty WHERE syncId=?',(record,)),1)
        self.db.execute("UPDATE sync_transfer_projections SET acknowledged=1 WHERE operationId='private-cost'")
        self.db.execute(ACK_SQL,('private-cost',))
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty WHERE syncId=?',(record,)),0)

    def test_projection_ack_keeps_a_later_sale_pending(self):
        record, revision = self.db.execute("SELECT syncId,revision FROM sync_dirty WHERE tableName='products'").fetchone()
        self.db.execute('INSERT INTO sync_transfer_projections VALUES(?,?,?,1)',('uploaded',record,revision))
        self.db.execute('UPDATE products SET currentStock=currentStock-1 WHERE id=1')
        self.db.execute(ACK_SQL,('uploaded',))
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty WHERE syncId=?',(record,)),0)
        self.assertEqual(self.db.execute("SELECT delta,acknowledged FROM sync_stock_movements WHERE kind='DELTA'").fetchone(),(-1,0))

    def test_projection_ack_keeps_a_later_metadata_edit_pending(self):
        record, revision = self.db.execute("SELECT syncId,revision FROM sync_dirty WHERE tableName='products'").fetchone()
        self.db.execute('INSERT INTO sync_transfer_projections VALUES(?,?,?,1)',('uploaded',record,revision))
        self.db.execute('UPDATE products SET purchasePricePaisa=500 WHERE id=1')
        self.db.execute(ACK_SQL,('uploaded',))
        self.assertGreater(self.scalar('SELECT revision FROM sync_dirty WHERE syncId=?',(record,)),revision)

    def test_local_product_worker_and_print_changes_do_not_queue_metadata(self):
        row(self.db,'workers',id=1,name='Worker')
        row(self.db,'bills',id=1,billNumber='000001')
        self.db.execute('DELETE FROM sync_dirty')
        self.db.execute("UPDATE products SET currentStock=99,imagePath='local.jpg',updatedAt=10 WHERE id=1")
        self.db.execute("UPDATE workers SET passwordHash='new',passwordSalt='salt',updatedAt=10 WHERE id=1")
        self.db.execute('UPDATE bills SET isPrinted=1,updatedAt=10 WHERE id=1')
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty'),0)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='DELTA'"),1)
        self.db.execute("UPDATE bills SET status='CANCELLED' WHERE id=1")
        self.db.execute('UPDATE workers SET maxDiscountPaisa=100 WHERE id=1')
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_dirty'),2)

    def test_reinstall_replaces_legacy_unfiltered_update_trigger(self):
        self.db.execute('DROP TRIGGER sync_track_products_update')
        self.db.execute("""CREATE TRIGGER sync_track_products_update AFTER UPDATE ON products BEGIN
            UPDATE sync_identity SET revision=revision+1 WHERE tableName='products'; END""")
        install(self.db)
        revision=self.scalar("SELECT revision FROM sync_identity WHERE tableName='products'")
        self.db.execute('UPDATE products SET currentStock=90,updatedAt=10 WHERE id=1')
        self.assertEqual(self.scalar("SELECT revision FROM sync_identity WHERE tableName='products'"),revision)

    def test_frozen_outbox_cannot_be_replaced_by_new_edit(self):
        self.db.execute("INSERT INTO sync_transfer_outbox VALUES('original','PUBLIC','record',1,'first','PENDING',0,NULL)")
        self.db.execute("INSERT OR IGNORE INTO sync_transfer_outbox VALUES('new','PUBLIC','record',2,'second','PENDING',0,NULL)")
        self.assertEqual(self.db.execute('SELECT operationId,body,localRevision FROM sync_transfer_outbox').fetchone(),('original','first',1))

    def test_interrupted_page_rolls_back_inbox_and_cursor(self):
        try:
            with self.db:
                self.db.execute("INSERT INTO sync_transfer_inbox VALUES('PUBLIC','op','record',1,100,0,'body','PENDING',NULL)")
                self.db.execute("INSERT INTO sync_transfer_cursors VALUES('PUBLIC',100,0,'op')")
                raise RuntimeError('Simulated interruption before commit')
        except RuntimeError:
            pass
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_transfer_inbox'),0)
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_transfer_cursors'),0)

    def test_new_transfer_schema_preserves_business_and_journal(self):
        before=self.db.execute('SELECT * FROM sync_identity').fetchall()
        self.db.execute("INSERT INTO sync_transfer_inbox VALUES('PUBLIC','op','record',1,100,0,'body','PENDING',NULL)")
        install(self.db)
        self.assertEqual(before,self.db.execute('SELECT * FROM sync_identity').fetchall())
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM sync_transfer_inbox'),1)
        self.assertEqual(self.scalar('SELECT currentStock FROM products WHERE id=1'),100)


def compile_sql(java, cache):
    global ACK_SQL
    modules = Path(cache) / 'caches/modules-2/files-2.1'
    def jar(path, name):
        return next((modules / path).rglob(name))
    stdlib = jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.20', 'kotlin-stdlib-2.0.20.jar')
    jars = [stdlib,
        jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.20', 'kotlin-compiler-embeddable-2.0.20.jar'),
        jar('org.jetbrains.kotlin/kotlin-script-runtime/2.0.20', 'kotlin-script-runtime-2.0.20.jar'),
        jar('org.jetbrains.kotlin/kotlin-reflect', 'kotlin-reflect-*.jar'),
        jar('org.jetbrains.intellij.deps/trove4j', 'trove4j-1.0.20200330.jar'),
        jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', 'kotlinx-coroutines-core-jvm-1.6.4.jar'),
        jar('org.jetbrains/annotations', 'annotations-13.0.jar')]
    output = ROOT / 'build/sync-journal-tests'
    output.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join(str(x) for x in jars)
    subprocess.run([java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-classpath', cp, '-d', str(output), str(ROOT / 'app/src/main/java/com/hardwareshop/pos/data/sync/SyncJournalSchema.kt'),
        str(ROOT / 'app/src/main/java/com/hardwareshop/pos/data/sync/SyncTransferSchema.kt'),
        str(ROOT / 'tools/ExportSyncJournal.kt')], check=True)
    generated = subprocess.check_output([java, '-cp', str(output) + os.pathsep + str(stdlib), 'ExportSyncJournalKt'], text=True)
    encoded_ack = subprocess.check_output([java, '-cp', str(output) + os.pathsep + str(stdlib), 'ExportSyncJournalKt','acknowledge'], text=True)
    ACK_SQL = base64.b64decode(encoded_ack.strip()).decode('utf-8')
    return [base64.b64decode(line).decode('utf-8') for line in generated.splitlines() if line]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--gradle-cache', required=True)
    args = parser.parse_args()
    SQL = compile_sql(args.java, args.gradle_cache)
    unittest.main(argv=['test_sync_journal'], verbosity=2)
