package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing products retain prices, units and stock; box conversion starts disabled. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN piecesPerBox INTEGER NOT NULL DEFAULT 0")
        com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount = true).forEach { db.execSQL(it) }
    }
}
