package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest

/** Read-only review input, captured in one database transaction. It deliberately does
 * not include passwords, buying prices, customer contact details or receipt payloads.
 * Publishing/approving a two-device cutover is a separate operation.
 */
@Singleton
class SyncFirstLinkSnapshot @Inject constructor(private val db: AppDatabase) {
    data class Snapshot(val deviceId: String,val storeId: String,val products: List<SyncMergePlan.Product>,
                        val historyCounts: Map<String,Long>,val changeToken: String)

    suspend fun capture(): Snapshot = db.withTransaction {
        val sql=db.openHelper.readableDatabase
        val (device,store)=sql.query("SELECT deviceId,storeId FROM sync_journal_control WHERE id=1").use {
            check(it.moveToFirst());it.getString(0) to it.getString(1)
        }
        val products=sql.query("""SELECT i.syncId,i.revision,c.name,t.name,p.name,p.size,p.unit,p.currentStock,s.revision
            FROM products p JOIN sync_identity i ON i.tableName='products' AND i.localKey=CAST(p.id AS TEXT)
            JOIN categories c ON c.id=p.categoryId JOIN product_types t ON t.id=p.typeId
            JOIN sync_stock_revisions s ON s.localKey=CAST(p.id AS TEXT)
            WHERE p.isActive=1 AND i.deleted=0 ORDER BY c.name,t.name,p.name,p.size,p.id""").use { c -> buildList {
            while(c.moveToNext()) add(SyncMergePlan.Product(c.getString(0),device,c.getLong(1),c.getString(2),
                c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getDouble(7),c.getLong(8)))
        } }
        val counts=listOf("bills","bill_items","payments","credit_transactions","purchase_bills",
            "supplier_payments","sale_returns","customers").associateWith { table ->
            sql.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst();it.getLong(0) }
        }
        val digest=MessageDigest.getInstance("SHA-256")
        for(query in listOf(
            "SELECT syncId,revision,deleted FROM sync_identity ORDER BY syncId",
            "SELECT localKey,revision FROM sync_stock_revisions ORDER BY localKey",
            "SELECT scope,recordId,version FROM sync_transfer_versions ORDER BY scope,recordId")) {
            sql.query(query).use { c -> while(c.moveToNext()) {
                for(column in 0 until c.columnCount) {
                    val bytes=c.getString(column).toByteArray(Charsets.UTF_8)
                    digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                    digest.update(':'.code.toByte());digest.update(bytes)
                }
                digest.update('\n'.code.toByte())
            } }
        }
        Snapshot(device,store,products,counts,digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Also run under the eventual merge transaction, immediately before applying any
     * approved quantities. Equal quantities alone do not prove the snapshot is current.
     */
    suspend fun requireUnchanged(reviewed: Snapshot) = db.withTransaction {
        check(capture()==reviewed) { "This phone changed during review. Refresh both phones before confirming." }
    }
}
