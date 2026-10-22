package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.sync.SyncTransferSchema

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SyncTransferSchema.statements().forEach { db.execSQL(it) }
    }
}
