package com.hardwareshop.pos

import android.content.Context
import androidx.activity.compose.setContent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class CartPricingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var db: AppDatabase
    private lateinit var products: ProductRepository
    private lateinit var billing: BillingRepository
    private lateinit var vm: BillingViewModel
    private lateinit var product: Product

    @Before fun setup() = runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java)
            .addCallback(object: androidx.room.RoomDatabase.Callback() {
                override fun onOpen(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                    (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true,includePipe=true) +
                        com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach(sql::execSQL)
                }
            }).build()
        val session=SessionHolder().apply { setSession(UserSession.OwnerSession("Test")) }
        val audit=AuditRepository(db.auditLogDao(),session)
        val settings=SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session)
        products=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),audit,session)
        billing=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),settings,session)
        val c=db.categoryDao().insertCategory(CategoryEntity(name="PVC"))
        val t=db.productTypeDao().insertType(ProductTypeEntity(categoryId=c,name="Pipe"))
        val id=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name="PVC Pipe Light extra long product name",size="4 inch",unit="Length",
            sellingPricePaisa=85000,purchasePricePaisa=60000,currentStock=20.0,
            isPipe=true,pipeLengthPricePaisa=85000,pipeFootPricePaisa=8000,pipeFeetPerLength=20))
        product=products.getProductById(id)!!
        vm=withContext(Dispatchers.Main) { BillingViewModel(billing,CustomerRepository(db.customerDao(),db.creditDao(),session),products,db.settingsDao(),settings,db,session) }
        ready()
        withContext(Dispatchers.Main) { vm.updatePipe(product,"2","5") }
    }
    private suspend fun ready() { withTimeout(15000) { vm.state.first { !it.isLoading } } }
    @After fun cleanup()=runBlocking {
        compose.activityRule.scenario.onActivity { it.setContent {} }
        withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        db.close()
    }
    private fun cart() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { CartScreen({}, {}, {}, {}, vm) }
        } }
    }

    @Test fun editedRatesSurviveQuantityPreviewAndReachSavedReceiptWithoutChangingMaster()=runBlocking {
        withContext(Dispatchers.Main) {
            assertTrue(vm.setPipePrices(product.id,"850","70"))
            assertEquals(205000L,vm.state.value.calculation!!.totalPaisa)
            vm.updatePipe(product,"3","6")
            assertEquals(297000L,vm.state.value.calculation!!.totalPaisa)
            vm.prepareCartPreview(PaymentMethod.UPI); vm.consumePreviewRequest()
        }
        ready()
        assertTrue(vm.state.value.previewLines!!.any { it.text.contains("6 Ft x 70") })
        withContext(Dispatchers.Main) { vm.closePreview(); vm.updatePipe(product,"2","5"); vm.saveBill() }
        ready()
        val saved=billing.getBillById(requireNotNull(vm.state.value.savedBillId))!!
        assertEquals(PaymentMethod.UPI,saved.paymentMethod)
        assertEquals(205000L,saved.totalPaisa)
        assertEquals(7000L,saved.items.single().pipeFootPricePaisa)
        assertTrue(ReceiptLayout.build(saved,ReceiptLayout.Shop("Shop","","","")).any { it.text.contains("5 Ft x 70") })
        val master=products.getProductById(product.id)!!
        assertEquals(8000L,master.pipeFootPricePaisa);assertEquals(85000L,master.pipeLengthPricePaisa)
        assertEquals(60000L,master.purchasePricePaisa);assertEquals(85000L,master.sellingPricePaisa)
        withContext(Dispatchers.Main) { vm.clearCart(); vm.updatePipe(master,"2","5") }
        assertEquals(210000L,vm.state.value.calculation!!.totalPaisa)
        assertEquals(PaymentMethod.CASH,vm.state.value.paymentMethod)
        assertFalse(vm.state.value.previewOnly)
    }
    @Test fun normalCashAndUpiPreviewDoNotSaveOrPrint()=runBlocking {
        for(method in listOf(PaymentMethod.CASH,PaymentMethod.UPI)) {
            withContext(Dispatchers.Main) { vm.prepareCartPreview(method); vm.consumePreviewRequest() }
            ready()
            assertEquals(method,vm.state.value.paymentMethod)
            assertFalse(vm.state.value.previewOnly)
            assertTrue(vm.state.value.previewLines!!.any { it.text=="Payment: ${method.displayName}" })
            assertNull(vm.state.value.savedBillId);assertFalse(vm.state.value.printAfterSave)
            withContext(Dispatchers.Main) { vm.closePreview() }
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM bills").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
    }
    @Test fun directPreviewKeepsPaymentStateAndCannotSubmit()=runBlocking {
        withContext(Dispatchers.Main) { vm.setPaymentMethod(PaymentMethod.UPI) }
        val before=vm.state.value
        withContext(Dispatchers.Main) { vm.prepareCartPreview(); vm.consumePreviewRequest() }
        ready()
        assertTrue(vm.state.value.previewOnly)
        assertEquals(before.paymentMethod,vm.state.value.paymentMethod);assertEquals(before.paidPaisa,vm.state.value.paidPaisa)
        assertNull(vm.state.value.previewQrUri)
        assertFalse(vm.state.value.previewLines!!.any { it.text.startsWith("Payment:") })
        withContext(Dispatchers.Main) { vm.saveBill(true) }
        assertNull(vm.state.value.savedBillId)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM payments").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
    }
    @Test fun cartPaymentPopupDismissalDoesNotChangeBill() {
        cart()
        compose.onNodeWithText("PROCEED").performClick()
        compose.onNodeWithText("Payment method").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.onNodeWithText("Payment method").assertDoesNotExist()
        compose.runOnIdle { assertFalse(vm.state.value.openPreviewOnArrival);assertNull(vm.state.value.savedBillId) }
    }
    private fun navigation() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var payment by remember { mutableStateOf(false) }
            MaterialTheme {
                if (payment) PaymentScreen({ payment=false }, {}, vm)
                else CartScreen({}, {}, { payment=true }, {}, vm)
            }
        } }
    }
    @Test fun normalCashChoiceOpensPreviewWithoutSaving() {
        navigation()
        compose.onNodeWithText("PROCEED").performClick()
        compose.onNodeWithText("CASH").performClick()
        compose.waitUntil(15000) { vm.state.value.previewLines != null }
        compose.onNodeWithText("Bill Preview").assertIsDisplayed()
        compose.onNodeWithText("Payment method").assertDoesNotExist()
        compose.onNodeWithText("CONFIRM, SAVE & PRINT").assertIsDisplayed()
        compose.runOnIdle { assertNull(vm.state.value.savedBillId) }
        compose.onNodeWithText("EDIT DETAILS").performClick()
        compose.runOnIdle { assertEquals(2L,vm.state.value.cart.single().pipeLengths) }
    }
    @Test fun directPreviewScreenHasNoSaveActionAndBackKeepsCart() {
        navigation()
        compose.onNodeWithContentDescription("Direct Preview").performClick()
        compose.waitUntil(15000) { vm.state.value.previewLines != null }
        compose.onNodeWithText("Bill Preview").assertIsDisplayed()
        compose.onNodeWithText("Payment method").assertDoesNotExist()
        compose.onNodeWithText("SAVE ONLY").assertDoesNotExist()
        compose.onNodeWithText("CONFIRM, SAVE & PRINT").assertDoesNotExist()
        compose.onNodeWithText("CLOSE PREVIEW").performClick()
        compose.runOnIdle { assertNull(vm.state.value.savedBillId); assertEquals(2L,vm.state.value.cart.single().pipeLengths) }
    }
    @Test fun directPreviewButtonSkipsPopupAndCreditUsesDetailsRoute() {
        cart()
        compose.onNodeWithContentDescription("Direct Preview").performClick()
        compose.onNodeWithText("Payment method").assertDoesNotExist()
        compose.runOnIdle { assertTrue(vm.state.value.previewOnly); vm.consumePreviewRequest() }
        compose.waitUntil(15000) { !vm.state.value.isLoading }
        compose.runOnIdle { vm.closePreview() }
        compose.onNodeWithText("Credit / Udhaar").performClick()
        compose.onNodeWithText("Payment method").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PaymentMethod.CREDIT,vm.state.value.paymentMethod);assertFalse(vm.state.value.previewOnly) }
    }
    @Test fun cartDialogEditsBothPricesAndRejectsInvalidInput() {
        cart()
        compose.onNodeWithText("EDIT PRICE FOR THIS BILL").performScrollTo().performClick()
        compose.onNodeWithText("Price per length ₹").performTextReplacement("900")
        compose.onNodeWithText("Price per foot ₹").performTextReplacement("NaN")
        compose.onNodeWithText("APPLY").assertIsNotEnabled()
        compose.onNodeWithText("Price per foot ₹").performTextReplacement("70")
        compose.onNodeWithText("APPLY").performClick()
        compose.runOnIdle {
            assertEquals(215000L,vm.state.value.calculation!!.totalPaisa)
            assertEquals(2L,vm.state.value.cart.single().pipeLengths)
            assertEquals(5.0,vm.state.value.cart.single().pipeFeet,0.0)
        }
    }
    @Test fun narrowProductCardWrapsCompleteNameAndSize() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { Box(Modifier.width(160.dp)) { BillingProductCard(product,false,{}, {}, {}) } }
        } }
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(product.name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount>2)
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText(product.size).assertIsDisplayed()
    }
}
