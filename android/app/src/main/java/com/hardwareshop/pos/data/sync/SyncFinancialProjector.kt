package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.database.AppDatabase
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Freezes one complete invoice graph. Unchanged entries reuse accepted receipts;
 * new/changed rows receive immutable operations. The manifest publishes only after
 * every listed operation has been acknowledged by the server.
 */
@Singleton
class SyncFinancialProjector @Inject constructor(private val db: AppDatabase,private val store: SyncTransferStore,
                                                private val rowProjector: SyncOutboxProjector) {
    suspend fun prepare(member: FirestoreSyncTransport.Membership,limit: Int=10): Int {
        require(limit in 1..20)
        val roots=db.withTransaction {
            val sql=db.openHelper.writableDatabase
            val names=SyncFinancialBundle.entities.joinToString(",") { "'$it'" }
            sql.query("""SELECT d.tableName,d.localKey FROM sync_dirty d WHERE d.deleted=0 AND d.tableName IN ($names)
                AND NOT EXISTS(SELECT 1 FROM sync_transfer_projections p WHERE p.syncId=d.syncId AND p.acknowledged=0)
                ORDER BY d.lastError IS NOT NULL,d.rowid LIMIT 500""").use { c ->
                buildSet {
                    while(c.moveToNext() && size<limit) root(sql,c.getString(0),c.getString(1))?.let {
                        if(it.first=="bills" || member.actor.role==SyncProtocol.Role.OWNER) add(it)
                    }
                }.toList()
            }
        }
        var prepared=0
        for ((table,key) in roots) {
            try { if(prepareRoot(member,table,key)) prepared++ }
            catch(e: IllegalArgumentException) {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL("UPDATE sync_dirty SET lastError=? WHERE tableName=? AND localKey=?",
                        arrayOf<Any>(e.message?.take(250) ?: "Invoice needs review",table,key))
                }
            }
        }
        return prepared
    }

    private suspend fun prepareRoot(member: FirestoreSyncTransport.Membership,table: String,key: String): Boolean = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        val first=change(sql,table,key)
        sql.query("SELECT 1 FROM sync_financial_bundles WHERE direction='OUT' AND rootId=? AND state!='PUBLISHED'",
            arrayOf<Any>(first.syncId)).use { if(it.moveToFirst()) return@withTransaction false }
        val keys=mutableListOf(table to key)
        fun append(child: String,column: String,parent: String) {
            sql.query("SELECT id FROM `$child` WHERE `$column`=? ORDER BY id",arrayOf<Any>(parent)).use {
                while(it.moveToNext()) keys+=child to it.getString(0)
            }
        }
        if(table=="bills") {
            append("bill_items","billId",key);append("payments","billId",key);append("credit_transactions","billId",key)
            append("sale_returns","billId",key)
            keys.filter { it.first=="sale_returns" }.toList().forEach { append("sale_return_items","returnId",it.second) }
        } else {
            append("purchase_bill_items","purchaseBillId",key);append("supplier_payments","purchaseBillId",key)
        }
        require(keys.size in 2..2000) { "Invoice has missing items or exceeds the supported sync size" }
        val changes=keys.map { change(sql,it.first,it.second) }
        for(c in changes) sql.query("SELECT 1 FROM sync_transfer_outbox WHERE recordId=?",arrayOf<Any>(c.syncId)).use {
            if(it.moveToFirst()) return@withTransaction false
        }
        // A child-only change (supplier payment/return) still needs a new root operation
        // to identify the new complete graph; the financial values remain unchanged.
        sql.execSQL("UPDATE sync_identity SET revision=revision+1 WHERE syncId=?",arrayOf<Any>(first.syncId))
        sql.execSQL("""INSERT OR REPLACE INTO sync_dirty(syncId,revision,deleted,tableName,localKey,lastError)
            SELECT syncId,revision,deleted,tableName,localKey,NULL FROM sync_identity WHERE syncId=?""",
            arrayOf<Any>(first.syncId))
        val snapshots=keys.map { change(sql,it.first,it.second) }
        val fresh=mutableListOf<Pair<SyncJournal.Change,SyncProtocol.Mutation>>()
        val receipts=snapshots.map { c ->
            val spec=SyncWireSchema.byName.getValue(c.table)
            val old=store.accepted(member.storeId,spec.scope,c.syncId)
            val dirty=sql.query("SELECT 1 FROM sync_dirty WHERE syncId=?",arrayOf<Any>(c.syncId)).use { it.moveToFirst() }
            if(!dirty && old!=null) old else {
                var fields=rowProjector.readFields(sql,c,spec,member.actor.role==SyncProtocol.Role.WORKER)
                if(c.table=="bills" && old!=null) fields=fields+("billNumber" to old.mutation.fields["billNumber"])
                val m=SyncProtocol.Mutation(UUID.randomUUID().toString(),c.syncId,c.table,spec.scope,member.actor.uid,
                    store.version(member.storeId,spec.scope,c.syncId),false,fields)
                fresh+=c to m
                SyncProtocol.Receipt(m,m.baseVersion+1)
            }
        }
        val manifest=SyncFinancialBundle.manifest(first.syncId,receipts)
        fresh.forEach { (c,m) ->
            val frozen=store.freeze(member.storeId,m,c.revision)
            check(frozen.mutation==m)
            sql.execSQL("INSERT INTO sync_transfer_projections(operationId,syncId,revision,acknowledged) VALUES(?,?,?,0)",
                arrayOf<Any>(m.operationId,c.syncId,c.revision))
        }
        sql.execSQL("""INSERT INTO sync_financial_bundles(direction,bundleId,rootId,scope,body,state,lastError,seconds,nanos)
            VALUES('OUT',?,?,?,?,'PENDING',NULL,0,0)""",arrayOf<Any>(manifest.bundleId,manifest.rootId,
            manifest.scope.name,SyncBundleJson.encode(manifest)))
        true
    }

    private fun change(sql: SupportSQLiteDatabase,table: String,key: String): SyncJournal.Change =
        sql.query("SELECT syncId,revision,deleted FROM sync_identity WHERE tableName=? AND localKey=?",arrayOf<Any>(table,key)).use {
            require(it.moveToFirst() && it.getInt(2)==0) { "Missing invoice identity" }
            SyncJournal.Change(it.getString(0),table,key,it.getLong(1),false)
        }

    private fun root(sql: SupportSQLiteDatabase,table: String,key: String): Pair<String,String>? {
        if(table in setOf("bills","purchase_bills")) return table to key
        val query=when(table) {
            "bill_items","payments","credit_transactions","sale_returns" -> "SELECT billId FROM `$table` WHERE id=?"
            "sale_return_items" -> "SELECT r.billId FROM sale_return_items i JOIN sale_returns r ON r.id=i.returnId WHERE i.id=?"
            "purchase_bill_items","supplier_payments" -> "SELECT purchaseBillId FROM `$table` WHERE id=?"
            else -> return null
        }
        return sql.query(query,arrayOf<Any>(key)).use {
            if(!it.moveToFirst() || it.isNull(0)) null else
                (if(table in setOf("purchase_bill_items","supplier_payments")) "purchase_bills" else "bills") to it.getString(0)
        }
    }
}
