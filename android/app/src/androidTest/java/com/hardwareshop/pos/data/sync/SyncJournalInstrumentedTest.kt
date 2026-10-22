package com.hardwareshop.pos.data.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncJournalInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var journal: SyncJournal

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    SyncJournalSchema.statements().forEach { db.execSQL(it) }
                }
            }).build()
        journal = SyncJournal(db)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "Plumbing"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Elbow"))
        db.productDao().insertProduct(ProductEntity(id=1,categoryId=category,typeId=type,
            name="PVC Elbow",unit="Piece",sellingPricePaisa=1500,currentStock=100.0))
        Unit
    }

    @After fun teardown() { db.close() }

    @Test fun simultaneousSalesAndDuplicateDelivery() = runBlocking {
        db.productDao().decrementStock(1, 5.0)
        journal.mapIncoming("remote_product_000001", "products", "1")
        assertTrue(journal.applyStockDelta("remote_movement_00001", "remote_product_000001", -3.0))
        assertFalse(journal.applyStockDelta("remote_movement_00001", "remote_product_000001", -3.0))
        assertEquals(92.0, db.productDao().getProductById(1)!!.currentStock, 0.0)
        Unit
    }

    @Test fun unknownProductDoesNotConsumeMovement() = runBlocking {
        try {
            journal.applyStockDelta("remote_movement_00002", "remote_product_000002", -3.0)
            fail("Missing product must leave delivery retryable")
        } catch (_: IllegalStateException) { }
        journal.mapIncoming("remote_product_000002", "products", "1")
        assertTrue(journal.applyStockDelta("remote_movement_00002", "remote_product_000002", -3.0))
        assertEquals(97.0, db.productDao().getProductById(1)!!.currentStock, 0.0)
        Unit
    }

    @Test fun delayedAcknowledgementPreservesNewerEdit() = runBlocking {
        val before = journal.pending().single { it.table == "products" }
        db.productDao().updateProduct(db.productDao().getProductById(1)!!.copy(sellingPricePaisa=1600))
        journal.acknowledge(before)
        assertTrue(journal.pending().single { it.table == "products" }.revision > before.revision)
        Unit
    }

    @Test fun cannotMixTwoStores() = runBlocking {
        journal.bindVerifiedStore("shop-one")
        journal.bindVerifiedStore("shop-one")
        try {
            journal.bindVerifiedStore("shop-two")
            fail("Store switching would leak/mix records")
        } catch (_: IllegalStateException) { }
        Unit
    }
}
