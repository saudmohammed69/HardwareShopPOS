package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Durable identities and stock deduplication. Network transport is deliberately separate. */
@Singleton
class SyncJournal @Inject constructor(private val db: AppDatabase) {
    data class Change(val syncId: String, val table: String, val localKey: String,
                      val revision: Long, val deleted: Boolean)

    suspend fun linkedStore(): String? = db.withTransaction {
        db.openHelper.readableDatabase.query("SELECT storeId FROM sync_journal_control WHERE id=1").use {
            if(it.moveToFirst()) it.getString(0).takeIf(String::isNotBlank) else null
        }
    }

    /** Local change status: journal rows, frozen uploads in flight, and records
     * parked for owner review (rejected uploads or conflicting deliveries). */
    suspend fun pendingCounts(): Triple<Int, Int, Int> = db.withTransaction {
        val sql = db.openHelper.readableDatabase
        fun count(query: String): Int = sql.query(query).use { it.moveToFirst(); it.getInt(0) }
        Triple(
            count("SELECT COUNT(*) FROM sync_dirty"),
            count("SELECT COUNT(*) FROM sync_transfer_outbox WHERE state='PENDING'"),
            count("SELECT COUNT(*) FROM sync_transfer_outbox WHERE state IN ('REVIEW','CONFLICT')") +
                count("SELECT COUNT(*) FROM sync_transfer_inbox WHERE state='CONFLICT'")
        )
    }

    suspend fun blockedReasons(): List<String> = db.withTransaction {
        db.openHelper.readableDatabase.query("""SELECT tableName || ': ' || lastError
            FROM sync_dirty WHERE lastError IS NOT NULL
            UNION ALL SELECT 'Download: ' || lastError FROM sync_transfer_inbox
              WHERE state IN ('WAITING','CONFLICT') AND lastError IS NOT NULL
            UNION ALL SELECT 'Upload: ' || lastError FROM sync_transfer_outbox
              WHERE lastError IS NOT NULL LIMIT 5""").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    suspend fun hasWaitingDownloads(): Boolean = db.withTransaction {
        db.openHelper.readableDatabase.query("""SELECT
            EXISTS(SELECT 1 FROM sync_transfer_inbox WHERE state IN ('PENDING','WAITING')) OR
            EXISTS(SELECT 1 FROM sync_financial_bundles WHERE direction='IN' AND state IN ('PENDING','WAITING'))""").use {
            it.moveToFirst(); it.getInt(0) != 0
        }
    }

    /** Retry only duplicate category/type conflicts handled by the exact-name
     * alias receiver. Financial and product conflicts remain untouched. */
    suspend fun retryCatalogueParents() = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        val operations=sql.query("""SELECT operationId,body FROM sync_transfer_inbox
            WHERE state='CONFLICT' AND lastError='A matching local record needs owner review'""").use { c ->
            buildList { while(c.moveToNext()) {
                val mutation=SyncWireJson.decode(c.getString(1))
                if(mutation.entity in setOf("categories","product_types","products")) add(c.getString(0))
            } }
        }
        operations.forEach { sql.execSQL("UPDATE sync_transfer_inbox SET state='PENDING',lastError=NULL WHERE operationId=?",arrayOf<Any>(it)) }
    }

