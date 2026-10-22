package com.hardwareshop.pos.data.sync

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Durable round-robin retries. A missing parent or an unfinished opening-stock
 * review must not trap unrelated records behind the first page forever.
 */
@Singleton
class SyncInboxScanner @Inject constructor(private val db: AppDatabase,private val transfer: SyncTransferStore) {
    data class Page<T>(val stream: String,val from: Long,val through: Long,val rows: List<T>)

    suspend fun records(member: FirestoreSyncTransport.Membership,limit: Int=200): Page<SyncTransferStore.Incoming> =
        scan(member,"records",limit) { body,version -> SyncTransferStore.Incoming(SyncWireJson.decode(body),version) }

    suspend fun bundles(member: FirestoreSyncTransport.Membership,limit: Int=20): Page<SyncFinancialBundle.Manifest> =
        scan(member,"bundles",limit) { body,_ -> SyncBundleJson.decode(body) }

    private suspend fun <T> scan(member: FirestoreSyncTransport.Membership,stream: String,limit: Int,
                                decode: (String,Long)->T): Page<T> = db.withTransaction {
        require(limit in 1..500)
        val sql=db.openHelper.writableDatabase
        transfer.requireStore(sql,member.storeId)
        val start=sql.query("SELECT sequence FROM sync_scan_progress WHERE stream=?",arrayOf<Any>(stream)).use {
            if(it.moveToFirst()) it.getLong(0) else 0L
        }
        val table=if(stream=="records") "sync_transfer_inbox" else "sync_financial_bundles"
        val version=if(stream=="records") "version" else "0"
        val direction=if(stream=="bundles") "AND direction='IN'" else ""
        val privacy=if(member.actor.role==SyncProtocol.Role.WORKER) "AND scope='PUBLIC'" else ""
        var end=start
        val rows=sql.query("""SELECT rowid,body,$version FROM $table WHERE rowid>?
            AND state IN ('PENDING','WAITING') $direction $privacy ORDER BY rowid LIMIT ?""",
            arrayOf<Any>(start,limit)).use { c -> buildList {
            while(c.moveToNext()) { end=c.getLong(0);add(decode(c.getString(1),c.getLong(2))) }
        } }
        Page(stream,start,if(rows.isEmpty()) 0L else end,rows)
    }

    /** Call only after processing the whole page. An interrupted run replays it. */
    suspend fun complete(storeId: String,page: Page<*>) = db.withTransaction {
        require(page.stream in setOf("records","bundles"))
        val sql=db.openHelper.writableDatabase
        transfer.requireStore(sql,storeId)
        sql.execSQL("""INSERT OR REPLACE INTO sync_scan_progress(stream,sequence)
            SELECT ?,? WHERE COALESCE((SELECT sequence FROM sync_scan_progress WHERE stream=?),0)=?""",
            arrayOf<Any>(page.stream,page.through,page.stream,page.from))
    }
}
