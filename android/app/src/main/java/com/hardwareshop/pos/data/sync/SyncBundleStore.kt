package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncBundleStore @Inject constructor(private val db: AppDatabase,private val records: SyncTransferStore) {
    suspend fun readyToPublish(storeId: String,limit: Int=10): List<SyncFinancialBundle.Manifest> = db.withTransaction {
        require(limit in 1..100)
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        val pending=sql.query("SELECT body FROM sync_financial_bundles WHERE direction='OUT' AND state='PENDING' ORDER BY rowid LIMIT ?",
            arrayOf<Any>(limit)).use { c -> buildList { while(c.moveToNext()) add(SyncBundleJson.decode(c.getString(0))) } }
        pending.filter { bundle -> bundle.members.all { member ->
            sql.query("""SELECT 1 FROM sync_record_receipts WHERE scope=? AND operationId=? AND version=?
                UNION SELECT 1 FROM sync_transfer_projections WHERE operationId=? AND acknowledged=1
                UNION SELECT 1 FROM sync_transfer_inbox WHERE scope=? AND operationId=? AND version=? AND state='APPLIED'""",
                arrayOf<Any>(bundle.scope.name,member.operationId,member.version,member.operationId,
                    bundle.scope.name,member.operationId,member.version)).use { it.moveToFirst() }
        } }
    }

    suspend fun published(storeId: String,manifest: SyncFinancialBundle.Manifest) = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        sql.query("SELECT body FROM sync_financial_bundles WHERE direction='OUT' AND bundleId=?",arrayOf<Any>(manifest.bundleId)).use {
            check(it.moveToFirst() && SyncBundleJson.decode(it.getString(0))==manifest)
        }
        sql.execSQL("UPDATE sync_financial_bundles SET state='PUBLISHED',lastError=NULL WHERE direction='OUT' AND bundleId=?",
            arrayOf<Any>(manifest.bundleId))
    }

    suspend fun savePage(storeId: String,scope: SyncProtocol.Scope,deliveries: List<FirestoreSyncTransport.BundleDelivery>) = db.withTransaction {
        if(deliveries.isEmpty()) return@withTransaction
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        val comparator=compareBy<FirestoreSyncTransport.Cursor> { it.seconds }.thenBy { it.nanos }.thenBy { it.operationId }
        require(deliveries.map { it.cursor }.zipWithNext().all { (a,b) -> comparator.compare(a,b)<=0 })
        deliveries.forEach { d ->
            val m=d.manifest
            require(m.scope==scope && d.cursor.operationId==m.bundleId)
            require(d.cursor.seconds>=0 && d.cursor.nanos in 0..999_999_999)
            val body=SyncBundleJson.encode(m)
            SyncBundleJson.decode(body)
            sql.execSQL("""INSERT OR IGNORE INTO sync_financial_bundles
                (direction,bundleId,rootId,scope,body,state,lastError,seconds,nanos) VALUES('IN',?,?,?,?,'PENDING',NULL,?,?)""",
                arrayOf<Any>(m.bundleId,m.rootId,scope.name,body,d.cursor.seconds,d.cursor.nanos))
            sql.query("SELECT body,seconds,nanos FROM sync_financial_bundles WHERE direction='IN' AND bundleId=?",
                arrayOf<Any>(m.bundleId)).use {
                check(it.moveToFirst() && SyncBundleJson.decode(it.getString(0))==m && it.getLong(1)==d.cursor.seconds && it.getInt(2)==d.cursor.nanos) {
                    "A financial completion record changed after being saved"
                }
            }
        }
        var cursor=deliveries.last().cursor
        sql.query("SELECT seconds,nanos,bundleId FROM sync_bundle_cursors WHERE scope=?",arrayOf<Any>(scope.name)).use {
            if(it.moveToFirst()) {
                val previous=FirestoreSyncTransport.Cursor(it.getLong(0),it.getInt(1),it.getString(2))
                if(comparator.compare(previous,cursor)>0) cursor=previous
            }
        }
        sql.execSQL("INSERT OR REPLACE INTO sync_bundle_cursors(scope,seconds,nanos,bundleId) VALUES(?,?,?,?)",
            arrayOf<Any>(scope.name,cursor.seconds,cursor.nanos,cursor.operationId))
    }

    suspend fun cursor(storeId: String,scope: SyncProtocol.Scope): FirestoreSyncTransport.Cursor? = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        sql.query("SELECT seconds,nanos,bundleId FROM sync_bundle_cursors WHERE scope=?",arrayOf<Any>(scope.name)).use {
            if(it.moveToFirst()) FirestoreSyncTransport.Cursor(it.getLong(0),it.getInt(1),it.getString(2)) else null
        }
    }

    suspend fun incoming(storeId: String,limit: Int=20): List<SyncFinancialBundle.Manifest> = db.withTransaction {
        require(limit in 1..100)
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        sql.query("""SELECT body FROM sync_financial_bundles WHERE direction='IN' AND state IN ('PENDING','WAITING')
            ORDER BY seconds,nanos,bundleId LIMIT ?""",arrayOf<Any>(limit)).use { c ->
            buildList { while(c.moveToNext()) add(SyncBundleJson.decode(c.getString(0))) }
        }
    }

    suspend fun receipts(storeId: String,manifest: SyncFinancialBundle.Manifest): List<SyncProtocol.Receipt> = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        manifest.members.mapNotNull { member ->
            sql.query("SELECT body,version FROM sync_transfer_inbox WHERE scope=? AND operationId=?",
                arrayOf<Any>(manifest.scope.name,member.operationId)).use {
                if(!it.moveToFirst()) null else {
                    check(it.getLong(1)==member.version)
                    SyncProtocol.Receipt(SyncWireJson.decode(it.getString(0)),it.getLong(1))
                }
            }
        }
    }

    suspend fun result(storeId: String,manifest: SyncFinancialBundle.Manifest,outcome: SyncFinancialReceiver.Outcome) = db.withTransaction {
        val sql=db.openHelper.writableDatabase
        records.requireStore(sql,storeId)
        sql.execSQL("UPDATE sync_financial_bundles SET state=?,lastError=? WHERE direction='IN' AND bundleId=?",
            arrayOf<Any?>(outcome.name,if(outcome==SyncFinancialReceiver.Outcome.APPLIED) null else
                if(outcome==SyncFinancialReceiver.Outcome.WAITING) "Waiting for related records" else "Owner review required",manifest.bundleId))
    }
}
