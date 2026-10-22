package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Run on an Android device/emulator: exercises actual Room transactions and access checks. */
class PendingProductIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var products: ProductRepository
    private lateinit var pending: PendingProductRepository
    private var category = 0L
    private var type = 0L

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Test shop")) }
        val audit = AuditRepository(db.auditLogDao(), session)
        products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(), audit, session)
        pending = PendingProductRepository(db, products, ImageManager(context), session)
        category = (products.createCategory("PVC") as Result.Success).data
        type = (products.createProductType(category, "Pipe") as Result.Success).data
    }
    @After fun close() { db.close() }

    private suspend fun create(): Long = (products.createProduct(category, type, "Pipe", "20mm", "Meter",
        1200, 800, 250, 10.0, 0.0, null, "conduit") as Result.Success).data
    private fun draft(id: String = "one") = ProductDraft(id, form = ProductForm(categoryId = category,
        typeId = type, name = "Half entered", sellingPrice = "12."))

    @Test fun draftIsDurableAndExcludedFromBilling() = runBlocking {
        val d = draft()
        pending.saveDraft(d)
        val reloaded = PendingProductRepository(db, products, ImageManager(ApplicationProvider.getApplicationContext()), session)
        assertEquals(d, reloaded.getDraft(d.id))
        assertEquals(0, db.productDao().countActiveProducts())
        assertEquals(1, reloaded.observePending().first().count { it.isDraft })
    }
    @Test fun productAndDraftCompletionAreAtomicAndIdempotent() = runBlocking {
        val d = draft().copy(ignoredOptional = setOf("photo"))
        pending.saveDraft(d)
        pending.complete(d) { create() }
        assertNull(pending.getDraft(d.id))
        assertEquals(1, db.productDao().countActiveProducts())
        try { pending.complete(d) { create() }; fail("Completed draft must not publish twice") }
        catch (_: IllegalStateException) { }
        assertEquals(1, db.productDao().countActiveProducts())
    }
    @Test fun failedCompletionRollsBackProductAndRetainsDraft() = runBlocking {
        val d = draft()
        pending.saveDraft(d)
        try { pending.complete(d) { create(); error("simulated interruption") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, db.productDao().countActiveProducts())
        assertEquals(d, pending.getDraft(d.id))
    }
    @Test fun detailEditsPreserveStockAfterASale() = runBlocking {
        val id = create()
        val oldForm = products.getProductById(id)!!
        val createdAt = db.productDao().getProductById(id)!!.createdAt
        db.productDao().decrementStock(id, 3.0)
        assertTrue(products.updateProduct(oldForm.copy(name = "Updated name")) is Result.Success)
        assertEquals(7.0, products.getProductById(id)!!.currentStock, 0.0)
        assertEquals(createdAt, db.productDao().getProductById(id)!!.createdAt)
    }
    @Test fun discardingAnEditKeepsSavedProductAndBlocksLateAutosave() = runBlocking {
        val id = create()
        val d = draft().copy(productId = id)
        pending.saveDraft(d)
        pending.discard(d.id)
        assertEquals("Pipe", products.getProductById(id)!!.name)
        assertNull(pending.getDraft(d.id))
        try { pending.saveDraft(d); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun ownerAccessIsRequiredForDraftReadsAndWrites() = runBlocking {
        session.setSession(UserSession.WorkerSession(1, "Worker", "worker", 0, false))
        try { pending.saveDraft(draft()); fail() } catch (_: SecurityException) { }
        try { pending.getDraft("one"); fail() } catch (_: SecurityException) { }
        try { pending.observePending().first(); fail() } catch (_: SecurityException) { }
    }
}
