package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TradeIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var billing: BillingRepository
    private lateinit var trade: TradeRepository
    private lateinit var audit: AuditRepository
    private var product = 0L; private var supplier = 0L; private var customer = 0L
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true) +
                        com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach { db.execSQL(it) }
                }
            }).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Owner")) }
        audit = AuditRepository(db.auditLogDao(), session)
        billing = BillingRepository(db, db.billDao(), db.productDao(), db.creditDao(), audit, BillNumberGenerator(db),
            SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), session), session)
        trade = TradeRepository(db, billing, session, audit)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "Hardware"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Pipe"))
        product = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "Pipe", unit = "Meter",
            sellingPricePaisa = 10000, currentStock = 10.0, gstRateBp = 1800))
        supplier = trade.saveSupplier(SupplierEntity(name = "Supplier"))
        customer = db.customerDao().insertCustomer(CustomerEntity(name = "Customer"))
    }
    @After fun close() { db.close() }
    private suspend fun purchase(key: String = "purchase-one", invoice: String = "INV1") = trade.purchase(key, supplier, invoice,
        System.currentTimeMillis(), listOf(PurchaseLine(product, 2.5, 4000, 1800)), 1800, "CASH", "Test")
    private suspend fun sale(method: PaymentMethod = PaymentMethod.CASH): Bill {
        val p = db.productDao().getProductById(product)!!.toDomain()
        return (billing.createBill(listOf(CartItem(p, 2.0, 10000, 20000)), customer, "Customer", "9876543210", 1000, "", true,
            method, 0, "", gstInclusive = false) as Result.Success).data
    }
    @Test fun purchaseRetriesReceiveStockOnceAndPaymentsCannotExceedDue() = runBlocking {
        val ids = coroutineScope { List(4) { async(Dispatchers.IO) { purchase() } }.awaitAll() }
        assertEquals(1, ids.distinct().size)
        assertEquals(12.5, db.productDao().getProductById(product)!!.currentStock, 0.0)
        assertEquals(10000L, trade.load().purchases.single().due)
        assertTrue(runCatching { trade.paySupplier("too-much", ids[0], 10001, "CASH", "") }.isFailure)
        trade.paySupplier("pay", ids[0], 10000, "UPI", "UTR")
        trade.paySupplier("pay", ids[0], 10000, "UPI", "UTR")
        assertEquals(0L, trade.load().purchases.single().due)
        assertTrue(runCatching { purchase("different-key", "inv1") }.isFailure)
    }
    @Test fun purchaseFailureRollsBackInvoiceAndStock() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_supplier_payment BEFORE INSERT ON supplier_payments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { purchase() }.isFailure)
        assertTrue(db.purchaseBillDao().getAllPurchaseBills().isEmpty())
        assertEquals(10.0, db.productDao().getProductById(product)!!.currentStock, 0.0)
    }
    @Test fun partialReturnsReconcileDiscountAndTaxWithoutDoubleStock() = runBlocking {
        val bill = sale(); val line = db.billDao().getBillItems(bill.id).single()
        for (i in 1..2) {
            val selection = listOf(ReturnLine(line.id, 1.0))
            val quote = trade.quote(bill.id, selection, emptyList())
            trade.returnSale("return-$i", bill.id, selection, emptyList(), "CASH", "Returned", quote)
            trade.returnSale("return-$i", bill.id, selection, emptyList(), "CASH", "Returned", quote)
        }
        assertEquals(bill.totalPaisa, db.tradeDao().returns().sumOf { it.totalPaisa })
        assertEquals(bill.gstAmountPaisa, db.tradeDao().returns().sumOf { it.taxPaisa })
        assertEquals(bill.discountPaisa, db.tradeDao().returns().sumOf { it.discountPaisa })
        assertEquals(10.0, db.productDao().getProductById(product)!!.currentStock, 0.0)
        assertEquals(0L, db.netSales(0, Long.MAX_VALUE).sales)
        assertTrue(billing.cancelBill(bill.id, "Already returned") is Result.Error)
        assertTrue(runCatching { trade.quote(bill.id, listOf(ReturnLine(line.id, 0.01)), emptyList()) }.isFailure)
    }
    @Test fun concurrentDistinctReturnRequestsCannotOverReturn() = runBlocking {
        val bill = sale(); val line = db.billDao().getBillItems(bill.id).single()
        val lines = listOf(ReturnLine(line.id, 2.0)); val quote = trade.quote(bill.id, lines, emptyList())
        val results = coroutineScope { List(4) { index -> async(Dispatchers.IO) {
            runCatching { trade.returnSale("return-$index", bill.id, lines, emptyList(), "CASH", "Return", quote) }
        } }.awaitAll() }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(10.0, db.productDao().getProductById(product)!!.currentStock, 0.0)
    }
    @Test fun creditReturnReducesOutstandingAndRefundsOnlyPaidBalance() = runBlocking {
        val bill = sale(PaymentMethod.CREDIT)
        CreditRepository(db, db.creditDao(), audit, session).recordPayment(customer, 5000, "Cash recovery")
        val lines = listOf(ReturnLine(db.billDao().getBillItems(bill.id).single().id, 2.0, false))
        val quote = trade.quote(bill.id, lines, emptyList())
        assertEquals(5000L, quote.refund)
        trade.returnSale("credit-return", bill.id, lines, emptyList(), "CASH", "Damaged", quote)
        assertEquals(0L, db.creditDao().getBalanceForCustomer(customer))
        assertEquals(8.0, db.productDao().getProductById(product)!!.currentStock, 0.0)
    }
    @Test fun exchangeSavesLinkedSaleAndCollectsOnlyDifference() = runBlocking {
        val bill = sale(); val lines = listOf(ReturnLine(db.billDao().getBillItems(bill.id).single().id, 1.0))
        val replacement = listOf(ReplacementLine(product, 1.0)); val quote = trade.quote(bill.id, lines, replacement)
        assertEquals(590L, quote.collect)
        val row = trade.returnSale("exchange", bill.id, lines, replacement, "CASH", "Exchange", quote)
        assertNotNull(row.exchangeBillId)
        val exchanged = db.billDao().getBillById(row.exchangeBillId!!)!!
        assertEquals(quote.replacement, exchanged.totalPaisa)
        assertEquals("", exchanged.customerGstNumber)
        assertEquals(exchanged.id.toString(), db.settingsDao().getSetting("checkout:exchange-exchange")!!.value)
        assertEquals(8.0, db.productDao().getProductById(product)!!.currentStock, 0.0)
        assertTrue(billing.cancelBill(row.exchangeBillId!!, "Linked exchange") is Result.Error)
        assertEquals(bill.totalPaisa + 590, db.netSales(0, Long.MAX_VALUE).cash)
    }
    @Test fun inclusiveExchangeKeepsQuotedTotalAndCustomerGstSnapshot() = runBlocking {
        val p = db.productDao().getProductById(product)!!.toDomain()
        val original = (billing.createBill(listOf(CartItem(p, 2.0, 10000, 20000)), null,
            "Customer", "9876543210", 1000, "", true, PaymentMethod.CASH, 0, "",
            customerGstNumber = "29ABCDE1234F1Z5", gstInclusive = true) as Result.Success).data
        val lines = listOf(ReturnLine(db.billDao().getBillItems(original.id).single().id, 1.0))
        val replacement = listOf(ReplacementLine(product, 1.0))
        val quote = trade.quote(original.id, lines, replacement)
        assertEquals(10000L, quote.replacement); assertEquals(500L, quote.collect)
        val row = trade.returnSale("inclusive-exchange", original.id, lines, replacement, "CASH", "Exchange", quote)
        val exchanged = db.billDao().getBillById(row.exchangeBillId!!)!!
        assertEquals(quote.replacement, exchanged.totalPaisa)
        assertEquals("29ABCDE1234F1Z5", exchanged.customerGstNumber)
        assertEquals(exchanged.id.toString(), db.settingsDao().getSetting("checkout:exchange-inclusive-exchange")!!.value)
        assertEquals(original.totalPaisa, db.billDao().getBillById(original.id)!!.totalPaisa)
    }
    @Test fun failedExchangeRollsBackReturnRestockAndNewSale() = runBlocking {
        val bill = sale(); val lines = listOf(ReturnLine(db.billDao().getBillItems(bill.id).single().id, 1.0))
        val replacement = listOf(ReplacementLine(product, 1.0)); val quote = trade.quote(bill.id, lines, replacement)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_exchange BEFORE INSERT ON payments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { trade.returnSale("exchange", bill.id, lines, replacement, "CASH", "Exchange", quote) }.isFailure)
        assertTrue(db.tradeDao().returns().isEmpty()); assertEquals(1, db.billDao().getAllBills().size)
        assertEquals(8.0, db.productDao().getProductById(product)!!.currentStock, 0.0)
    }
    @Test fun backupRoundTripKeepsSupplierDuesAndReturnedQuantities() = runBlocking {
        purchase(); val bill = sale()
        val lines = listOf(ReturnLine(db.billDao().getBillItems(bill.id).single().id, 1.0))
        trade.returnSale("backup-return", bill.id, lines, emptyList(), "CASH", "Return", trade.quote(bill.id, lines, emptyList()))
        val manager = BackupManager(ApplicationProvider.getApplicationContext<Context>(), db, audit, session)
        val uri = (manager.createBackup() as Result.Success).data
        val stock = db.productDao().getProductById(product)!!.currentStock
        assertTrue(manager.restoreBackup(uri) is Result.Success)
        assertEquals(stock, db.productDao().getProductById(product)!!.currentStock, 0.0)
        assertEquals(10000L, trade.load().purchases.single().due)
        assertEquals(1.0, trade.findBill(bill.billNumber).returned.values.single(), 0.0)
    }
    @Test fun signedOutSessionCannotAccessPurchasesOrReturns() = runBlocking {
        session.clear()
        assertTrue(runCatching { trade.load() }.isFailure)
        assertTrue(runCatching { purchase() }.isFailure)
    }
}