    suspend fun pending(limit: Int = 200): List<Change> = db.withTransaction {
        require(limit in 1..500)
        db.openHelper.writableDatabase.query("""SELECT syncId,tableName,localKey,revision,deleted
            FROM sync_dirty ORDER BY tableName,localKey LIMIT ?""", arrayOf<Any>(limit)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Change(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getInt(4) != 0))
            }
        }
    }

    /** A delayed acknowledgement must not clear a newer edit made during upload. */
    suspend fun acknowledge(change: Change) = db.withTransaction {
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM sync_dirty WHERE syncId=? AND revision=?",
            arrayOf<Any>(change.syncId, change.revision))
    }

    /** Must only be called after successful server-side membership verification. */
    suspend fun bindVerifiedStore(storeId: String) = db.withTransaction {
        require(storeId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid Shop ID" }
        val sql = db.openHelper.writableDatabase
        val existing = sql.query("SELECT storeId FROM sync_journal_control WHERE id=1").use {
            check(it.moveToFirst()); it.getString(0)
        }
        check(existing.isEmpty() || existing == storeId) {
            "This database is already linked to another shop. Its data cannot be mixed with a different shop."
        }
        if (existing.isEmpty()) {
            // Stock cutover: movements recorded before this phone linked are history,
            // not deltas to replay on the other phone. Post-link sales stay pending.
            sql.execSQL("UPDATE sync_stock_movements SET acknowledged=1 WHERE acknowledged=0")
        }
        sql.execSQL("UPDATE sync_journal_control SET storeId=? WHERE id=1", arrayOf<Any>(storeId))
    }

    /** Registers a foreign global identity against its freshly allocated LOCAL Room key.
     * Never inserts a remote auto-increment ID directly into a business table.
     */
    suspend fun mapIncoming(syncId: String, table: String, localKey: String) = db.withTransaction {
        val spec = requireNotNull(SyncJournalSchema.tables.singleOrNull { it.name == table })
        require(syncId.matches(Regex("[A-Za-z0-9_-]{16,100}")))
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT 1 FROM `${spec.name}` WHERE `${spec.primaryKey}`=?", arrayOf<Any>(localKey)).use {
            check(it.moveToFirst()) { "Cannot map a missing business record" }
        }
        sql.query("SELECT tableName,localKey,deleted FROM sync_identity WHERE syncId=?", arrayOf<Any>(syncId)).use {
            if (it.moveToFirst()) check(it.getString(0) == table && it.getString(1) == localKey && it.getInt(2) == 0) {
                "Global identity collides with a different local record"
            }
        }
        sql.query("SELECT tableName,localKey FROM sync_foreign_identity WHERE syncId=?", arrayOf<Any>(syncId)).use {
            if (it.moveToFirst()) {
                check(it.getString(0) == table && it.getString(1) == localKey) { "A sync identity cannot be remapped" }
            } else {
                sql.execSQL("INSERT INTO sync_foreign_identity(syncId,tableName,localKey) VALUES(?,?,?)",
                    arrayOf<Any>(syncId,table,localKey))
            }
        }
    }

    /** Applies one foreign DELTA exactly once. Opening-stock reconciliation is a separate
     * bootstrap step and must never be passed to this function as a second opening balance.
     */
    suspend fun applyStockDelta(movementId: String, productSyncId: String, delta: Double): Boolean = db.withTransaction {
        require(movementId.matches(Regex("[A-Za-z0-9_-]{16,120}")))
        require(productSyncId.matches(Regex("[A-Za-z0-9_-]{16,100}")))
        require(delta.isFinite())
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT productSyncId,delta FROM sync_applied_movements WHERE movementId=?", arrayOf<Any>(movementId)).use {
            if (it.moveToFirst()) {
                check(it.getString(0) == productSyncId && it.getDouble(1) == delta) { "Stock movement content changed" }
                return@withTransaction false
            }
        }
        // Outbound movements are already reflected in local stock. A listener echo must not reapply them.
        val own = sql.query("SELECT productSyncId,delta FROM sync_stock_movements WHERE movementId=?", arrayOf<Any>(movementId)).use {
            if (!it.moveToFirst()) false else {
                check(it.getString(0) == productSyncId && it.getDouble(1) == delta)
                true
            }
        }
        if (!own) {
            val localId = checkNotNull(SyncIdentityLookup.reference(sql,"products",productSyncId)) {
                "Product must be downloaded before its stock movement"
            }
            val current = sql.query("SELECT currentStock FROM products WHERE id=?", arrayOf<Any>(localId)).use {
                check(it.moveToFirst()) { "Stock movement product is missing" }; it.getDouble(0)
            }
            require((current + delta).isFinite()) { "Invalid stock result" }
            val previous = sql.query("SELECT suppressTracking FROM sync_journal_control WHERE id=1").use {
                check(it.moveToFirst()); it.getInt(0)
            }
            sql.execSQL("UPDATE sync_journal_control SET suppressTracking=1 WHERE id=1")
            try {
                sql.execSQL("UPDATE products SET currentStock=currentStock+? WHERE id=?", arrayOf<Any>(delta,localId))
            } finally {
                sql.execSQL("UPDATE sync_journal_control SET suppressTracking=? WHERE id=1", arrayOf<Any>(previous))
            }
        }
        sql.execSQL("INSERT INTO sync_applied_movements(movementId,productSyncId,delta) VALUES(?,?,?)",
            arrayOf<Any>(movementId,productSyncId,delta))
        true
    }
}
