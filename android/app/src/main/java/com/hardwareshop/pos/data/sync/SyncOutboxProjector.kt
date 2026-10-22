package com.hardwareshop.pos.data.sync

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.database.AppDatabase
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshots local rows, global references and journal revisions in one transaction. */
@Singleton
class SyncOutboxProjector @Inject constructor(private val db: AppDatabase, private val transfer: SyncTransferStore) {
    data class Result(val prepared: Int, val waiting: Int)

    suspend fun prepare(member: FirestoreSyncTransport.Membership, limit: Int = 200): Result = db.withTransaction {
        require(limit in 1..500)
        val sql = db.openHelper.writableDatabase
        var prepared = 0
        var waiting = 0
        val allowed = SyncJournalSchema.tables.map { it.name }.filter {
            it !in (SyncFinancialBundle.entities - "credit_transactions")
        }.filter {
            member.actor.role == SyncProtocol.Role.OWNER ||
                (SyncWireSchema.byName.getValue(it).scope == SyncProtocol.Scope.PUBLIC && it !in SyncProtocol.ownerOnly)
        }.joinToString(",") { "'$it'" }
        val changes = sql.query("""SELECT d.syncId,d.tableName,d.localKey,d.revision,d.deleted
            FROM sync_dirty d WHERE d.tableName IN ($allowed)
              AND (d.tableName!='credit_transactions' OR EXISTS(SELECT 1 FROM credit_transactions c
                WHERE CAST(c.id AS TEXT)=d.localKey AND c.billId IS NULL))
              AND NOT EXISTS(SELECT 1 FROM sync_transfer_projections p
              WHERE p.syncId=d.syncId AND p.acknowledged=0)
            ORDER BY d.lastError IS NOT NULL,d.rowid LIMIT ?""", arrayOf<Any>(limit)).use { c ->
            buildList { while (c.moveToNext()) add(SyncJournal.Change(c.getString(0),c.getString(1),
                c.getString(2),c.getLong(3),c.getInt(4)!=0)) }
        }
        for (change in changes) {
            val spec = requireNotNull(SyncWireSchema.byName[change.table])
            if (member.actor.role != SyncProtocol.Role.OWNER &&
                (spec.scope == SyncProtocol.Scope.PRIVATE || change.table in SyncProtocol.ownerOnly)) {
                waiting++
                continue
            }
            val mutations: List<SyncProtocol.Mutation>
            try {
                var fields = if (change.deleted) emptyMap() else readFields(sql,change,spec,
                    member.actor.role==SyncProtocol.Role.WORKER)
                if(change.table=="products" && !change.deleted && member.actor.role==SyncProtocol.Role.WORKER &&
                    transfer.version(member.storeId,spec.scope,change.syncId)==0L) {
                    // Publish an unpriced proposal; the saved worker price and cost
                    // remain local. Only an owner may publish an approved price.
                    fields=fields+("sellingPricePaisa" to 0L)
                }
                val projections = mutableListOf(mutation(member,change.syncId,change.table,spec.scope,change.deleted,fields))
                if (change.table == "products" && member.actor.role == SyncProtocol.Role.OWNER) {
                    val cost = if (change.deleted) emptyMap() else sql.query(
                        "SELECT purchasePricePaisa FROM products WHERE id=?", arrayOf<Any>(change.localKey)).use {
                        check(it.moveToFirst())
                        mapOf("productSyncId" to change.syncId,"purchasePricePaisa" to it.getLong(0))
                    }
                    projections += mutation(member,"cost_${change.syncId}","product_costs",
                        SyncProtocol.Scope.PRIVATE,change.deleted,cost)
                }
                // Validate ALL projections before inserting any (public product and private cost).
                projections.forEach { SyncWireJson.encode(it) }
                if (member.actor.role == SyncProtocol.Role.WORKER) {
                    require(!change.deleted) { "Owner review required for deletion" }
                    if (change.table == "products") require(
                        projections.first().baseVersion == 0L && fields["sellingPricePaisa"] == 0L
                    ) { "Owner must approve catalogue changes" }
                }
                mutations = projections
            } catch (e: IllegalArgumentException) {
                sql.execSQL("UPDATE sync_dirty SET lastError=? WHERE syncId=?",
                    arrayOf<Any>(e.message?.take(250) ?: "Record requires review",change.syncId))
                waiting++
                continue
            }
            // Failure here rolls back the whole transaction, never leaving half a projection.
            mutations.forEach { mutation ->
                val frozen = transfer.freeze(member.storeId,mutation,change.revision)
                check(frozen.mutation == mutation && frozen.localRevision == change.revision)
                sql.execSQL("""INSERT INTO sync_transfer_projections(operationId,syncId,revision,acknowledged)
                    VALUES(?,?,?,0)""", arrayOf<Any>(mutation.operationId,change.syncId,change.revision))
            }
            sql.execSQL("UPDATE sync_dirty SET lastError=NULL WHERE syncId=?",arrayOf<Any>(change.syncId))
            prepared++
        }
        prepared += prepareStock(member, sql)
        Result(prepared,waiting)
    }

