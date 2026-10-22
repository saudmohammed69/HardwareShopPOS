package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.sync.SyncJournalSchema

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SyncJournalSchema.statements().forEach { db.execSQL(it) }
    }
}
