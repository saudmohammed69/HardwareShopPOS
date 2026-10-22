package com.hardwareshop.pos

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_4_5
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_8_9
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_2_3
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_3_4
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_5_6
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_6_7
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_7_8
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TradeMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    @Test fun fullAvailableMigrationChainPreservesStockDraftAndSearchIndex() {
        val name = "full-migration-${java.util.UUID.randomUUID()}"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO categories (id,name,displayOrder,isActive,createdAt,updatedAt) VALUES (1,'Hardware',0,1,1,1)")
            execSQL("INSERT INTO product_types (id,categoryId,name,displayOrder,isActive,createdAt,updatedAt) VALUES (1,1,'Screws',0,1,1,1)")
            execSQL("INSERT INTO products (id,categoryId,typeId,name,size,unit,sellingPricePaisa,purchasePricePaisa,gstRateBp,searchAliases,currentStock,minimumStock,imagePath,isActive,createdAt,updatedAt) VALUES (1,1,1,'Screw','20mm','Piece',125,85,1800,'pech',500,5,NULL,1,1,1)")
            execSQL("INSERT INTO settings (`key`,value,updatedAt) VALUES ('cart:owner','saved-cart',100)")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, false, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).apply {
            query("SELECT currentStock,sellingPricePaisa,purchasePricePaisa,piecesPerBox FROM products WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(500.0,it.getDouble(0),0.0)
                assertEquals(125L,it.getLong(1)); assertEquals(85L,it.getLong(2)); assertEquals(0,it.getInt(3))
            }
            query("SELECT value FROM settings WHERE `key`='cart:owner'").use {
                assertTrue(it.moveToFirst()); assertEquals("saved-cart",it.getString(0))
            }
            query("SELECT docid FROM products_fts WHERE products_fts MATCH 'Screw'").use {
                assertTrue(it.moveToFirst()); assertEquals(1L,it.getLong(0))
            }
            execSQL("UPDATE products SET name='Bolt' WHERE id=1")
            query("SELECT docid FROM products_fts WHERE products_fts MATCH 'Bolt'").use { assertTrue(it.moveToFirst()) }
            query("SELECT docid FROM products_fts WHERE products_fts MATCH 'Screw'").use { assertTrue(!it.moveToFirst()) }
            close()
        }
    }
    @Test fun boxMigrationPreservesExistingProductValues() {
        val name = "box-migration-${java.util.UUID.randomUUID()}"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO categories (id,name,displayOrder,isActive,createdAt,updatedAt) VALUES (1,'Hardware',0,1,1,1)")
            execSQL("INSERT INTO product_types (id,categoryId,name,displayOrder,isActive,createdAt,updatedAt) VALUES (1,1,'Screws',0,1,1,1)")
            execSQL("INSERT INTO products (id,categoryId,typeId,name,size,unit,sellingPricePaisa,purchasePricePaisa,gstRateBp,searchAliases,currentStock,minimumStock,imagePath,isActive,createdAt,updatedAt) VALUES (1,1,1,'Screw','20mm','Piece',125,0,0,'pech',500,0,NULL,1,1,1)")
            close()
        }
        // The sync journal intentionally contains auxiliary non-Room tables.
        helper.runMigrationsAndValidate(name, 9, false, MIGRATION_8_9).apply {
            query("SELECT piecesPerBox,sellingPricePaisa,currentStock,searchAliases FROM products WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)); assertEquals(125L,it.getLong(1))
                assertEquals(500.0,it.getDouble(2),0.0); assertEquals("pech",it.getString(3))
            }
            execSQL("DELETE FROM sync_dirty")
            execSQL("UPDATE products SET piecesPerBox=100 WHERE id=1")
            query("SELECT COUNT(*) FROM sync_dirty WHERE tableName='products'").use {
                assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0))
            }
            close()
        }
    }
    @Test fun migrationPreservesExistingWorkspaceAndValidatesAllRoomTables() {
        val name = "trade-migration-${java.util.UUID.randomUUID()}"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO settings (`key`,value,updatedAt) VALUES ('cart:owner','saved-cart',100)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).apply {
            query("SELECT value FROM settings WHERE `key`='cart:owner'").use {
                assertTrue(it.moveToFirst()); assertEquals("saved-cart", it.getString(0))
            }
            query("SELECT COUNT(*) FROM sale_returns").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            close()
        }
    }
}
