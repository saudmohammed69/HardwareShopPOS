package com.hardwareshop.pos

import android.content.Context
import androidx.compose.ui.test.*
import com.hardwareshop.pos.presentation.worker.billing.*
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
class WeightCalculatorUiTest {
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
        compose.activityRule.scenario.onActivity { it.setContent {} }
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



    private fun capture(name:String) {
        compose.waitForIdle()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"advanced-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun sampleCalibrationPersistsAndEstimatedQuantityIsReviewedInCartAndReceipt()=runBlocking {
        val vm=newBillingViewModel()
        withContext(Dispatchers.Main){vm.addToCart(product,2.0);vm.setGstEnabled(false)}
        compose.activityRule.scenario.onActivity {it.setContent {MaterialTheme {CartScreen({},{},{},{},vm)}}}
        compose.onNodeWithTag("weight-open-${product.id}").performScrollTo().performClick()
        compose.onNodeWithText("CALIBRATE FROM COUNTED SAMPLE").performScrollTo().performClick()
        compose.onNodeWithTag("sample-weight").performScrollTo().performTextReplacement("50")
        compose.onNodeWithTag("sample-count").performScrollTo().performTextReplacement("20")
        compose.onNodeWithText("USE SAMPLE CALIBRATION").performScrollTo().performClick()
        compose.onNodeWithText("SAVE VARIANT CALIBRATION").performScrollTo().performClick()
        compose.waitUntil(10000){runBlocking {db.settingsDao().getSetting("weight-calibration:${product.id}")!=null}}
        compose.onNodeWithTag("weight-gross").performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag("weight-tare").performScrollTo().performTextReplacement("5")
        compose.onNodeWithText("CALCULATE ESTIMATE").performScrollTo().performClick()
        compose.onNodeWithTag("weight-quantity").performScrollTo().assertTextContains("38.00")
        compose.onNodeWithTag("weight-quantity").performTextReplacement("37")
        capture("quantity-review")
        compose.onNodeWithText("APPLY QUANTITY").performClick()
        assertEquals(37.0,vm.state.value.cart.single().quantity,0.0)
        assertTrue(vm.state.value.cart.single().quantityEstimated)
        assertEquals("2.50000000",vm.loadWeightCalibration(product))
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"))
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
        withContext(Dispatchers.Main){vm.previewBill()}
        withTimeout(15000){vm.state.first {it.previewLines!=null && !it.isLoading}}
        assertTrue(vm.state.value.previewLines!!.any {it.text.contains("Estimated")})
        withContext(Dispatchers.Main){vm.closePreview();vm.saveDraft()}
        withTimeout(15000){vm.state.first {!it.isLoading && it.draftMessage=="Draft saved on this phone."}}
        withContext(Dispatchers.Main){vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()}
        val resumed=newBillingViewModel()
        assertEquals(37.0,resumed.state.value.cart.single().quantity,0.0)
        assertTrue(resumed.state.value.cart.single().quantityEstimated)
        assertEquals("2.50000000",resumed.loadWeightCalibration(product))
    }
    @Test fun zeroCalibrationInvalidWeightsAndUnauthenticatedWritesAreRejected()=runBlocking {
        val vm=newBillingViewModel()
        assertTrue(runCatching {vm.saveWeightCalibration(product,"0")}.isFailure)
        assertNull(db.settingsDao().getSetting("weight-calibration:${product.id}"))
        vm.saveWeightCalibration(product,"2")
        withContext(Dispatchers.Main){vm.addToCart(product,2.0)}
        assertFalse(withContext(Dispatchers.Main){vm.applyWeightQuantity(product.id,"1.5")})
        assertFalse(withContext(Dispatchers.Main){vm.applyWeightQuantity(product.id,"0")})
        assertEquals(2.0,vm.state.value.cart.single().quantity,0.0)
        session.clear()
        assertTrue(runCatching {vm.saveWeightCalibration(product,"3")}.isFailure)
        assertTrue(runCatching {vm.loadWeightCalibration(product)}.isFailure)
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"))
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
    }
}
