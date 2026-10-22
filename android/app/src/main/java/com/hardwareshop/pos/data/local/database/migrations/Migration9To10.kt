package com.hardwareshop.pos.data.local.database.migrations
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("isPipe", "pipeLengthPricePaisa", "pipeFootPricePaisa", "pipeFeetPerLength").forEach {
            db.execSQL("ALTER TABLE products ADD COLUMN $it INTEGER NOT NULL DEFAULT 0")
        }
        listOf("isPipe", "pipeLengths", "pipeFootPricePaisa").forEach {
            db.execSQL("ALTER TABLE bill_items ADD COLUMN $it INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE bill_items ADD COLUMN pipeFeet REAL NOT NULL DEFAULT 0")
        com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount = true, includePipe = true).forEach { db.execSQL(it) }
    }
}
