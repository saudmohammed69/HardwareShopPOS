package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.WarrantyRepository
import com.hardwareshop.pos.util.SessionHolder
import com.hardwareshop.pos.util.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class WarrantyIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var repository: WarrantyRepository
    private var productId: Long = 0
    private var otherProductId: Long = 0

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Shop")) }
        repository = WarrantyRepository(db, session)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "Tools"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Drill"))
        productId = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "Drill Model 123",
            size = "10 mm", unit = "Piece", sellingPricePaisa = 300000, purchasePricePaisa = 220000, currentStock = 7.0))
        otherProductId = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "Drill Model 456",
            size = "13 mm", unit = "Piece", sellingPricePaisa = 500000, currentStock = 3.0))
    }

    @After fun cleanup() { db.close() }

    private fun draft(serial: String = "DRILL-001") = WarrantyRepository.Draft(productId = productId, serial = serial,
        customerName = "Test Customer", customerPhone = "9876543210", saleDate = LocalDate.now().minusMonths(1).toString(), warrantyMonths = "12")

    @Test fun saveDuplicateEditAndServiceRetainStockPricesAndBills() = runBlocking {
        val productBefore = db.productDao().getProductById(productId)!!
        val saved = repository.save(draft())
        assertEquals(productBefore, db.productDao().getProductById(productId))
        assertEquals(1, repository.observe().first().size)
        assertEquals(2, repository.observeProducts().first().size)
        rejects { repository.save(draft("drill-001")) }
        val edited = repository.save(draft().copy(customerName = "Corrected Customer"), saved)
        assertNotEquals(saved.revision, edited.revision)
        rejects { repository.save(draft(), saved) }
        val serviced = repository.addService(edited, LocalDate.now().toString(), "Motor stopped", "Replaced motor under warranty")
        assertEquals(1, serviced.services.size)
        assertEquals("Motor stopped", repository.snapshot().single().services.single().problem)
        rejects { repository.addService(edited, LocalDate.now().toString(), "Retry", "Retry") }
        val afterEdit = repository.save(draft().copy(warrantyMonths = "24"), serviced)
        assertEquals(serviced.services, afterEdit.services)
        assertEquals(productBefore, db.productDao().getProductById(productId))
        assertTrue(db.stockDao().getAllAdjustments().isEmpty())
        assertNull(db.billDao().getBillByNumber("000001"))
        // A serial on another saved product is a distinct register entry.
        repository.save(draft("drill-001").copy(productId = otherProductId))
        assertEquals(2, repository.snapshot().size)
    }

    @Test fun billLinkMustExistBeActiveAndContainActualProduct() = runBlocking {
        rejects { repository.save(draft().copy(billNumber = "missing")) }
        val billId = db.billDao().insertBill(BillEntity(billNumber = "000017", subtotalPaisa = 300000, totalPaisa = 300000,
            paymentMethod = "CASH", paidPaisa = 300000))
        rejects { repository.save(draft().copy(billNumber = "000017")) }
        db.billDao().insertBillItems(listOf(BillItemEntity(billId = billId, productId = productId, productName = "Drill",
            productSize = "10 mm", productUnit = "Piece", quantity = 1.0, unitPricePaisa = 300000, itemTotalPaisa = 300000)))
        val billBefore = db.billDao().getBillById(billId)
        val itemsBefore = db.billDao().getBillItems(billId)
        val saved = repository.save(draft().copy(billNumber = "000017"))
        assertEquals(billId, saved.billId)
        assertEquals("000017", saved.billNumber)
        assertEquals(billBefore, db.billDao().getBillById(billId))
        assertEquals(itemsBefore, db.billDao().getBillItems(billId))
        rejects { repository.save(draft("OTHER").copy(productId = otherProductId, billNumber = "000017")) }
        val cancelledId = db.billDao().insertBill(BillEntity(billNumber = "000018", subtotalPaisa = 300000, totalPaisa = 300000,
            paymentMethod = "CASH", status = "CANCELLED"))
        db.billDao().insertBillItems(listOf(BillItemEntity(billId = cancelledId, productId = productId, productName = "Drill",
            productSize = "10 mm", productUnit = "Piece", quantity = 1.0, unitPricePaisa = 300000, itemTotalPaisa = 300000)))
        rejects { repository.save(draft("CANCELLED").copy(billNumber = "000018")) }
        assertEquals(1, repository.snapshot().size)
    }

    @Test fun ownerAuthenticationIsRequiredForEveryReadAndWrite() = runBlocking {
        val saved = repository.save(draft())
        session.clear()
        denied { repository.snapshot() }
        denied { repository.observe().first() }
        denied { repository.observeProducts().first() }
        denied { repository.save(draft("NEW")) }
        denied { repository.save(draft(), saved) }
        denied { repository.addService(saved, LocalDate.now().toString(), "Problem", "Action") }
        session.setSession(UserSession.WorkerSession(1, "Worker", "worker", 0, false))
        denied { repository.snapshot() }
        denied { repository.save(draft("NEW")) }
        denied { repository.addService(saved, LocalDate.now().toString(), "Problem", "Action") }
        session.setSession(UserSession.OwnerSession("Shop"))
        assertEquals(listOf(saved), repository.snapshot())
    }

    @Test fun damagedRecordsAreKeptAndBlockWritesRatherThanReplaced() = runBlocking {
        val saved = repository.save(draft())
        val damaged = SettingsEntity("warranty:broken", "{invalid")
        db.settingsDao().upsertSetting(damaged)
        try { repository.snapshot(); fail("Damaged record must be visible as an error") } catch (_: IllegalStateException) { }
        try { repository.save(draft("NEW")); fail("Cannot append to a damaged register") } catch (_: IllegalStateException) { }
        try { repository.addService(saved, LocalDate.now().toString(), "Problem", "Action"); fail("Cannot edit a damaged register") } catch (_: IllegalStateException) { }
        assertEquals(damaged, db.settingsDao().getSetting(damaged.key))
        assertNotNull(db.settingsDao().getSetting("warranty:${saved.id}"))
    }

    private suspend fun rejects(block: suspend () -> Any?) {
        try { block(); fail("Expected validation to reject input") } catch (_: IllegalArgumentException) { }
    }

    private suspend fun denied(block: suspend () -> Any?) {
        try { block(); fail("Expected owner permission denial") } catch (_: SecurityException) { }
    }
}
