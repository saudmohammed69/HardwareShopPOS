package com.hardwareshop.pos

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_10_11
import com.hardwareshop.pos.util.ProductSearch
import org.junit.*
import org.junit.Assert.*

class RegionalAliasMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),AppDatabase::class.java)
    @Test fun migrationRetainsPricesStockBillsAndBuildsIndexedRegionalAliases() {
        val name="regional-alias-migration"
        helper.createDatabase(name,10).apply {
            execSQL("INSERT INTO categories VALUES(1,'PVC',0,1,1,1)")
            execSQL("INSERT INTO product_types VALUES(1,1,'Pipe',0,1,1,1)")
            execSQL("INSERT INTO products(id,categoryId,typeId,name,size,unit,sellingPricePaisa,purchasePricePaisa,gstRateBp,searchAliases,piecesPerBox,currentStock,minimumStock,imagePath,isActive,createdAt,updatedAt,isPipe,pipeLengthPricePaisa,pipeFootPricePaisa,pipeFeetPerLength) VALUES(1,1,1,'Water Tap','1 inch','Piece',41234,30000,1800,'bend,ನಳ್ಳಿ,नल [[pos-variant:YWJj]]',0,9.0,0,NULL,1,1,1,0,0,0,0)")
            execSQL("INSERT INTO bills(id,billNumber,customerId,customerName,customerPhone,workerId,workerName,subtotalPaisa,discountPaisa,discountNote,gstEnabled,gstAmountPaisa,totalPaisa,paymentMethod,paidPaisa,creditPaisa,status,cancellationReason,shopGstNumber,customerGstNumber,notes,isPrinted,createdAt,updatedAt) VALUES(1,'HISTORY',NULL,'','',NULL,'Owner',10000,0,'',0,0,10000,'CASH',10000,0,'ACTIVE','','','','',0,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name,11,false,MIGRATION_10_11).apply {
            query("SELECT sellingPricePaisa,purchasePricePaisa,currentStock,name FROM products WHERE id=1").use {
                assertTrue(it.moveToFirst());assertEquals(41234L,it.getLong(0));assertEquals(30000L,it.getLong(1));assertEquals(9.0,it.getDouble(2),0.0);assertEquals("Water Tap",it.getString(3))
            }
            query("SELECT totalPaisa FROM bills WHERE id=1").use {assertTrue(it.moveToFirst());assertEquals(10000L,it.getLong(0))}
            query("SELECT alias FROM product_aliases ORDER BY alias").use {
                val aliases=mutableListOf<String>();while(it.moveToNext())aliases.add(it.getString(0))
                assertEquals(setOf("bend","ನಳ್ಳಿ","नल"),aliases.toSet())
            }
            query("EXPLAIN QUERY PLAN SELECT * FROM product_aliases WHERE normalizedAlias='bend'").use {
                assertTrue(it.moveToFirst());assertTrue(it.getString(3).contains("index_product_aliases_normalizedAlias"))
            }
            execSQL("UPDATE products SET searchAliases='ನೀರು ನಳ್ಳಿ, पानी का नल' WHERE id=1")
            query("SELECT COUNT(*) FROM product_aliases WHERE productId=1").use {assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0))}
            close()
        }
    }
    @Test fun combiningMarksRemainPartOfRegionalTokensAndAliasesMatchCanonicalProducts() {
        assertEquals(listOf("ನಳ್ಳಿ"),ProductSearch.tokens("ನಳ್ಳಿ"))
        assertEquals(listOf("पानी"),ProductSearch.tokens("पानी"))
        assertNotNull(ProductSearch.score(ProductSearch.tokens("ನಳ್ಳಿ"),"Water Tap 1 inch","ನಳ್ಳಿ, पानी का नल"))
        assertNotNull(ProductSearch.score(ProductSearch.tokens("पानी"),"Water Tap 1 inch","ನಳ್ಳಿ, पानी का नल"))
    }
}
