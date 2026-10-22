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
class AdvancedBillingIntegrationTest {
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


    @Test fun splitPaymentsPersistTwoRowsAndRetryDeductsStockExactlyOnce()=runBlocking {
        val token=UUID.randomUUID().toString()
        suspend fun save()=billing.createBill(listOf(line()),null,"Customer","",1,"",false,PaymentMethod.SPLIT,19999,"",
            checkoutId=token,paymentSplit=com.hardwareshop.pos.util.PaymentSplit(6000,13999))
        val result=save();assertTrue(result is Result.Success)
        val bill=(result as Result.Success).data
        assertEquals(19999L,bill.totalPaisa);assertEquals(19999L,bill.paidPaisa);assertEquals(0L,bill.creditPaisa)
        assertEquals(listOf(6000L,13999L),bill.payments.map {it.amountPaisa})
        assertEquals(listOf("CASH","UPI"),db.billDao().getPaymentsForBill(bill.id).map{it.method})
        val receipt=com.hardwareshop.pos.util.ReceiptLayout.build(bill,com.hardwareshop.pos.util.ReceiptLayout.Shop("Shop","","",""))
        assertTrue(receipt.any{it.text.contains("Cash") && it.text.contains("60.00")})
        assertTrue(receipt.any{it.text.contains("UPI") && it.text.contains("139.99")})
        val totals=db.billDao().getReportTotals(0,Long.MAX_VALUE)
        assertEquals(6000L,totals.cash);assertEquals(13999L,totals.upi);assertEquals(19999L,totals.sales)
        assertEquals(bill.id,(save() as Result.Success).data.id)
        assertEquals(1L,count("SELECT COUNT(*) FROM bills"));assertEquals(2L,count("SELECT COUNT(*) FROM payments"))
        assertEquals(18.0,products.getProductById(product.id)!!.currentStock,0.0)
        assertTrue(db.creditDao().getAllTransactions().isEmpty())
    }
    @Test fun invalidSplitRollsBackBeforeInvoiceStockAndPayments()=runBlocking {
        for(split in listOf(null,com.hardwareshop.pos.util.PaymentSplit(6000,13999),com.hardwareshop.pos.util.PaymentSplit(-1,20001),com.hardwareshop.pos.util.PaymentSplit(Long.MAX_VALUE,1))){
            val result=billing.createBill(listOf(line()),null,"","",0,"",false,PaymentMethod.SPLIT,20000,"",paymentSplit=split)
            assertTrue(result is Result.Error)
        }
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"));assertEquals(0L,count("SELECT COUNT(*) FROM payments"))
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun creditStillRecordsPartialCashAndOnlyRemainingDebt()=runBlocking {
        val id=db.customerDao().insertCustomer(com.hardwareshop.pos.data.local.entity.CustomerEntity(name="Credit customer",phone="9876543210"))
        val result=billing.createBill(listOf(line()),id,"Credit customer","9876543210",0,"",false,PaymentMethod.CREDIT,5000,"")
        assertTrue(result is Result.Success)
        val bill=(result as Result.Success).data
        assertEquals(15000L,bill.creditPaisa);assertEquals(15000L,db.creditDao().getBalanceForCustomer(id))
        assertEquals(5000L,db.billDao().getPaymentsForBill(bill.id).single().amountPaisa)
        assertEquals("CASH",db.billDao().getPaymentsForBill(bill.id).single().method)
    }
    @Test fun namedDraftsKeepCustomerDiscountSplitAndQuantityIsolatedAcrossRestart()=runBlocking {
        val vm=newBillingViewModel()
        val customerId=db.customerDao().insertCustomer(com.hardwareshop.pos.data.local.entity.CustomerEntity(name="Customer A",phone="9876543210"))
        val customer=CustomerRepository(db.customerDao(),db.creditDao(),session).getCustomerById(customerId)!!
        withContext(Dispatchers.Main){vm.addToCart(product,2.0);vm.selectCustomer(customer);vm.setGstEnabled(false);vm.setDiscount(100,"A discount");vm.setPaymentMethod(PaymentMethod.SPLIT);vm.setSplitAmounts(6000,13900);vm.parkCurrent("Customer A")}
        withTimeout(15000){vm.state.first{!it.isLoading && it.cart.isEmpty()}}
        val keyA=db.settingsDao().getAllSettings().single{it.key.startsWith("cart:owner:parked:")}.key
        withContext(Dispatchers.Main){vm.addToCart(product,3.0);vm.renameCart("Customer B");vm.setGstEnabled(false);vm.setDiscount(50,"B discount");vm.resumeParked(keyA)}
        withTimeout(15000){vm.state.first{!it.isLoading && it.cartLabel=="Customer A"}}
        assertEquals(2.0,vm.state.value.cart.single().quantity,0.0);assertEquals(customerId,vm.state.value.customer!!.id)
        assertEquals(100L,vm.state.value.discountPaisa);assertEquals(6000L,vm.state.value.splitCashPaisa);assertEquals(13900L,vm.state.value.splitUpiPaisa)
        val b=db.settingsDao().getAllSettings().single{it.key.startsWith("cart:owner:parked:")}
        val draftB=gson.fromJson(b.value,SavedCart::class.java)
        assertEquals("Customer B",draftB.label);assertEquals(3.0,draftB.items.single().quantity,0.0);assertNull(draftB.customerId);assertEquals(50L,draftB.discountPaisa)
        withContext(Dispatchers.Main){vm.saveDraft()};withTimeout(15000){vm.state.first{!it.isLoading}}
        withContext(Dispatchers.Main){vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()}
        val restarted=newBillingViewModel()
        assertEquals("Customer A",restarted.state.value.cartLabel);assertEquals(2.0,restarted.state.value.cart.single().quantity,0.0)
        assertEquals(100L,restarted.state.value.discountPaisa);assertEquals(13900L,restarted.state.value.splitUpiPaisa)
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"));assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
        assertTrue(db.creditDao().getAllTransactions().isEmpty())
    }
}
