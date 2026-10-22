package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Durable transport state. Each method participates in Room's transaction executor. */
@Singleton
class SyncTransferStore @Inject constructor(private val db: AppDatabase) {
    data class Queued(val mutation: SyncProtocol.Mutation, val localRevision: Long)
    data class Incoming(val mutation: SyncProtocol.Mutation, val version: Long)

    internal fun requireStore(sql: SupportSQLiteDatabase, storeId: String) {
        require(storeId.isNotBlank())
        sql.query("SELECT storeId FROM sync_journal_control WHERE id=1").use {
            check(it.moveToFirst() && it.getString(0) == storeId) { "This database is not linked to the requested shop" }
        }
    }

    /** Freeze before network IO. Subsequent edits remain in sync_dirty until this
     * immutable payload is acknowledged; a retry always uses the original operation ID.
     */
    suspend fun freeze(storeId: String, mutation: SyncProtocol.Mutation, localRevision: Long): Queued = db.withTransaction {
        require(localRevision >= 0)
        val sql = db.openHelper.writableDatabase
        requireStore(sql, storeId)
        val body = SyncWireJson.encode(mutation)
        sql.execSQL("""INSERT OR IGNORE INTO sync_transfer_outbox
            (operationId,scope,recordId,localRevision,body,state,attempts,lastError)
            VALUES(?,?,?,?,?,'PENDING',0,NULL)""",
            arrayOf<Any>(mutation.operationId,mutation.scope.name,mutation.recordId,localRevision,body))
        sql.query("SELECT body,localRevision FROM sync_transfer_outbox WHERE scope=? AND recordId=?",
            arrayOf<Any>(mutation.scope.name,mutation.recordId)).use {
            check(it.moveToFirst()) { "Operation ID was already used for another record" }
            Queued(SyncWireJson.decode(it.getString(0)), it.getLong(1))
        }
    }

