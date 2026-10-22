package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.CategoryEntity
import com.hardwareshop.pos.data.local.entity.ProductEntity
import com.hardwareshop.pos.data.local.entity.ProductTypeEntity
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.data.repository.AuditRepository
import com.hardwareshop.pos.data.repository.ProductRepository
import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class QuotationIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var quotes: QuotationRepository
    private lateinit var products: ProductRepository
    private lateinit var product: Product
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                    (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount = true, includePipe = true) +
                        com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach(sql::execSQL)
                }
            }).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Quotation test")) }
        val audit = AuditRepository(db.auditLogDao(), session)
        products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(), audit, session)
        quotes = QuotationRepository(db, session, products)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "PVC"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Pipe"))
        val id = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "PVC Pipe",
            size = "1 inch", unit = "Length", sellingPricePaisa = 40000, gstRateBp = 1800, currentStock = 10.0,
            isPipe = true, pipeFeetPerLength = 20, pipeLengthPricePaisa = 40000, pipeFootPricePaisa = 3500))
        product = products.getProductById(id)!!
    }
    @After fun close() { db.close() }

    private suspend fun save() = quotes.saveFromCart(listOf(PipeBilling.item(product, 1, 2.0)), "Test plumber", "9876543210",
        QuotationRepository.defaultValidUntil(), "Prices subject to approval", 1000, true, false)

    @Test fun saveIsAnEstimateWithNoStockSaleOrCreditMutation() = runBlocking {
        val beforeBills = db.billDao().getAllBills()
        val beforeCredit = db.creditDao().getAllTransactions()
        val beforeCustomers = db.customerDao().getAllCustomers()
        val quote = save()
        assertEquals(54280L, quote.totalPaisa)
        assertEquals(10.0, db.productDao().getProductById(product.id)!!.currentStock, 0.0)
        assertEquals(beforeBills, db.billDao().getAllBills())
        assertEquals(beforeCredit, db.creditDao().getAllTransactions())
        assertEquals(beforeCustomers, db.customerDao().getAllCustomers())
        assertEquals(quote, quotes.snapshot().single())
        assertEquals(quote, quotes.observe().first().single())
    }

    @Test fun currentRatesAreUsedForReviewAndTheQuotedRatesRemainSaved() = runBlocking {
        val quote = save()
        val entity = db.productDao().getProductById(product.id)!!
        db.productDao().updateProduct(entity.copy(pipeLengthPricePaisa = 45000, pipeFootPricePaisa = 4000))
        val conversion = quotes.prepareConversion(quote)
        assertEquals(1, conversion.changedPriceCount)
        assertEquals(53000L, conversion.items.single().itemTotalPaisa)
        assertEquals(47000L, quotes.snapshot().single().lines.single().itemTotalPaisa)
        assertEquals(10.0, db.productDao().getProductById(product.id)!!.currentStock, 0.0)
    }

    @Test fun ownerPermissionIsEnforcedForReadsAndWrites() = runBlocking {
        val quote = save()
        session.clear()
        assertTrue(runCatching { quotes.snapshot() }.isFailure)
        assertTrue(runCatching { quotes.observe().first() }.isFailure)
        assertTrue(runCatching { save() }.isFailure)
        assertTrue(runCatching { quotes.prepareConversion(quote) }.isFailure)
        assertTrue(runCatching { quotes.remove(quote) }.isFailure)
        session.setSession(UserSession.OwnerSession("Test"))
        assertEquals(1, quotes.snapshot().size)
    }

    @Test fun savedBillMarkerPreventsAnotherConversionAndDeletion() = runBlocking {
        val quote = save()
        db.settingsDao().upsertSetting(SettingsEntity("quotation-bill:${quote.id}", "15"))
        assertEquals(15L, quotes.observe().first().single().convertedBillId)
        assertTrue(runCatching { quotes.prepareConversion(quote) }.isFailure)
        assertTrue(runCatching { quotes.remove(quote) }.isFailure)
    }

    @Test fun damagedRecordIsKeptAndBlocksWrites() = runBlocking {
        db.settingsDao().upsertSetting(SettingsEntity("quotation:broken", "{broken}"))
        assertTrue(runCatching { quotes.snapshot() }.isFailure)
        assertTrue(runCatching { save() }.isFailure)
        assertEquals("{broken}", db.settingsDao().getSetting("quotation:broken")!!.value)
    }

    @Test fun manualItemsSurviveQuotationConversion() = runBlocking {
        val item = ManualBillItem.create("Loose fitting", "75", emptyList())
        val quote = quotes.saveFromCart(listOf(item), "Walk-in", "", QuotationRepository.defaultValidUntil(), "", 0, false, false)
        val restored = quotes.prepareConversion(quote)
        ManualBillItem.validate(restored.items.single())
        assertEquals(7500L, restored.items.single().itemTotalPaisa)
        assertEquals(0, restored.changedPriceCount)
    }

    @Test fun quotationsSurviveBackupAndThePdfIsShareable() = runBlocking {
        val quote = save()
        val backup = BackupManager(context, db, AuditRepository(db.auditLogDao(), session), session)
        val exported = backup.createBackup()
        assertTrue(exported is Result.Success)
        assertTrue(backup.restoreBackup((exported as Result.Success).data) is Result.Success)
        assertEquals(quote, quotes.snapshot().single())
        val file = QuotationPdf.create(context, quote, SettingsRepository.ShopInfo("Quotation test", "", "", ""))
        assertTrue(file.length() > 100)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        assertNotNull(context.contentResolver.openInputStream(uri)?.use { it.read() })
    }
}
