package com.hardwareshop.pos.data.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.database.AppDatabase
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Imports a verified complete invoice graph in ONE Room transaction. It never calls
 * createBill/receivePurchase: those operations would change stock a second time.
 * Stock has its own deduplicated movement stream and reviewed opening quantity.
 */
@Singleton
class SyncFinancialReceiver @Inject constructor(private val db: AppDatabase,private val transfer: SyncTransferStore) {
    enum class Outcome { APPLIED, WAITING, CONFLICT }

    suspend fun apply(member: FirestoreSyncTransport.Membership, manifest: SyncFinancialBundle.Manifest,
                      receipts: List<SyncProtocol.Receipt>): Outcome {
        SyncFinancialBundle.validate(manifest,receipts)
        if(manifest.scope==SyncProtocol.Scope.PRIVATE) require(member.actor.role==SyncProtocol.Role.OWNER)
        val incoming=receipts.map { SyncTransferStore.Incoming(SyncProtocol.canonical(it.mutation),it.version) }
        return applyRows(member,incoming)
    }

    /** Independent credit collections and audit/closing history do not belong to an
     * invoice bundle. Import the signed ledger entry, never a cached customer balance.
     */
    suspend fun applyStandalone(member: FirestoreSyncTransport.Membership,
                                incoming: SyncTransferStore.Incoming): Outcome {
        val m=incoming.mutation
        SyncProtocol.validate(m)
        require(incoming.version==m.baseVersion+1 && !m.deleted)
        require(m.entity in setOf("credit_transactions","stock_adjustments","audit_logs","day_closings"))
        if(m.scope==SyncProtocol.Scope.PRIVATE) require(member.actor.role==SyncProtocol.Role.OWNER)
        if(m.entity=="credit_transactions") require(m.fields["billId"]==null) { "Bill credit requires a complete invoice" }
        return applyRows(member,listOf(incoming.copy(mutation=SyncProtocol.canonical(m))))
    }

