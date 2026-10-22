package com.hardwareshop.pos.data.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Catalogue receiver only. Financial records wait for the atomic-bundle receiver.
 * Incoming identities get fresh local keys. A remote numeric Room ID is never used.
 */
@Singleton
class SyncCatalogueReceiver @Inject constructor(private val db: AppDatabase, private val transfer: SyncTransferStore,
    private val cataloguePolicy: com.hardwareshop.pos.data.remote.CloudCataloguePolicy? = null) {
    enum class Outcome { APPLIED, WAITING, CONFLICT, UNSUPPORTED }
    private val tables = setOf("categories","product_types","products","customers","suppliers","settings","workers")

    suspend fun apply(member: FirestoreSyncTransport.Membership, incoming: SyncTransferStore.Incoming): Outcome {
        val m=incoming.mutation
        SyncProtocol.validate(m)
        require(incoming.version == m.baseVersion+1)
        if (m.scope == SyncProtocol.Scope.PRIVATE) require(member.actor.role == SyncProtocol.Role.OWNER)
        if (m.entity !in tables && m.entity != "product_costs") return Outcome.UNSUPPORTED
        return try {
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                if (transfer.version(member.storeId,m.scope,m.recordId) >= incoming.version) {
                    transfer.applied(member.storeId,incoming)
                    return@withTransaction Outcome.APPLIED
                }
                val currentVersion=transfer.version(member.storeId,m.scope,m.recordId)
                if (currentVersion != m.baseVersion) throw Waiting("Waiting for an earlier record version")
                val businessId = if (m.entity == "product_costs")
                    m.fields["productSyncId"] as? String ?: m.recordId.removePrefix("cost_") else m.recordId
                sql.query("SELECT 1 FROM sync_dirty WHERE syncId=?",arrayOf<Any>(businessId)).use {
                    if (it.moveToFirst()) throw Review("Local changes are awaiting upload or owner review")
                }
                sql.query("SELECT 1 FROM sync_transfer_outbox WHERE scope=? AND recordId=?",
                    arrayOf<Any>(m.scope.name,m.recordId)).use {
                    if(it.moveToFirst()) throw Waiting("Waiting for upload acknowledgement")
                }
                val oldSuppression=sql.query("SELECT suppressTracking FROM sync_journal_control WHERE id=1").use {
                    check(it.moveToFirst()); it.getInt(0)
                }
                sql.execSQL("UPDATE sync_journal_control SET suppressTracking=1 WHERE id=1")
                try {
                    val useCloud = cataloguePolicy?.usesCloud(member.actor.uid) == true
                    if (m.entity == "product_costs") applyCost(sql,m,businessId,useCloud) else applyRow(sql,m,useCloud)
                    transfer.applied(member.storeId,incoming)
                } finally {
                    sql.execSQL("UPDATE sync_journal_control SET suppressTracking=? WHERE id=1",arrayOf<Any>(oldSuppression))
                }
                Outcome.APPLIED
            }
        } catch (e: Waiting) {
            transfer.defer(member.storeId,incoming,e.message.orEmpty())
            Outcome.WAITING
        } catch (e: Review) {
            transfer.defer(member.storeId,incoming,e.message.orEmpty(),conflict=true)
            Outcome.CONFLICT
        } catch (_: SQLiteConstraintException) {
            // Unique category/type/customer keys need an explicit merge, not REPLACE:
            // REPLACE would delete a local row and damage its historical references.
            transfer.defer(member.storeId,incoming,"A matching local record needs owner review",conflict=true)
            Outcome.CONFLICT
        }
    }

    private fun applyCost(sql: SupportSQLiteDatabase, m: SyncProtocol.Mutation, productId: String, useCloud: Boolean) {
        // Deleting an archived product's cost document must not erase historical costs.
        if (m.deleted) return
        val localId=(if (useCloud) SyncIdentityLookup.reference(sql,"products",productId) else resolve(sql,"products",productId))
            ?: throw Waiting("Waiting for product metadata")
        if (useCloud) requireCleanLocal(sql,"products",localId)
        val values=ContentValues().apply { put("purchasePricePaisa",(m.fields.getValue("purchasePricePaisa") as Number).toLong()) }
        if(sql.update("products",SQLiteDatabase.CONFLICT_ABORT,values,"id=?",arrayOf<Any>(localId))!=1)
            throw Review("Linked product is missing")
    }

    private fun applyRow(sql: SupportSQLiteDatabase, m: SyncProtocol.Mutation, useCloud: Boolean) {
        val spec=SyncWireSchema.byName.getValue(m.entity)
        val key=SyncJournalSchema.tables.single { it.name==m.entity }.primaryKey
        val localKey=if (useCloud) SyncIdentityLookup.reference(sql,m.entity,m.recordId) else resolve(sql,m.entity,m.recordId)
        // A previously matched foreign catalogue identity refers to the local
        // category/type without replacing that phone's metadata or historical keys.
        if (m.entity in setOf("categories","product_types","products")) {
            sql.query("SELECT 1 FROM sync_foreign_identity WHERE syncId=?",arrayOf<Any>(m.recordId)).use {
                if(it.moveToFirst()) {
                    if (!useCloud) return
                    requireCleanLocal(sql,m.entity,checkNotNull(localKey))
                }
            }
        }
        if (!m.deleted && m.entity=="settings" && localKey!=null && localKey!=m.fields["key"])
            throw Review("A setting identity cannot change its key")
        if (m.deleted) {
            if (localKey != null) {
                if (m.entity == "settings") sql.execSQL("DELETE FROM settings WHERE `key`=?",arrayOf<Any>(localKey))
                else sql.execSQL("UPDATE `${m.entity}` SET isActive=0 WHERE `$key`=?",arrayOf<Any>(localKey))
                sql.execSQL("UPDATE sync_identity SET deleted=1 WHERE syncId=?",arrayOf<Any>(m.recordId))
            }
            return
        }
        sql.query("SELECT deleted FROM sync_identity WHERE syncId=?",arrayOf<Any>(m.recordId)).use {
            if (it.moveToFirst() && it.getInt(0)!=0) throw Review("Deleted identity cannot be restored by sync")
        }
        val values=ContentValues()
        spec.fields.forEach { field ->
            val value=m.fields[field] ?: if (field in setOf("isPipe", "pipeLengthPricePaisa", "pipeFootPricePaisa", "pipeFeetPerLength", "pipeLengths")) 0L else if (field == "pipeFeet") 0.0 else null
            val mapped=if (value!=null && field in spec.references)
                SyncIdentityLookup.reference(sql,spec.references.getValue(field),value as String)
                    ?: throw Waiting("Waiting for linked ${spec.references.getValue(field)}") else value
            when (mapped) {
                null -> values.putNull(field)
                is String -> values.put(field,mapped)
                is Long -> values.put(field,mapped)
                is Int -> values.put(field,mapped)
                is Double -> values.put(field,mapped)
                else -> error("Unsupported database value")
            }
        }
        if (m.entity=="products") {
            val packaging = try {
                ProductPackagingWire.decode(values.getAsString("searchAliases"), values.getAsString("unit"))
            } catch (e: IllegalArgumentException) { throw Review(e.message ?: "Invalid box metadata") }
            values.put("searchAliases", packaging.aliases)
            values.put("piecesPerBox", packaging.piecesPerBox)
            val category=values.getAsString("categoryId")
            sql.query("SELECT categoryId FROM product_types WHERE id=?",arrayOf<Any>(values.getAsString("typeId"))).use {
                if (!it.moveToFirst() || it.getString(0)!=category) throw Review("Product type belongs to another category")
            }
        }
        if (localKey==null) {
            if(m.entity=="products") {
                val matchedExact=sql.query("""SELECT id FROM products WHERE categoryId=? AND typeId=?
                    AND name=? AND size=? AND unit=? AND isActive=1""",
                    arrayOf<Any>(values.getAsString("categoryId"),values.getAsString("typeId"),
                        values.getAsString("name"),values.getAsString("size"),values.getAsString("unit"))).use {
                    if(!it.moveToFirst()) null else it.getString(0).also { _ ->
                        if(it.moveToNext()) throw Review("Multiple matching products need owner review")
                    }
                }
                val matched=matchedExact ?: sql.query("SELECT id,name,size,unit FROM products WHERE categoryId=? AND typeId=? AND isActive=1",
                    arrayOf<Any>(values.getAsString("categoryId"),values.getAsString("typeId"))).use { rows ->
                    fun normalized(text:String)=com.hardwareshop.pos.util.ProductSearch.tokens(text)
                    val name=normalized(values.getAsString("name"));val size=normalized(values.getAsString("size"))
                    val unit=normalized(values.getAsString("unit"))
                    var found:String?=null
                    while(rows.moveToNext()) if(normalized(rows.getString(1))==name && normalized(rows.getString(2))==size && normalized(rows.getString(3))==unit) {
                        if(found!=null)throw Review("Multiple normalized variants need owner review")
                        found=rows.getString(0)
                    }
                    found
                }
                if(matched!=null) {
                    // Link invoice/stock references only; preserve local prices,
                    // costs, stock and historical product IDs.
                    sql.execSQL("INSERT INTO sync_foreign_identity(syncId,tableName,localKey) VALUES(?,?,?)",
                        arrayOf<Any>(m.recordId,m.entity,matched))
                    if (useCloud) {
                        requireCleanLocal(sql,m.entity,matched)
                        sql.update(m.entity,SQLiteDatabase.CONFLICT_ABORT,values,"`$key`=?",arrayOf<Any>(matched))
                    }
                    return
                }
            }
            if (m.entity in setOf("categories","product_types")) {
                val condition=if(m.entity=="product_types") " AND categoryId=?" else ""
                val args=if(m.entity=="product_types") arrayOf<Any>(values.getAsString("name"),values.getAsString("categoryId"))
                    else arrayOf<Any>(values.getAsString("name"))
                val matched=sql.query("SELECT id FROM `${m.entity}` WHERE name=? AND isActive=1$condition",args).use {
                    if(!it.moveToFirst()) null else it.getString(0).also { _ ->
                        if(it.moveToNext()) throw Review("Multiple matching catalogue records")
                    }
                }
                if(matched!=null) {
                    sql.execSQL("INSERT INTO sync_foreign_identity(syncId,tableName,localKey) VALUES(?,?,?)",
                        arrayOf<Any>(m.recordId,m.entity,matched))
                    if (useCloud) {
                        requireCleanLocal(sql,m.entity,matched)
                        sql.update(m.entity,SQLiteDatabase.CONFLICT_ABORT,values,"`$key`=?",arrayOf<Any>(matched))
                    }
                    return
                }
            }
            if(m.entity=="workers") {
                // Receive attribution/permissions, never credentials. A newly received
                // worker cannot log in here until the local owner sets a password.
                values.put("passwordHash","")
                values.put("passwordSalt","")
            }
            if (m.entity=="products") {
                values.put("purchasePricePaisa",0L)
                values.put("currentStock",0.0) // Stock is reconciled separately, never copied from a phone snapshot.
                values.putNull("imagePath")
            }
            val inserted=sql.insert(m.entity,SQLiteDatabase.CONFLICT_ABORT,values)
            val assigned=if(key=="key") values.getAsString("key") else inserted.toString()
            sql.execSQL("""INSERT INTO sync_identity(tableName,localKey,syncId,revision,deleted)
                VALUES(?,?,?,0,0)""",arrayOf<Any>(m.entity,assigned,m.recordId))
        } else {
            if (sql.update(m.entity,SQLiteDatabase.CONFLICT_ABORT,values,"`$key`=?",arrayOf<Any>(localKey))!=1)
                throw Review("Linked local record is missing")
        }
    }

    private fun requireCleanLocal(sql: SupportSQLiteDatabase, table: String, localKey: String) {
        sql.query("""SELECT 1 FROM sync_identity i WHERE i.tableName=? AND i.localKey=? AND
            (EXISTS(SELECT 1 FROM sync_dirty d WHERE d.syncId=i.syncId) OR
             EXISTS(SELECT 1 FROM sync_transfer_outbox o WHERE o.recordId=i.syncId OR o.recordId='cost_'||i.syncId))""",
            arrayOf<Any>(table,localKey)).use {
            if (it.moveToFirst()) {
                val untouchedStub = table == "products" && sql.query("""SELECT 1 FROM products WHERE id=?
                    AND sellingPricePaisa=0 AND purchasePricePaisa=0 AND searchAliases='' AND piecesPerBox=0
                    AND imagePath IS NULL""",arrayOf<Any>(localKey)).use { c -> c.moveToFirst() }
                if (untouchedStub || table in setOf("categories","product_types"))
                    throw Waiting("Waiting for initial catalogue uploads before using cloud details")
                throw Review("Unsent local catalogue edits need review before using cloud details")
            }
        }
    }

    private fun resolve(sql: SupportSQLiteDatabase, table: String, syncId: String): String? =
        sql.query("SELECT tableName,localKey FROM sync_identity WHERE syncId=?",arrayOf<Any>(syncId)).use {
            if(!it.moveToFirst()) null else {
                if(it.getString(0)!=table) throw Review("Identity belongs to a different record type")
                it.getString(1)
            }
        }

    private class Waiting(message: String): IllegalStateException(message)
    private class Review(message: String): IllegalStateException(message)
}
