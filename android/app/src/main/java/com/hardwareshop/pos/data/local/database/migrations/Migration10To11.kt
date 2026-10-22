package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.database.AliasIndexSchema

/** Add a derived alias index. Products, prices, bills, payments and stock are retained. */
val MIGRATION_10_11=object:Migration(10,11) {
    override fun migrate(db:SupportSQLiteDatabase) {
        AliasIndexSchema.tableStatements.forEach(db::execSQL)
        AliasIndexSchema.triggerStatements().forEach(db::execSQL)
        db.execSQL(AliasIndexSchema.backfillStatement())
    }
}