    private suspend fun applyRows(member: FirestoreSyncTransport.Membership,
                                  incoming: List<SyncTransferStore.Incoming>): Outcome {
        return try {
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                val previous=sql.query("SELECT suppressTracking FROM sync_journal_control WHERE id=1").use {
                    check(it.moveToFirst());it.getInt(0)
                }
                sql.execSQL("UPDATE sync_journal_control SET suppressTracking=1 WHERE id=1")
                try {
                    val rank=mapOf("bills" to 0,"purchase_bills" to 0,"bill_items" to 1,
                        "purchase_bill_items" to 1,"payments" to 2,"supplier_payments" to 2,
                        "credit_transactions" to 2,"sale_returns" to 3,"sale_return_items" to 4)
                    for (row in incoming.sortedBy { rank[it.mutation.entity] ?: 0 }) {
                        val m=row.mutation
                        val version=transfer.version(member.storeId,m.scope,m.recordId)
                        if(version>=row.version) {
                            if(resolve(sql,m.entity,m.recordId)==null) throw Review("Previously received financial record is missing")
                            transfer.applied(member.storeId,row)
                            continue
                        }
                        if(version!=m.baseVersion) throw Waiting("Waiting for an earlier invoice version")
                        sql.query("SELECT 1 FROM sync_dirty WHERE syncId=?",arrayOf<Any>(m.recordId)).use {
                            if(it.moveToFirst()) throw Review("This invoice also changed locally")
                        }
                        sql.query("SELECT 1 FROM sync_transfer_outbox WHERE scope=? AND recordId=?",
                            arrayOf<Any>(m.scope.name,m.recordId)).use {
                            if(it.moveToFirst()) throw Waiting("Waiting for upload acknowledgement")
                        }
                        applyRow(sql,m)
                        transfer.applied(member.storeId,row)
                    }
                } finally {
                    sql.execSQL("UPDATE sync_journal_control SET suppressTracking=? WHERE id=1",arrayOf<Any>(previous))
                }
                Outcome.APPLIED
            }
        } catch (e: Waiting) {
            deferAll(member,incoming,e.message.orEmpty(),false)
            Outcome.WAITING
        } catch (e: Review) {
            deferAll(member,incoming,e.message.orEmpty(),true)
            Outcome.CONFLICT
        } catch (_: SQLiteConstraintException) {
            deferAll(member,incoming,"Invoice conflicts with an existing record; owner review required",true)
            Outcome.CONFLICT
        }
    }

    private suspend fun deferAll(member: FirestoreSyncTransport.Membership, rows: List<SyncTransferStore.Incoming>,
                                 reason: String, conflict: Boolean) = db.withTransaction {
        rows.forEach { transfer.defer(member.storeId,it,reason,conflict) }
    }

    private fun applyRow(sql: SupportSQLiteDatabase,m: SyncProtocol.Mutation) {
        val spec=SyncWireSchema.byName.getValue(m.entity)
        val localKey=resolve(sql,m.entity,m.recordId)
        val values=ContentValues()
        // Include nullable fields even when omitted, so stale references cannot survive an update.
        spec.fields.forEach { field ->
            val value=m.fields[field] ?: if (field in setOf("isPipe", "pipeLengthPricePaisa", "pipeFootPricePaisa", "pipeFeetPerLength", "pipeLengths")) 0L else if (field == "pipeFeet") 0.0 else null
            val mapped=if(m.entity == "bill_items" && field == "productId" && value == SyncWireSchema.MANUAL_PRODUCT_REFERENCE) 0L
                else if(value!=null && field in spec.references)
                SyncIdentityLookup.reference(sql,spec.references.getValue(field),value as String)
                    ?: throw Waiting("Waiting for linked ${spec.references.getValue(field)}") else value
            when(mapped) {
                null -> values.putNull(field)
                is String -> values.put(field,mapped)
                is Long -> values.put(field,mapped)
                is Int -> values.put(field,mapped)
                is Double -> values.put(field,mapped)
                else -> error("Unsupported financial field")
            }
        }
        if(localKey!=null && m.entity in SyncProtocol.immutable) {
            sql.query("SELECT * FROM `${m.entity}` WHERE id=?",arrayOf<Any>(localKey)).use { row ->
                if(!row.moveToFirst()) throw Review("Linked financial record is missing")
                spec.fields.forEach { field ->
                    val index=row.getColumnIndexOrThrow(field)
                    if(row.getString(index)!=values.getAsString(field)) throw Review("An immutable financial entry changed")
                }
            }
            return
        }
        if(m.entity=="bills") {
            if(localKey==null) {
                val original=values.getAsString("billNumber")
                val collision=sql.query("SELECT 1 FROM bills WHERE billNumber=?",arrayOf<Any>(original)).use { it.moveToFirst() }
                if(collision) values.put("billNumber","$original-${m.recordId}")
                values.put("isPrinted",0) // Receiving a sale must never print it automatically.
            } else {
                // Preserve the local collision suffix across later cancellation/notes updates.
                sql.query("SELECT billNumber FROM bills WHERE id=?",arrayOf<Any>(localKey)).use {
                    if(!it.moveToFirst()) throw Review("Linked bill is missing")
                    values.put("billNumber",it.getString(0))
                }
            }
        }
        if(localKey==null) {
            // Required locally but private to the creating phone's owner account.
            if(m.entity=="credit_transactions") values.putNull("recordedByOwnerId")
            // Zero denotes a remote owner, not a credential identity on this phone.
            if(m.entity=="stock_adjustments") values.put("adjustedByOwnerId",0L)
            if(m.entity=="day_closings") values.put("closedByOwnerId",0L)
            if(m.entity=="audit_logs") values.putNull("entityId")
            val stringKey=m.entity in setOf("supplier_payments","sale_returns")
            val generated=if(stringKey) UUID.randomUUID().toString() else null
            if(generated!=null) values.put("id",generated)
            val inserted=sql.insert(m.entity,SQLiteDatabase.CONFLICT_ABORT,values)
            sql.execSQL("""INSERT INTO sync_identity(tableName,localKey,syncId,revision,deleted)
                VALUES(?,?,?,0,0)""",arrayOf<Any>(m.entity,generated ?: inserted.toString(),m.recordId))
        } else if(sql.update(m.entity,SQLiteDatabase.CONFLICT_ABORT,values,"id=?",arrayOf<Any>(localKey))!=1)
            throw Review("Linked financial record is missing")
    }

    private fun resolve(sql: SupportSQLiteDatabase,table: String,id: String): String? =
        sql.query("SELECT tableName,localKey FROM sync_identity WHERE syncId=?",arrayOf<Any>(id)).use {
            if(!it.moveToFirst()) null else {
                if(it.getString(0)!=table) throw Review("Financial identity belongs to another record type")
                it.getString(1)
            }
        }
    private class Waiting(message: String): IllegalStateException(message)
    private class Review(message: String): IllegalStateException(message)
}
