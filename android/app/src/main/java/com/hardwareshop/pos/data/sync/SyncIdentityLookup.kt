package com.hardwareshop.pos.data.sync

import androidx.sqlite.db.SupportSQLiteDatabase

/** Used only for references after a reviewed merge. Receiving an alias's metadata
 * must not silently replace the canonical product/customer chosen by the owner.
 */
internal object SyncIdentityLookup {
    fun reference(sql: SupportSQLiteDatabase,table: String,id: String): String? =
        sql.query("""SELECT tableName,localKey FROM sync_identity WHERE syncId=?
            UNION SELECT tableName,localKey FROM sync_foreign_identity WHERE syncId=?""",arrayOf<Any>(id,id)).use {
            if(!it.moveToFirst()) null else {
                check(it.getString(0)==table) { "Reference belongs to another record type" }
                val key=it.getString(1)
                check(!it.moveToNext()) { "A global reference has conflicting local mappings" }
                key
            }
        }
}
