package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Applies incoming stock movements and the owner's stock baselines.
 * Movements are signed deltas applied exactly once through the journal's
 * deduplication; baselines are absolute quantities adopted only when this
 * phone has not already sold from the product. Opening history never replays:
 * movements recorded before this phone linked were retired at bind time.
 */
@Singleton
class SyncStockReceiver @Inject constructor(
    private val db: AppDatabase,
    private val journal: SyncJournal,
    private val transfer: SyncTransferStore
) {
    enum class Outcome { APPLIED, WAITING, CONFLICT }

    suspend fun apply(member: FirestoreSyncTransport.Membership, incoming: SyncTransferStore.Incoming): Outcome {
        val m = incoming.mutation
        SyncProtocol.validate(m)
        require(incoming.version == m.baseVersion + 1)
        if (m.entity !in setOf("stock_movements", "stock_baselines")) return Outcome.CONFLICT
        return try {
            db.withTransaction {
                if (transfer.version(member.storeId, m.scope, m.recordId) >= incoming.version) {
                    transfer.applied(member.storeId, incoming)
                    return@withTransaction Outcome.APPLIED
                }
                if (m.entity == "stock_baselines") applyBaseline(member, incoming)
                else applyMovement(member, incoming)
            }
        } catch (e: Waiting) {
            transfer.defer(member.storeId, incoming, e.message.orEmpty())
            Outcome.WAITING
        } catch (e: Review) {
            transfer.defer(member.storeId, incoming, e.message.orEmpty(), conflict = true)
            Outcome.CONFLICT
        } catch (e: IllegalStateException) {
            // One malformed record must never abort the whole sync run.
            transfer.defer(member.storeId, incoming, e.message.orEmpty().take(250), conflict = true)
            Outcome.CONFLICT
        } catch (e: IllegalArgumentException) {
            transfer.defer(member.storeId, incoming, e.message.orEmpty().take(250), conflict = true)
            Outcome.CONFLICT
        }
    }

    private suspend fun applyMovement(member: FirestoreSyncTransport.Membership,
                                      incoming: SyncTransferStore.Incoming): Outcome {
        val m = incoming.mutation
        val productSyncId = m.fields.getValue("productSyncId") as String
        val delta = (m.fields.getValue("delta") as Number).toDouble()
        // Own echo, duplicate delivery and prerequisite checks live in the journal;
        // a missing product maps to Waiting so the record retries after the catalogue.
        try {
            journal.applyStockDelta(m.recordId, productSyncId, delta)
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            if ("downloaded" in message || "missing" in message) throw Waiting(message)
            throw Review(message)
        } catch (e: IllegalArgumentException) {
            throw Review(e.message.orEmpty())
        }
        transfer.applied(member.storeId, incoming)
        return Outcome.APPLIED
    }

    private suspend fun applyBaseline(member: FirestoreSyncTransport.Membership,
                                      incoming: SyncTransferStore.Incoming): Outcome {
        val m = incoming.mutation
        // Upload acknowledgements already suppress this phone's echo by record version.
        // Another phone may use the same email account, so UID alone is not an echo.
        return db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val productSyncId = m.fields.getValue("productSyncId") as String
            val quantity = (m.fields.getValue("quantity") as Number).toDouble()
            require(quantity.isFinite() && quantity >= 0) { "Invalid baseline quantity" }
            val localId = SyncIdentityLookup.reference(sql, "products", productSyncId)
                ?: throw Waiting("Waiting for the product")
            sql.query("SELECT 1 FROM sync_dirty WHERE syncId=?", arrayOf<Any>(productSyncId)).use {
                if (it.moveToFirst()) throw Review("Product changes are awaiting upload or review")
            }
            sql.query("SELECT 1 FROM sync_transfer_outbox WHERE scope=? AND recordId=?",
                arrayOf<Any>(SyncProtocol.Scope.PUBLIC.name, productSyncId)).use {
                if (it.moveToFirst()) throw Waiting("Waiting for the product upload")
            }
            fun movementCount(where: String): Int = sql.query(
                "SELECT COUNT(*) FROM sync_stock_movements WHERE productSyncId=? AND $where",
                arrayOf<Any>(productSyncId)).use { it.moveToFirst(); it.getInt(0) }
            if (movementCount("acknowledged=0") > 0)
                throw Waiting("Waiting for local stock changes to upload first")
            if (movementCount("kind='DELTA' AND acknowledged=1") > 0)
                throw Review("Stock changed on this phone before the baseline arrived; review")
            if (quantity == 0.0) {
                // A zero baseline usually describes the publisher's stub copy of another
                // phone's product. Never zero out real local stock with it.
                transfer.applied(member.storeId, incoming)
                Outcome.APPLIED
            } else {
                val previous = sql.query("SELECT suppressTracking FROM sync_journal_control WHERE id=1").use {
                    check(it.moveToFirst()); it.getInt(0)
                }
                sql.execSQL("UPDATE sync_journal_control SET suppressTracking=1 WHERE id=1")
                try {
                    sql.execSQL("UPDATE products SET currentStock=? WHERE id=?",
                        arrayOf<Any>(quantity, localId))
                } finally {
                    sql.execSQL("UPDATE sync_journal_control SET suppressTracking=? WHERE id=1", arrayOf<Any>(previous))
                }
                transfer.applied(member.storeId, incoming)
                Outcome.APPLIED
            }
        }
    }

    private class Waiting(message: String) : IllegalStateException(message)
    private class Review(message: String) : IllegalStateException(message)
}