    suspend fun pending(storeId: String, limit: Int = 100): List<Queued> = db.withTransaction {
        require(limit in 1..500)
        val sql = db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.query("SELECT body,localRevision FROM sync_transfer_outbox WHERE state='PENDING' ORDER BY rowid LIMIT ?",
            arrayOf<Any>(limit)).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Queued(SyncWireJson.decode(cursor.getString(0)),cursor.getLong(1)))
        } }
    }

    suspend fun acknowledge(storeId: String, receipt: SyncProtocol.Receipt) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        requireStore(sql,storeId)
        val mutation = receipt.mutation
        check(receipt.version == mutation.baseVersion + 1)
        val queued = sql.query("SELECT body,localRevision FROM sync_transfer_outbox WHERE operationId=?",
            arrayOf<Any>(mutation.operationId)).use {
            if (!it.moveToFirst()) return@withTransaction
            check(SyncWireJson.decode(it.getString(0)) == mutation) { "Receipt differs from queued operation" }
            it.getLong(1)
        }
        putVersion(sql,mutation.scope,mutation.recordId,receipt.version)
        putReceipt(sql,receipt)
        sql.execSQL("DELETE FROM sync_transfer_outbox WHERE operationId=?", arrayOf<Any>(mutation.operationId))
        sql.execSQL("UPDATE sync_transfer_projections SET acknowledged=1 WHERE operationId=?",
            arrayOf<Any>(mutation.operationId))
        // A product has public metadata and a private cost. Both must finish before
        // clearing its journal revision; an intervening local edit remains pending.
        sql.execSQL(SyncTransferSchema.completeProjectionSql,
            arrayOf<Any>(mutation.operationId))
        if (mutation.entity == "stock_movements") {
            sql.execSQL("UPDATE sync_stock_movements SET acknowledged=1 WHERE movementId=?",
                arrayOf<Any>(mutation.recordId))
        }
        check(queued >= 0)
    }

    suspend fun conflict(storeId: String, queued: Queued, remote: SyncProtocol.Record) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        requireStore(sql,storeId)
        check(remote.recordId == queued.mutation.recordId && remote.scope == queued.mutation.scope)
        // Retain both sides; the app must offer an owner-reviewed resolution.
        val remoteMutation = queued.mutation.copy(authorUid=remote.authorUid,baseVersion=remote.version,
            deleted=remote.deleted,fields=remote.fields)
        sql.execSQL("INSERT OR REPLACE INTO sync_transfer_conflicts(operationId,remoteBody,reason) VALUES(?,?,?)",
            arrayOf<Any>(queued.mutation.operationId,SyncWireJson.encode(remoteMutation),"Changed on another device"))
        sql.execSQL("UPDATE sync_transfer_outbox SET state='CONFLICT',lastError=? WHERE operationId=?",
            arrayOf<Any>("Owner review required",queued.mutation.operationId))
    }

    suspend fun needsReview(storeId: String,queued: Queued,reason: String) = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.execSQL("UPDATE sync_transfer_outbox SET state='REVIEW',lastError=? WHERE operationId=?",
            arrayOf<Any>(reason.take(250),queued.mutation.operationId))
    }

    suspend fun saveInboxPage(storeId: String, scope: SyncProtocol.Scope,
                             deliveries: List<FirestoreSyncTransport.Delivery>, advanceCursor: Boolean = true) = db.withTransaction {
        if (deliveries.isEmpty()) return@withTransaction
        val sql = db.openHelper.writableDatabase
        requireStore(sql,storeId)
        deliveries.forEach { delivery ->
            val receipt=delivery.receipt
            check(receipt.mutation.scope == scope && delivery.cursor.operationId == receipt.mutation.operationId)
            check(receipt.version == receipt.mutation.baseVersion + 1)
            require(delivery.cursor.seconds >= 0 && delivery.cursor.nanos in 0..999_999_999)
            sql.execSQL("""INSERT OR IGNORE INTO sync_transfer_inbox
                (scope,operationId,recordId,version,seconds,nanos,body,state,lastError)
                VALUES(?,?,?,?,?,?,?,'PENDING',NULL)""", arrayOf<Any>(scope.name,receipt.mutation.operationId,
                receipt.mutation.recordId,receipt.version,delivery.cursor.seconds,delivery.cursor.nanos,
                SyncWireJson.encode(receipt.mutation)))
            sql.query("SELECT body,version,seconds,nanos FROM sync_transfer_inbox WHERE scope=? AND operationId=?",
                arrayOf<Any>(scope.name,receipt.mutation.operationId)).use {
                check(it.moveToFirst())
                check(SyncWireJson.decode(it.getString(0)) == SyncProtocol.canonical(receipt.mutation) &&
                    it.getLong(1) == receipt.version && it.getLong(2) == delivery.cursor.seconds &&
                    it.getInt(3) == delivery.cursor.nanos) { "An incoming operation changed after being recorded" }
            }
        }
        // Direct dependency fetches are not a complete page; they must not skip the
        // intervening history by advancing the pagination checkpoint.
        if(!advanceCursor) return@withTransaction
        val comparator = compareBy<FirestoreSyncTransport.Cursor> { it.seconds }.thenBy { it.nanos }.thenBy { it.operationId }
        check(deliveries.map { it.cursor }.zipWithNext().all { (a,b) -> comparator.compare(a,b) <= 0 }) {
            "Incoming page is not in server order"
        }
        var cursor = deliveries.last().cursor
        sql.query("SELECT seconds,nanos,operationId FROM sync_transfer_cursors WHERE scope=?",arrayOf<Any>(scope.name)).use {
            if (it.moveToFirst()) {
                val existing = FirestoreSyncTransport.Cursor(it.getLong(0),it.getInt(1),it.getString(2))
                if (comparator.compare(existing,cursor) > 0) cursor = existing
            }
        }
        // The cursor advances only after the entire page is durable. Applying business
        // rows can then wait for missing parents without discarding an incoming record.
        sql.execSQL("INSERT OR REPLACE INTO sync_transfer_cursors(scope,seconds,nanos,operationId) VALUES(?,?,?,?)",
            arrayOf<Any>(scope.name,cursor.seconds,cursor.nanos,cursor.operationId))
    }

    suspend fun cursor(storeId: String, scope: SyncProtocol.Scope): FirestoreSyncTransport.Cursor? = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.query("SELECT seconds,nanos,operationId FROM sync_transfer_cursors WHERE scope=?",arrayOf<Any>(scope.name)).use {
            if (it.moveToFirst()) FirestoreSyncTransport.Cursor(it.getLong(0),it.getInt(1),it.getString(2)) else null
        }
    }

    suspend fun incoming(storeId: String, limit: Int = 200): List<Incoming> = db.withTransaction {
        require(limit in 1..500)
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.query("""SELECT body,version FROM sync_transfer_inbox WHERE state IN ('PENDING','WAITING')
            ORDER BY state='WAITING',seconds,nanos,operationId LIMIT ?""",arrayOf<Any>(limit)).use { c ->
            buildList { while (c.moveToNext()) add(Incoming(SyncWireJson.decode(c.getString(0)),c.getLong(1))) }
        }
    }

    /** Called within the receiver's SAME Room transaction as its business writes. */
    suspend fun applied(storeId: String, incoming: Incoming) = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        val m=incoming.mutation
        sql.query("SELECT body,version FROM sync_transfer_inbox WHERE scope=? AND operationId=?",
            arrayOf<Any>(m.scope.name,m.operationId)).use {
            check(it.moveToFirst() && SyncWireJson.decode(it.getString(0)) == m && it.getLong(1) == incoming.version)
        }
        putVersion(sql,m.scope,m.recordId,incoming.version)
        putReceipt(sql,SyncProtocol.Receipt(m,incoming.version))
        sql.execSQL("UPDATE sync_transfer_inbox SET state='APPLIED',lastError=NULL WHERE scope=? AND operationId=?",
            arrayOf<Any>(m.scope.name,m.operationId))
    }

    suspend fun defer(storeId: String, incoming: Incoming, reason: String, conflict: Boolean = false) = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.execSQL("""UPDATE sync_transfer_inbox SET state=?,lastError=? WHERE scope=? AND operationId=? AND state!='APPLIED'""",
            arrayOf<Any>(if(conflict) "CONFLICT" else "WAITING",reason.take(250),incoming.mutation.scope.name,incoming.mutation.operationId))
    }

    suspend fun version(storeId: String, scope: SyncProtocol.Scope, recordId: String): Long = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.query("SELECT version FROM sync_transfer_versions WHERE scope=? AND recordId=?",arrayOf<Any>(scope.name,recordId)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    suspend fun accepted(storeId: String, scope: SyncProtocol.Scope, recordId: String): SyncProtocol.Receipt? = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        requireStore(sql,storeId)
        sql.query("SELECT body,version FROM sync_record_receipts WHERE scope=? AND recordId=?",arrayOf<Any>(scope.name,recordId)).use {
            if(it.moveToFirst()) SyncProtocol.Receipt(SyncWireJson.decode(it.getString(0)),it.getLong(1)) else null
        }
    }

    private fun putReceipt(sql: SupportSQLiteDatabase, receipt: SyncProtocol.Receipt) {
        val m=receipt.mutation
        sql.execSQL("""INSERT OR REPLACE INTO sync_record_receipts(scope,recordId,version,operationId,body)
            SELECT ?,?,?,?,? WHERE ? >= COALESCE((SELECT version FROM sync_record_receipts WHERE scope=? AND recordId=?),0)""",
            arrayOf<Any>(m.scope.name,m.recordId,receipt.version,m.operationId,SyncWireJson.encode(m),receipt.version,m.scope.name,m.recordId))
    }

    private fun putVersion(sql: SupportSQLiteDatabase, scope: SyncProtocol.Scope, recordId: String, version: Long) {
        sql.execSQL("""INSERT OR REPLACE INTO sync_transfer_versions(scope,recordId,version)
            VALUES(?,?,MAX(?,COALESCE((SELECT version FROM sync_transfer_versions WHERE scope=? AND recordId=?),0)))""",
            arrayOf<Any>(scope.name,recordId,version,scope.name,recordId))
    }
}
