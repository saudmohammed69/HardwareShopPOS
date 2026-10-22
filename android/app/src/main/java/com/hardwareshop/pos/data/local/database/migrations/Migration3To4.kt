package com.hardwareshop.pos.data.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Repair search indexes, including already upgraded version-3 installations. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS products_fts_insert")
        db.execSQL("DROP TRIGGER IF EXISTS products_fts_update")
        db.execSQL("DROP TRIGGER IF EXISTS products_fts_delete")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `products_fts` USING FTS4(`name` TEXT NOT NULL, `size` TEXT NOT NULL, `unit` TEXT NOT NULL, `searchAliases` TEXT NOT NULL, content=`products`)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_products_fts_BEFORE_UPDATE BEFORE UPDATE ON `products` BEGIN DELETE FROM `products_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_products_fts_BEFORE_DELETE BEFORE DELETE ON `products` BEGIN DELETE FROM `products_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_products_fts_AFTER_UPDATE AFTER UPDATE ON `products` BEGIN INSERT INTO `products_fts`(`docid`, `name`, `size`, `unit`, `searchAliases`) VALUES (NEW.`rowid`, NEW.`name`, NEW.`size`, NEW.`unit`, NEW.`searchAliases`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_products_fts_AFTER_INSERT AFTER INSERT ON `products` BEGIN INSERT INTO `products_fts`(`docid`, `name`, `size`, `unit`, `searchAliases`) VALUES (NEW.`rowid`, NEW.`name`, NEW.`size`, NEW.`unit`, NEW.`searchAliases`); END")
        db.execSQL("INSERT INTO products_fts(products_fts) VALUES('rebuild')")
    }
}
