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
class AdvancedBillingUiTest {
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
    @Test fun splitChoiceInputsPreviewAndSaveUseTheProductionScreens()=runBlocking {
        db.settingsDao().upsertSetting(SettingsEntity("merchant_upi_id","shop@upi"))
        db.settingsDao().upsertSetting(SettingsEntity("merchant_upi_name","Test shop"))
        val vm=newBillingViewModel()
        withContext(Dispatchers.Main){vm.addToCart(product,2.0);vm.setGstEnabled(false)}
        compose.activityRule.scenario.onActivity {activity->activity.setContent {MaterialTheme {
            var payment by remember {mutableStateOf(false)}
            if(payment)PaymentScreen({payment=false},{},vm) else CartScreen({},{},{payment=true},{},vm)
        }}}
        compose.onNodeWithText("PROCEED").performClick()
        compose.onNodeWithText("Payment method").assertIsDisplayed()
        compose.onNodeWithText("CASH").assertIsDisplayed();compose.onNodeWithText("UPI").assertIsDisplayed()
        compose.onNodeWithText("SPLIT CASH + UPI").assertIsDisplayed().performClick()
        compose.onNodeWithTag("split-cash").performScrollTo().performTextReplacement("60")
        compose.onNodeWithTag("split-upi").performScrollTo().performTextReplacement("140")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        capture("split-entry")
        compose.onNodeWithText("CONFIRM SPLIT & PREVIEW").performClick()
        withTimeout(15000){vm.state.first {!it.isLoading && (it.previewLines!=null || it.error!=null)}}
        assertNull(vm.state.value.error)
        compose.onNodeWithText("Bill Preview").assertIsDisplayed()
        assertTrue(vm.state.value.previewLines!!.any {it.text.contains("Cash") && it.text.contains("60.00")})
        assertTrue(vm.state.value.previewLines!!.any {it.text.contains("UPI") && it.text.contains("140.00")})
        assertEquals("140.00",android.net.Uri.parse(vm.state.value.previewQrUri!!).getQueryParameter("am"))
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"))
        capture("split-preview")
        compose.onNodeWithText("SAVE ONLY").performClick()
        withTimeout(15000){vm.state.first {it.savedBillId!=null && !it.isLoading}}
        val bill=billing.getBillById(vm.state.value.savedBillId!!)!!
        assertEquals(20000L,bill.totalPaisa);assertEquals(listOf(6000L,14000L),bill.payments.map {it.amountPaisa})
        assertEquals(18.0,products.getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun swipeEditAndRemoveKeepNormalControlsAndUndo()=runBlocking {
        val vm=newBillingViewModel()
        withContext(Dispatchers.Main){vm.addToCart(product,2.0);vm.setGstEnabled(false)}
        compose.activityRule.scenario.onActivity {activity->activity.setContent {MaterialTheme {CartScreen({},{},{},{},vm)}}}
        compose.onNodeWithTag("cart-swipe-${product.id}").performTouchInput {swipeRight()}
        compose.onNodeWithText("EDIT QUANTITY / SIZE").assertIsDisplayed().performClick()
        compose.onNodeWithTag("edit-quantity").performScrollTo().performTextReplacement("4")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("APPLY CHANGES").performScrollTo().performClick()
        compose.waitUntil(15000){vm.state.value.cart.singleOrNull()?.quantity==4.0}
        compose.onNodeWithTag("cart-swipe-${product.id}").performTouchInput {swipeLeft()}
        compose.onNodeWithText("Remove this item from the cart?").assertIsDisplayed()
        compose.onNodeWithText("KEEP ITEM").performClick()
        assertEquals(4.0,vm.state.value.cart.single().quantity,0.0)
        compose.onNodeWithTag("cart-swipe-${product.id}").performTouchInput {swipeLeft()}
        compose.onAllNodesWithText("REMOVE").onLast().performClick()
        compose.onNodeWithText("UNDO").performClick()
        compose.waitUntil(15000){vm.state.value.cart.singleOrNull()?.quantity==4.0}
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"));assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
        capture("swipe-undo")
    }
}
