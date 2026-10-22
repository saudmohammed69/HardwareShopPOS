// data/local/database/migrations/Migration2To3.kt
package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `purchase_bill_items` (
                `id`                   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `purchaseBillId`       INTEGER NOT NULL,
                `productId`            INTEGER NOT NULL,
                `productName`          TEXT    NOT NULL,
                `productSize`          TEXT    NOT NULL,
                `productUnit`          TEXT    NOT NULL,
                `quantity`             REAL    NOT NULL,
                `purchasePricePaisa`   INTEGER NOT NULL,
                `gstRateBp`            INTEGER NOT NULL,
                `itemTotalPaisa`       INTEGER NOT NULL,
                `gstAmountPaisa`       INTEGER NOT NULL,
                `createdAt`            INTEGER NOT NULL,
                FOREIGN KEY(`purchaseBillId`)
                    REFERENCES `purchase_bills`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`productId`)
                    REFERENCES `products`(`id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS `index_purchase_bill_items_purchaseBillId`
            ON `purchase_bill_items` (`purchaseBillId`)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS `index_purchase_bill_items_productId`
            ON `purchase_bill_items` (`productId`)
        """.trimIndent())
    }
}