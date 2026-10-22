package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManualProductIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var products: ProductRepository
    private lateinit var manual: ManualProductRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Shop")) }
        products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(),
            db.stockDao(), AuditRepository(db.auditLogDao(), session), session)
        manual = ManualProductRepository(db, products, session)
    }
    @After fun close() { db.close() }

    private suspend fun savedBill(name: String = "Loose fitting", price: Long = 11800): String {
        val id = db.billDao().insertBill(BillEntity(billNumber = java.util.UUID.randomUUID().toString(),
            subtotalPaisa = price, totalPaisa = price, paidPaisa = price, paymentMethod = "CASH", workerName = "Worker"))
        db.billDao().insertBillItem(BillItemEntity(billId = id, productId = 0, productName = name,
            productSize = "", productUnit = "Piece", quantity = 1.0, unitPricePaisa = price, itemTotalPaisa = price))
        return ManualProductRepository.key(db.billDao().getBillItems(id).single().id)
    }

    @Test fun onlySavedBillsAppearAndAcceptanceDoesNotRewriteIssuedBill() = runBlocking {
        assertTrue(manual.observeBills().first().isEmpty())
        val key = savedBill()
        val before = manual.observeBills().first().single()
        assertEquals(before.bill.id, before.manualItems.single().billId)
        assertTrue(db.productDao().getAllProducts().isEmpty())
        val id = manual.accept(key)!!
        val product = db.productDao().getProductById(id)!!
        assertEquals("Loose fitting", product.name)
        assertEquals(11800L, product.sellingPricePaisa)
        assertEquals(0.0, product.currentStock, 0.0)
        assertEquals(ManualProductRepository.CATEGORY, db.categoryDao().getCategoryById(product.categoryId)!!.name)
        val after = manual.observeBills().first().single()
        assertEquals(before.bill, after.bill)
        assertEquals(before.items, after.items)
        assertEquals("ACCEPTED", after.manualItems.single().status)
        assertTrue(products.searchProducts("Loose fitting").any { it.id == id })
        Unit
    }

    @Test fun repeatedConcurrentAcceptanceAndAnotherBillCannotDuplicateOrResetStock() = runBlocking {
        val key = savedBill("Washer", 1000)
        coroutineScope { List(3) { async { manual.accept(key) } }.awaitAll() }
        val original = db.productDao().getAllProducts().single()
        db.productDao().updateProduct(original.copy(currentStock = 5.0))
        manual.accept(savedBill("Washer", 1000))
        assertEquals(1, db.productDao().getAllProducts().size)
        assertEquals(5.0, db.productDao().getProductById(original.id)!!.currentStock, 0.0)
        assertEquals(2, manual.observeBills().first().size)
        Unit
    }

    @Test fun rejectionIsPermanentAndKeepsBillItemsAndTotals() = runBlocking {
        val key = savedBill("Washer", 1000)
        val before = manual.observeBills().first().single()
        manual.reject(key)
        try { manual.accept(key); fail("Accepted rejected item") } catch (_: IllegalArgumentException) { }
        val after = ManualProductRepository(db, products, session).observeBills().first().single()
        assertEquals("REJECTED", after.manualItems.single().status)
        assertEquals(before.bill, after.bill)
        assertEquals(before.items, after.items)
        assertTrue(db.productDao().getAllProducts().isEmpty())
        Unit
    }

    @Test fun ownerEditsOnlyProposedProductAndMustAcceptBeforeCatalogueChanges() = runBlocking {
        val key = savedBill("Washer", 1000)
        val before = manual.observeBills().first().single()
        manual.edit(key, "Special washer", "12.50")
        val review = manual.observeBills().first().single()
        assertEquals("Special washer", review.manualItems.single().name)
        assertEquals(1250L, review.manualItems.single().pricePaisa)
        assertEquals(before.items, review.items)
        assertTrue(db.productDao().getAllProducts().isEmpty())
        manual.accept(key)
        assertEquals("Special washer", db.productDao().getAllProducts().single().name)
        assertEquals(1250L, db.productDao().getAllProducts().single().sellingPricePaisa)
        assertEquals(before.bill, db.billDao().getAllBills().single())
        Unit
    }

    @Test fun failedAcceptanceKeepsPendingItemAndRollsBackCatalogueGroups() = runBlocking {
        val key = savedBill("Washer", 1000)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_manual_product BEFORE INSERT ON products BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { manual.accept(key); fail("Accepted despite failed insert") } catch (_: IllegalStateException) { }
        assertEquals("PENDING", manual.observeBills().first().single().manualItems.single().status)
        assertTrue(db.productDao().getAllProducts().isEmpty())
        assertTrue(db.categoryDao().getAllCategories().isEmpty())
        Unit
    }

    @Test fun workerCannotReadAcceptRejectOrEditOwnerReview() = runBlocking {
        val key = savedBill()
        session.setSession(UserSession.WorkerSession(1, "Worker", "worker", 0, true))
        try { manual.observeBills().first(); fail("Worker read reviews") } catch (_: SecurityException) { }
        try { manual.accept(key); fail("Worker accepted product") } catch (_: SecurityException) { }
        try { manual.reject(key); fail("Worker rejected product") } catch (_: SecurityException) { }
        try { manual.edit(key, "Changed", "10"); fail("Worker edited review") } catch (_: SecurityException) { }
        assertTrue(db.productDao().getAllProducts().isEmpty())
        Unit
    }
}
