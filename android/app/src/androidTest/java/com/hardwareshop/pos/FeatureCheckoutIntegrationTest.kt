package com.hardwareshop.pos

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.CategoryEntity
import com.hardwareshop.pos.data.local.entity.ProductEntity
import com.hardwareshop.pos.data.local.entity.ProductTypeEntity
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.data.repository.AuditRepository
import com.hardwareshop.pos.data.repository.BillingRepository
import com.hardwareshop.pos.data.repository.CustomerRepository
import com.hardwareshop.pos.data.repository.ProductRepository
import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.data.sync.SyncJournalSchema
import com.hardwareshop.pos.data.sync.SyncTransferSchema
import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.PaymentMethod
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.domain.model.SavedCart
import com.hardwareshop.pos.presentation.common.ProductSortControl
import com.hardwareshop.pos.presentation.worker.billing.BillingViewModel
import com.hardwareshop.pos.util.BillNumberGenerator
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.ProductSortOrder
import com.hardwareshop.pos.util.SessionHolder
import com.hardwareshop.pos.util.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Every repository uses this test's fresh Room database, never the application's seeded database. */
class FeatureCheckoutIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var products: ProductRepository
    private lateinit var settings: SettingsRepository
    private lateinit var billing: BillingRepository
    private lateinit var quotations: QuotationRepository
    private lateinit var product: Product
    private val viewModels = mutableListOf<BillingViewModel>()
    private val gson = Gson()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(sql: SupportSQLiteDatabase) {
                    (SyncJournalSchema.statements(includeBoxCount = true, includePipe = true) +
                        SyncTransferSchema.statements()).forEach(sql::execSQL)
                }
            }).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Integration shop")) }
        val audit = AuditRepository(db.auditLogDao(), session)
        settings = SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), session)
        products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(), audit, session)
        billing = BillingRepository(db, db.billDao(), db.productDao(), db.creditDao(), audit, BillNumberGenerator(db), settings, session)
        quotations = QuotationRepository(db, session, products)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "Tools"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Hand Tools"))
        val id = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "Hammer",
            size = "500 g", unit = "Piece", sellingPricePaisa = 10000, purchasePricePaisa = 6000, currentStock = 20.0))
        product = requireNotNull(products.getProductById(id))
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        db.close()
    }

    private fun line() = CartItem(product, 2.0, product.sellingPricePaisa,
        CalculationEngine.itemTotalPaisa(2.0, product.sellingPricePaisa))

    private suspend fun quotation() = quotations.saveFromCart(listOf(line()), "Contractor", "9876543210",
        QuotationRepository.defaultValidUntil(), "Deliver after approval", 500, gstEnabled = false, gstInclusive = true)

    private suspend fun checkout(record: QuotationRepository.Record, items: List<CartItem>, token: String): Result<Bill> =
        billing.createBill(items, null, record.customerName, record.customerPhone, record.discountPaisa,
            "Quotation ${record.number}", record.gstEnabled, PaymentMethod.CASH, 0, record.notes,
            checkoutId = token, gstInclusive = record.gstInclusive, sourceQuotationId = record.id)

    private fun count(query: String): Long = db.openHelper.readableDatabase.query(query).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private suspend fun newBillingViewModel(): BillingViewModel = withContext(Dispatchers.Main) {
        BillingViewModel(billing, CustomerRepository(db.customerDao(), db.creditDao(), session), products,
            db.settingsDao(), settings, db, session).also { viewModels.add(it) }
    }.also { vm -> withTimeout(15000) { vm.state.first { !it.isLoading } } }

    @Test fun quotationCheckoutLinksExactlyOneBillAndStockMovementDespiteRetries() = runBlocking {
        val beforeMovements = count("SELECT COUNT(*) FROM sync_stock_movements")
        val quote = quotation()
        assertEquals(20.0, products.getProductById(product.id)!!.currentStock, 0.0)
        assertEquals(0L, count("SELECT COUNT(*) FROM bills"))
        assertEquals(beforeMovements, count("SELECT COUNT(*) FROM sync_stock_movements"))
        val current = db.productDao().getProductById(product.id)!!
        db.productDao().updateProduct(current.copy(sellingPricePaisa = 12000))
        val conversion = quotations.prepareConversion(quote)
        assertEquals(1, conversion.changedPriceCount)
        assertEquals(12000L, conversion.items.single().unitPricePaisa)
        val token = UUID.randomUUID().toString()
        val saved = checkout(quote, conversion.items, token)
        assertTrue(saved is Result.Success)
        val bill = (saved as Result.Success).data
        assertEquals(23500L, bill.totalPaisa)
        assertEquals(bill.id.toString(), db.settingsDao().getSetting("quotation-bill:${quote.id}")!!.value)
        assertEquals(bill.id, quotations.snapshot().single().convertedBillId)
        assertEquals(18.0, products.getProductById(product.id)!!.currentStock, 0.0)
        assertEquals(beforeMovements + 1, count("SELECT COUNT(*) FROM sync_stock_movements"))

        val retry = checkout(quote, conversion.items, token)
        assertTrue(retry is Result.Success)
        assertEquals(bill.id, (retry as Result.Success).data.id)
        val duplicate = checkout(quote, conversion.items, UUID.randomUUID().toString())
        assertTrue(duplicate is Result.Error)
        assertEquals(1L, count("SELECT COUNT(*) FROM bills"))
        assertEquals(1L, count("SELECT COUNT(*) FROM payments"))
        assertEquals(beforeMovements + 1, count("SELECT COUNT(*) FROM sync_stock_movements"))
        assertEquals(18.0, products.getProductById(product.id)!!.currentStock, 0.0)
        assertTrue(runCatching { quotations.prepareConversion(quote) }.isFailure)
    }

    @Test fun failedQuotationCheckoutLeavesNoLinkAndSameTokenCanSucceedAfterCorrection() = runBlocking {
        val quote = quotation()
        val conversion = quotations.prepareConversion(quote)
        val token = UUID.randomUUID().toString()
        val current = db.productDao().getProductById(product.id)!!
        val beforeMovements = count("SELECT COUNT(*) FROM sync_stock_movements")
        db.productDao().updateProduct(current.copy(isActive = false))
        assertTrue(checkout(quote, conversion.items, token) is Result.Error)
        assertNull(db.settingsDao().getSetting("quotation-bill:${quote.id}"))
        assertNull(db.settingsDao().getSetting("checkout:$token"))
        assertEquals(0L, count("SELECT COUNT(*) FROM bills"))
        assertEquals(0L, count("SELECT COUNT(*) FROM payments"))
        assertEquals(beforeMovements, count("SELECT COUNT(*) FROM sync_stock_movements"))
        assertEquals(20.0, db.productDao().getProductById(product.id)!!.currentStock, 0.0)
        db.productDao().updateProduct(current)
        assertTrue(checkout(quote, conversion.items, token) is Result.Success)
        assertEquals(1L, count("SELECT COUNT(*) FROM bills"))
        assertNotNull(db.settingsDao().getSetting("quotation-bill:${quote.id}"))
    }

    @Test fun workerCannotConvertQuotationThroughTheCheckoutRepository() = runBlocking {
        val quote = quotation()
        val conversion = quotations.prepareConversion(quote)
        session.setSession(UserSession.WorkerSession(99, "Worker", "worker", 0, true))
        assertTrue(checkout(quote, conversion.items, UUID.randomUUID().toString()) is Result.Error)
        assertNull(db.settingsDao().getSetting("quotation-bill:${quote.id}"))
        assertEquals(0L, count("SELECT COUNT(*) FROM bills"))
        assertEquals(20.0, db.productDao().getProductById(product.id)!!.currentStock, 0.0)
    }

    @Test fun importedQuotationPersistsItsSourceAndRecoversAsAnUnfinishedBill() = runBlocking {
        val quote = quotation()
        val conversion = quotations.prepareConversion(quote)
        val vm = newBillingViewModel()
        val imported = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { vm.importQuotation(quote, conversion.items) { imported.complete(Unit) } }
        withTimeout(15000) { imported.await() }
        assertEquals(quote.id, vm.state.value.sourceQuotationId)
        assertEquals(quote.customerName, vm.state.value.customerName)
        assertEquals(quote.discountPaisa, vm.state.value.discountPaisa)
        val saved = gson.fromJson(db.settingsDao().getSetting("cart:owner")!!.value, SavedCart::class.java)
        assertEquals(quote.id, saved.sourceQuotationId)
        assertEquals(quote.id, gson.fromJson(gson.toJson(saved), SavedCart::class.java).sourceQuotationId)
        assertEquals(2.0, saved.items.single().quantity, 0.0)
        assertEquals(0L, count("SELECT COUNT(*) FROM bills"))
        assertEquals(20.0, products.getProductById(product.id)!!.currentStock, 0.0)
        withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
        val recovered = newBillingViewModel()
        assertEquals(quote.id, recovered.state.value.sourceQuotationId)
        assertEquals(saved.items.single().quantity, recovered.state.value.cart.single().quantity, 0.0)
        assertNull(recovered.state.value.savedBillId)
        assertNull(db.settingsDao().getSetting("quotation-bill:${quote.id}"))
    }

    @Test fun workspaceCleanupRemovesFeatureRecordsAndConversionLinksButKeepsShopSettings() = runBlocking {
        val quote = quotation()
        db.settingsDao().upsertSetting(SettingsEntity("quotation-bill:${quote.id}", "12"))
        db.settingsDao().upsertSetting(SettingsEntity("warranty:${UUID.randomUUID()}", "test warranty record"))
        db.settingsDao().upsertSetting(SettingsEntity("shop_name", "Shop survives restore"))
        db.settingsDao().clearWorkspaces()
        assertNull(db.settingsDao().getSetting("quotation:${quote.id}"))
        assertNull(db.settingsDao().getSetting("quotation-bill:${quote.id}"))
        assertFalse(db.settingsDao().getAllSettings().any { it.key.startsWith("warranty:") })
        assertEquals("Shop survives restore", db.settingsDao().getSetting("shop_name")!!.value)
        assertEquals(20.0, db.productDao().getProductById(product.id)!!.currentStock, 0.0)
    }

    @Test fun compactSortStartsClosedAndUserCanChooseAndResetOptionalOrder() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var order by remember { mutableStateOf(ProductSortOrder.DEFAULT) }
            MaterialTheme { ProductSortControl(order, { order = it }, compact = true) }
        } }
        compose.onNodeWithContentDescription("Sort products: Default").assertIsDisplayed()
        compose.onNodeWithText("Lowest stock").assertDoesNotExist()
        compose.onNodeWithTag("product-sort-control").performClick()
        compose.onNodeWithTag("product-sort-PRICE_ASC").performClick()
        compose.onNodeWithContentDescription("Sort products: Price low → high").assertIsDisplayed()
        compose.onNodeWithText("Lowest stock").assertDoesNotExist()
        compose.onNodeWithTag("product-sort-control").performClick()
        compose.onNodeWithTag("product-sort-DEFAULT").performClick()
        compose.onNodeWithContentDescription("Sort products: Default").assertIsDisplayed()
    }
}
