package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.ProductEntity
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.util.UserSession
import com.hardwareshop.pos.util.ImageManager
import com.hardwareshop.pos.util.SessionHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Device tests: Room transaction rollback and the real Products/Pending flows. */
class FinalCatalogIntegrationTest {
    @Test fun bothCataloguesUpgradeAndPendingContainsOnly237UnpricedDrafts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val rows = context.assets.open("final_catalog.json").bufferedReader().use {
                Gson().fromJson(it, Array<FinalProductCatalog.Row>::class.java).toList()
            }
            val supplement = context.assets.open("store_catalog.json").bufferedReader().use {
                Gson().fromJson(it, Array<FinalProductCatalog.Row>::class.java).toList()
            }
            db.withTransaction { FinalProductCatalog.merge(FinalProductCatalog.RoomStore(db), rows, 1) }
            db.withTransaction { FinalProductCatalog.mergeBoth(FinalProductCatalog.RoomStore(db), rows, supplement, 2) }
            db.withTransaction { FinalProductCatalog.mergeBoth(FinalProductCatalog.RoomStore(db), rows, supplement, 3) }
            val session = SessionHolder().apply { setSession(UserSession.OwnerSession("Test shop")) }
            val products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(),
                AuditRepository(db.auditLogDao(), session), session)
            val pending = PendingProductRepository(db, products, ImageManager(context), session)
            assertEquals(1698, products.observeAllActiveProducts().first().size)
            val drafts = pending.observePending().first()
            assertEquals(237, drafts.size)
            assertTrue(drafts.all { it.isDraft && it.gaps.any { gap -> gap.key == "selling" } })
            assertTrue(db.productDao().getAllProducts().all { it.gstRateBp == 1800 })
        } finally { db.close() }
    }

    @Test fun interruptedImportRollsBackProductsGroupsAndCompletionMarker() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val room = FinalProductCatalog.RoomStore(db)
            val interrupted = object : FinalProductCatalog.Store by room {
                override suspend fun addProduct(value: ProductEntity): Long {
                    room.addProduct(value)
                    error("simulated interruption")
                }
            }
            val row = FinalProductCatalog.Row("final-test", "Hardware", "Tools", "Hammer", "", "Piece",
                "130", "", "18", "", "")
            try {
                db.withTransaction { FinalProductCatalog.merge(interrupted, listOf(row), 1) }
                fail("Import must roll back")
            } catch (_: IllegalStateException) { }
            assertTrue(db.productDao().getAllProducts().isEmpty())
            assertTrue(db.categoryDao().getAllCategories().isEmpty())
            assertNull(db.settingsDao().getSetting(FinalProductCatalog.VERSION))
            db.withTransaction { FinalProductCatalog.merge(room, listOf(row), 2) }
            assertEquals(1, db.productDao().countActiveProducts())
        } finally { db.close() }
    }
}
