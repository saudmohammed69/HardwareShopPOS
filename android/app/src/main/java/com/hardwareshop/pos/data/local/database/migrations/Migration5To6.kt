package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_outbox (
                recordKey TEXT NOT NULL PRIMARY KEY,
                entityType TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                lastError TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_state ON sync_outbox(state)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_updatedAt ON sync_outbox(updatedAt)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_checkpoint (
                id INTEGER NOT NULL PRIMARY KEY,
                storeId TEXT NOT NULL,
                lastServerTimeMs INTEGER NOT NULL DEFAULT 0,
                lastRunAt INTEGER NOT NULL DEFAULT 0,
                lastError TEXT
            )
        """.trimIndent())
    }
}