    /** Stock leaves the phone through two record types. The owner publishes one
     * baseline per established product (its absolute quantity at cutover); both
     * phones then upload post-link movements. Movements recorded before the
     * phone linked were retired at bind time and never replay.
     */
    private suspend fun prepareStock(member: FirestoreSyncTransport.Membership, sql: SupportSQLiteDatabase): Int {
        var prepared = 0
        if (member.actor.role == SyncProtocol.Role.OWNER) {
            // Baselines only for products with no pending local movements, so the
            // frozen quantity already equals the quantity the other phone will
            // reconstruct (baseline + every movement uploaded after it).
            sql.query("""SELECT i.syncId,p.currentStock FROM products p
                JOIN sync_identity i ON i.tableName='products' AND i.localKey=CAST(p.id AS TEXT)
                WHERE i.deleted=0
                  AND NOT EXISTS(SELECT 1 FROM sync_stock_movements m
                    WHERE m.productSyncId=i.syncId AND m.acknowledged=0)
                  AND NOT EXISTS(SELECT 1 FROM sync_transfer_outbox o WHERE o.recordId='base_'||i.syncId)
                  AND NOT EXISTS(SELECT 1 FROM sync_record_receipts r
                    WHERE r.scope='PUBLIC' AND r.recordId='base_'||i.syncId)
                LIMIT 200""").use { c ->
                while (c.moveToNext()) {
                    val syncId = c.getString(0)
                    val quantity = c.getDouble(1)
                    if (!quantity.isFinite() || quantity < 0) continue
                    val mutation = SyncProtocol.Mutation(UUID.randomUUID().toString(),"base_$syncId",
                        "stock_baselines",SyncProtocol.Scope.PUBLIC,member.actor.uid,
                        transfer.version(member.storeId,SyncProtocol.Scope.PUBLIC,"base_$syncId"),
                        false,mapOf("productSyncId" to syncId,"quantity" to quantity))
                    try {
                        SyncWireJson.encode(mutation)
                        val frozen = transfer.freeze(member.storeId,mutation,0)
                        require(frozen.mutation == mutation)
                        sql.execSQL("""INSERT INTO sync_transfer_projections(operationId,syncId,revision,acknowledged)
                            VALUES(?,'base_'||?,0,0)""", arrayOf<Any>(mutation.operationId,syncId))
                        prepared++
                    } catch(_: IllegalArgumentException) { continue }
                }
            }
        }
        sql.query("""SELECT m.movementId,m.productSyncId,m.delta,m.kind FROM sync_stock_movements m
            WHERE m.acknowledged=0
              AND NOT EXISTS(SELECT 1 FROM sync_transfer_outbox o WHERE o.recordId=m.movementId)
            LIMIT 200""").use { c ->
            while (c.moveToNext()) {
                val movementId = c.getString(0)
                val productSyncId = c.getString(1)
                val delta = c.getDouble(2)
                val kind = c.getString(3)
                if (!delta.isFinite() || kind !in listOf("OPENING","DELTA")) continue
                // The referenced product must already carry a global identity; an
                // unlinked product waits for its catalogue upload instead of failing here.
                val linked = sql.query("SELECT 1 FROM sync_identity WHERE tableName='products' AND syncId=?",
                    arrayOf<Any>(productSyncId)).use { it.moveToFirst() }
                if (!linked) continue
                val mutation = SyncProtocol.Mutation(UUID.randomUUID().toString(),movementId,
                    "stock_movements",SyncProtocol.Scope.PUBLIC,member.actor.uid,
                    transfer.version(member.storeId,SyncProtocol.Scope.PUBLIC,movementId),
                    false,mapOf("productSyncId" to productSyncId,"delta" to delta,"kind" to kind))
                // Validate before freezing; a rejected row (e.g. a worker's stock increase)
                // would otherwise poison this whole prepare transaction.
                try {
                    SyncWireJson.encode(mutation)
                    val frozen = transfer.freeze(member.storeId,mutation,0)
                    require(frozen.mutation == mutation)
                    sql.execSQL("""INSERT INTO sync_transfer_projections(operationId,syncId,revision,acknowledged)
                        VALUES(?,?,0,0)""", arrayOf<Any>(mutation.operationId,movementId))
                    prepared++
                } catch(_: IllegalArgumentException) { continue }
            }
        }
        return prepared
    }

