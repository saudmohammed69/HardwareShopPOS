package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `supplier_payments` (`id` TEXT NOT NULL, `purchaseBillId` INTEGER NOT NULL, `amountPaisa` INTEGER NOT NULL, `method` TEXT NOT NULL, `reference` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`purchaseBillId`) REFERENCES `purchase_bills`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_supplier_payments_purchaseBillId` ON `supplier_payments` (`purchaseBillId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sale_returns` (`id` TEXT NOT NULL, `billId` INTEGER NOT NULL, `exchangeBillId` INTEGER, `totalPaisa` INTEGER NOT NULL, `discountPaisa` INTEGER NOT NULL, `taxPaisa` INTEGER NOT NULL, `creditReductionPaisa` INTEGER NOT NULL, `refundPaisa` INTEGER NOT NULL, `method` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`billId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`exchangeBillId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_returns_billId` ON `sale_returns` (`billId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_returns_exchangeBillId` ON `sale_returns` (`exchangeBillId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_returns_createdAt` ON `sale_returns` (`createdAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sale_return_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `returnId` TEXT NOT NULL, `billItemId` INTEGER NOT NULL, `quantity` REAL NOT NULL, `totalPaisa` INTEGER NOT NULL, `discountPaisa` INTEGER NOT NULL, `taxPaisa` INTEGER NOT NULL, `restock` INTEGER NOT NULL, FOREIGN KEY(`returnId`) REFERENCES `sale_returns`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`billItemId`) REFERENCES `bill_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_return_items_returnId` ON `sale_return_items` (`returnId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_return_items_billItemId` ON `sale_return_items` (`billItemId`)")
    }
}