    private suspend fun mutation(member: FirestoreSyncTransport.Membership, id: String, entity: String,
                                 scope: SyncProtocol.Scope, deleted: Boolean, fields: Map<String,Any?>) =
        SyncProtocol.Mutation(UUID.randomUUID().toString(),id,entity,scope,member.actor.uid,
            transfer.version(member.storeId,scope,id),deleted,fields)

    internal fun readFields(sql: SupportSQLiteDatabase, change: SyncJournal.Change,
                           spec: SyncWireSchema.Spec, preferAliases: Boolean = false): Map<String,Any?> {
        val table = SyncJournalSchema.tables.single { it.name == change.table }
        return sql.query("SELECT * FROM `${table.name}` WHERE `${table.primaryKey}`=?",
            arrayOf<Any>(change.localKey)).use { row ->
            require(row.moveToFirst()) { "Local record is missing; review deletion" }
            val pipeRecord = change.table in setOf("products", "bill_items") && row.getInt(row.getColumnIndexOrThrow("isPipe")) != 0
            val pipeFields = setOf("isPipe", "pipeLengthPricePaisa", "pipeFootPricePaisa", "pipeFeetPerLength", "pipeLengths", "pipeFeet")
            spec.fields.filter { pipeRecord || it !in pipeFields }.associateWith { field ->
                val index = row.getColumnIndexOrThrow(field)
                when {
                    row.isNull(index) -> null
                    change.table == "products" && field == "searchAliases" -> ProductPackagingWire.encode(
                        row.getString(index), row.getInt(row.getColumnIndexOrThrow("piecesPerBox")),
                        row.getString(row.getColumnIndexOrThrow("unit")))
                    change.table == "bill_items" && field == "productId" && row.getLong(index) == 0L ->
                        SyncWireSchema.MANUAL_PRODUCT_REFERENCE
                    change.table == "audit_logs" && field == "actorId" &&
                        row.getString(row.getColumnIndexOrThrow("actorRole")) != "WORKER" -> null
                    field in spec.references -> globalReference(sql,spec.references.getValue(field),row.getString(index),preferAliases)
                    else -> when (row.getType(index)) {
                        Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> row.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> row.getString(index)
                        else -> throw IllegalArgumentException("Unsupported value in $field")
                    }
                }
            }
        }
    }

    private fun globalReference(sql: SupportSQLiteDatabase, table: String, localKey: String, preferAliases: Boolean): String {
        if(preferAliases) sql.query("SELECT syncId FROM sync_foreign_identity WHERE tableName=? AND localKey=? ORDER BY syncId LIMIT 1",
            arrayOf<Any>(table,localKey)).use { if(it.moveToFirst()) return it.getString(0) }
        return sql.query("SELECT syncId FROM sync_identity WHERE tableName=? AND localKey=?",
            arrayOf<Any>(table,localKey)).use {
            require(it.moveToFirst()) { "Waiting for linked $table record" }
            it.getString(0)
        }
    }
}
