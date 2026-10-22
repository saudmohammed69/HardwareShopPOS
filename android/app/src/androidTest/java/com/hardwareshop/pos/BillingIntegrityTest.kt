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

class BillingIntegrityTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var billing: BillingRepository
    private lateinit var stock: StockRepository
    private lateinit var credit: CreditRepository
    private var productId = 0L
    private var customerId = 0L

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true) +
                        com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach { db.execSQL(it) }
                }
            }).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Test")) }
        val audit = AuditRepository(db.auditLogDao(), session)
        billing = BillingRepository(db, db.billDao(), db.productDao(), db.creditDao(), audit,
            BillNumberGenerator(db), SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), session), session)
        stock = StockRepository(db, db.productDao(), db.stockDao(), audit, session)
        credit = CreditRepository(db, db.creditDao(), audit, session)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "Tools"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Pipe"))
        productId = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "Pipe",
            unit = "Meter", sellingPricePaisa = 10000, gstRateBp = 1800, currentStock = 10.0))
        customerId = db.customerDao().insertCustomer(CustomerEntity(name = "Customer"))
    }
    @After fun close() { db.close() }
    @Test fun billingSearchPrioritisesFrequentActiveSalesAndIgnoresCancelledBills() = runBlocking {
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "PVC"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Fittings"))
        suspend fun product(name: String) = db.productDao().insertProduct(ProductEntity(
            categoryId = category, typeId = type, name = name, size = "1 inch", unit = "Piece", sellingPricePaisa = 100))
        val coupler = product("PVC Coupler")
        val elbow = product("PVC Elbow")
        var sequence = 0
        suspend fun record(id: Long, status: String, duplicate: Boolean = false) {
            val bill = db.billDao().insertBill(BillEntity(billNumber = "popular-${sequence++}",
                subtotalPaisa = 100, totalPaisa = 100, paymentMethod = "CASH", status = status))
            val line = BillItemEntity(billId = bill, productId = id, productName = "Snapshot", productSize = "1 inch",
                productUnit = "Piece", quantity = 1.0, unitPricePaisa = 100, itemTotalPaisa = 100)
            db.billDao().insertBillItems(if (duplicate) listOf(line, line) else listOf(line))
        }
        record(coupler, "ACTIVE", duplicate = true)
        repeat(3) { record(elbow, "ACTIVE") }
        repeat(10) { record(coupler, "CANCELLED") }
        val repo = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(), audit(), session)
        assertEquals(listOf(coupler, elbow), repo.searchProducts("PVC").map { it.id })
        assertEquals(listOf(elbow, coupler), repo.searchProducts("PVC", preferBestSellers = true).map { it.id })
        assertEquals(listOf(coupler), repo.searchProducts("PVC Coupler", preferBestSellers = true).map { it.id })
        assertEquals(mapOf(coupler to 1L, elbow to 3L), db.billDao().getProductSalesFrequency().associate { it.productId to it.saleCount })
    }

    private fun audit() = AuditRepository(db.auditLogDao(), session)
    @Test fun inclusiveDiscountUsesOriginalPriceAndRetriesDeductStockOnce() = runBlocking {
        val product = db.productDao().getProductById(productId)!!.toDomain()
        for (discount in listOf(5000L, 10000L)) {
            suspend fun save() = billing.createBill(listOf(CartItem(product, 1.0, 10000, 10000)),
                null, "", "", discount, "Owner approved", true, PaymentMethod.CASH, 0, "",
                checkoutId = "inclusive-discount-$discount", gstInclusive = true)
            val first = save()
            assertTrue(first.toString(), first is Result.Success)
            val bill = (first as Result.Success).data
            assertEquals(10000L - discount, bill.totalPaisa)
            assertEquals(if (discount == 5000L) 763L else 0L, bill.gstAmountPaisa)
            assertEquals(bill.gstAmountPaisa, bill.items.sumOf { it.gstAmountPaisa })
            assertEquals(bill.id, (save() as Result.Success).data.id)
        }
        assertEquals(2, db.billDao().getAllBills().size)
        assertEquals(8.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun discountAboveOriginalPriceRejectsWithoutChangingStockOrInvoiceCounter() = runBlocking {
        val product = db.productDao().getProductById(productId)!!.toDomain()
        val sequenceBefore = db.settingsDao().getSetting("bill_sequence")
        for (inclusive in listOf(true, false)) {
            val result = billing.createBill(listOf(CartItem(product, 1.0, 10000, 10000)),
                null, "", "", 10001, "", true, PaymentMethod.CASH, 0, "",
                checkoutId = "invalid-discount-$inclusive", gstInclusive = inclusive)
            assertTrue(result.toString(), result is Result.Error)
            assertTrue((result as Result.Error).message.contains("Discount exceeds subtotal"))
            assertNull(db.settingsDao().getSetting("checkout:invalid-discount-$inclusive"))
        }
        assertEquals(sequenceBefore, db.settingsDao().getSetting("bill_sequence"))
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun staleStockUnitCannotBeSavedOrDeductedFromAnotherUnit() = runBlocking {
        val old = db.productDao().getProductById(productId)!!.toDomain()
        db.productDao().updateProduct(old.toEntity().copy(unit = "Piece"))
        val result = billing.createBill(listOf(CartItem(old, 1.0, old.sellingPricePaisa, 10000)), null, "", "",
            0, "", true, PaymentMethod.CASH, 0, "", checkoutId = "stale-unit")
        assertTrue(result.toString(), result is Result.Error)
        assertEquals(10.0,db.productDao().getProductById(productId)!!.currentStock,0.0)
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertNull(db.settingsDao().getSetting("checkout:stale-unit"))
    }
    @Test fun automaticSearchUsesCategoryAndSizeWithoutManualAliases() = runBlocking {
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "PVC"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Elbow"))
        val correct = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type,
            name = "Elbow", size = "1/2 inch", unit = "Piece", sellingPricePaisa = 125))
        db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type,
            name = "Elbow", size = "3/4 inch", unit = "Piece", sellingPricePaisa = 150))
        val products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(),
            AuditRepository(db.auditLogDao(), session), session)
        val results = products.searchProducts("pvc half inch bend")
        assertEquals(listOf(correct),results.map { it.id })
        assertEquals("PVC",results.single().categoryName)
        assertEquals("",results.single().searchAliases)
    }
    @Test fun concurrentRepaymentRetriesDeductOnceAndRejectChangedDetails() = runBlocking {
        assertTrue(sale(method = PaymentMethod.CREDIT, customer = customerId) is Result.Success)
        val requests = coroutineScope { List(4) { async(Dispatchers.IO) {
            credit.recordPayment(customerId, 1800, "Cash received", requestId = "repayment-once")
        } }.awaitAll() }
        assertTrue(requests.all { it is Result.Success })
        assertEquals(10000L, credit.getBalanceForCustomer(customerId))
        assertTrue(credit.recordPayment(customerId, 1801, "Cash received", requestId = "repayment-once") is Result.Error)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM audit_logs WHERE actionType='CREDIT_PAYMENT'").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }
    @Test fun failedRepaymentAuditRollsBackMoneyAndCanBeRetriedSafely() = runBlocking {
        sale(method = PaymentMethod.CREDIT, customer = customerId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_credit_audit BEFORE INSERT ON audit_logs WHEN NEW.actionType='CREDIT_PAYMENT' BEGIN SELECT RAISE(ABORT, 'audit failure'); END")
        assertTrue(credit.recordPayment(customerId, 1800, "Cash received", requestId = "audit-retry") is Result.Error)
        assertEquals(11800L, credit.getBalanceForCustomer(customerId))
        assertNull(db.settingsDao().getSetting("credit-payment:audit-retry"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_credit_audit")
        repeat(2) { assertTrue(credit.recordPayment(customerId, 1800, "Cash received", requestId = "audit-retry") is Result.Success) }
        assertEquals(10000L, credit.getBalanceForCustomer(customerId))
    }
    @Test fun boxConversionBillsAndDeductsTheCombinedPieceQuantity() = runBlocking {
        val entity = db.productDao().getProductById(productId)!!.copy(unit = "Piece", piecesPerBox = 100,
            sellingPricePaisa = 125, currentStock = 500.0)
        db.productDao().updateProduct(entity)
        val p = entity.toDomain()
        val quantity = BoxQuantity.pieces("2", p.piecesPerBox)!! + 5
        val result = billing.createBill(listOf(CartItem(p, quantity, 125, 25625)), null, "", "", 0, "",
            false, PaymentMethod.CASH, 0, "", checkoutId = "box-sale")
        assertTrue(result.toString(), result is Result.Success)
        val bill = (result as Result.Success).data
        assertEquals(25625L, bill.totalPaisa)
        assertEquals(205.0, bill.items.single().quantity, 0.0)
        assertEquals("Piece", bill.items.single().productUnit)
        assertEquals(295.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        assertTrue(billing.cancelBill(bill.id, "Test return") is Result.Success)
        assertEquals(500.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    private suspend fun sale(key: String = java.util.UUID.randomUUID().toString(), method: PaymentMethod = PaymentMethod.CASH,
                             customer: Long? = null, discount: Long = 0, paid: Long = 0): Result<Bill> {
        val product = db.productDao().getProductById(productId)!!.toDomain()
        return billing.createBill(listOf(CartItem(product, 1.0, 10000, 10000)), customer, "Customer", "9876543210", discount,
            "", true, method, paid, "", checkoutId = key, gstInclusive = false)
    }

    @Test fun creditRejectsMissingNameOrPhoneInRepository() = runBlocking {
        val product = db.productDao().getProductById(productId)!!.toDomain()
        for ((name, phone) in listOf("" to "9876543210", "Customer" to "")) {
            val result = billing.createBill(listOf(CartItem(product, 1.0, 10000, 10000)), customerId,
                name, phone, 0, "", false, PaymentMethod.CREDIT, 0, "")
            assertTrue(result is Result.Error)
        }
        assertTrue(db.billDao().getAllBills().isEmpty())
    }

    @Test fun manualLinesSaveForAllPaymentsWithoutCatalogueOrStockChanges() = runBlocking {
        val line = ManualBillItem.create("Unlisted fitting", "118", emptyList())
        for (method in PaymentMethod.values()) {
            val key = "manual-${method.name}"
            suspend fun save() = billing.createBill(listOf(line), if (method == PaymentMethod.CREDIT) customerId else null,
                "Customer", "9876543210", 0, "", true, method, 0, "", checkoutId = key,
                paymentSplit=if(method==PaymentMethod.SPLIT)com.hardwareshop.pos.util.PaymentSplit(6000,5800) else null)
            val first = save()
            assertTrue(first.toString(), first is Result.Success)
            val bill = (first as Result.Success).data
            assertEquals(11800L, bill.totalPaisa)
            assertEquals(0L, bill.items.single().productId)
            assertEquals("Unlisted fitting", bill.items.single().productName)
            assertEquals(bill.id, (save() as Result.Success).data.id)
        }
        assertEquals(1, db.productDao().getAllProducts().size)
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }

    @Test fun workerCannotSubmitOwnerPriceOverride() = runBlocking {
        val id = db.workerDao().insertWorker(WorkerEntity(name = "Worker", username = "worker", passwordHash = "test", passwordSalt = "test"))
        session.setSession(UserSession.WorkerSession(id, "Worker", "worker", 0, true))
        val product = db.productDao().getProductById(productId)!!.toDomain()
        val result = billing.createBill(listOf(CartItem(product, 1.0, 5000, 5000)), null,
            "", "", 0, "", false, PaymentMethod.CASH, 5000, "")
        assertTrue(result is Result.Error)
        assertTrue(db.billDao().getAllBills().isEmpty())
    }

    @Test fun workerCanSaveManualLineButNoStockChanges() = runBlocking {
        val id = db.workerDao().insertWorker(WorkerEntity(name = "Worker", username = "manual-worker", passwordHash = "test", passwordSalt = "test"))
        session.setSession(UserSession.WorkerSession(id, "Worker", "manual-worker", 0, true))
        val result = billing.createBill(listOf(ManualBillItem.create("Loose item", "50", emptyList())), null,
            "", "", 0, "", false, PaymentMethod.CASH, 5000, "")
        assertTrue(result.toString(), result is Result.Success)
        assertEquals(5000L, (result as Result.Success).data.totalPaisa)
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }

    @Test fun concurrentRetriesCreateOneBillAndOneStockDeduction() = runBlocking {
        val bills = coroutineScope { List(5) { async(Dispatchers.IO) { sale("one-checkout") } }.awaitAll() }
        assertTrue(bills.all { it is Result.Success })
        assertEquals(1, bills.map { (it as Result.Success).data.id }.distinct().size)
        assertEquals(9.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        assertEquals(1, db.billDao().getAllBills().size)
    }
    @Test fun concurrentCancellationReversesStockAndCreditOnce() = runBlocking {
        val bill = (sale(method = PaymentMethod.CREDIT, customer = customerId) as Result.Success).data
        val results = coroutineScope { List(5) { async(Dispatchers.IO) { billing.cancelBill(bill.id, "Customer cancelled") } }.awaitAll() }
        assertEquals(1, results.count { it is Result.Success })
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        assertEquals(0L, credit.getBalanceForCustomer(customerId))
        billing.markBillPrinted(bill.id)
        assertEquals("CANCELLED", db.billDao().getBillById(bill.id)!!.status)
    }
    @Test fun creditNeedsCustomerAndCannotBeOverpaid() = runBlocking {
        assertTrue(sale(method = PaymentMethod.CREDIT) is Result.Error)
        sale(method = PaymentMethod.CREDIT, customer = customerId)
        assertTrue(credit.recordPayment(customerId, 11801, "Too much") is Result.Error)
        assertTrue(credit.recordPayment(customerId, 1800, "Partial") is Result.Success)
        assertEquals(10000L, db.creditDao().getTotalOutstanding())
        assertTrue(credit.recordPayment(customerId, 10000, "Rest") is Result.Success)
        assertTrue(credit.recordPayment(customerId, 1, "Already settled") is Result.Error)
    }
    @Test fun repaidCreditBillMustUseReturnSettlementWithoutLosingRefund() = runBlocking {
        val bill = (sale(method = PaymentMethod.CREDIT, customer = customerId) as Result.Success).data
        assertTrue(credit.recordPayment(customerId, 4000, "Cash received") is Result.Success)
        val result = billing.cancelBill(bill.id, "Customer returned goods")
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message.contains("Return / Exchange"))
        assertEquals("ACTIVE", db.billDao().getBillById(bill.id)!!.status)
        assertEquals(9.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        assertEquals(7800L, credit.getBalanceForCustomer(customerId))
        assertEquals(2, db.creditDao().getAllTransactions().size)
        val trade = TradeRepository(db, billing, session, AuditRepository(db.auditLogDao(), session))
        val lines = listOf(ReturnLine(db.billDao().getBillItems(bill.id).single().id, 1.0))
        val quote = trade.quote(bill.id, lines, emptyList())
        assertEquals(7800L, quote.debtReduction); assertEquals(4000L, quote.refund)
        trade.returnSale("settlement", bill.id, lines, emptyList(), "CASH", "Goods returned", quote)
        trade.returnSale("settlement", bill.id, lines, emptyList(), "CASH", "Goods returned", quote)
        assertEquals(0L, credit.getBalanceForCustomer(customerId))
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        assertEquals(4000L, db.tradeDao().returns().single().refundPaisa)
    }
    @Test fun fullyRepaidCreditBillCannotCreateNegativeBalanceOnCancellation() = runBlocking {
        val bill = (sale(method = PaymentMethod.CREDIT, customer = customerId) as Result.Success).data
        assertTrue(credit.recordPayment(customerId, bill.totalPaisa, "Paid in full") is Result.Success)
        assertTrue(billing.cancelBill(bill.id, "Cancel") is Result.Error)
        assertEquals(0L, credit.getBalanceForCustomer(customerId))
        assertEquals("ACTIVE", db.billDao().getBillById(bill.id)!!.status)
        assertEquals(9.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun cashReceivedAtCreditCheckoutRequiresReturnEvenWithoutLaterRepayment() = runBlocking {
        for (paid in listOf(4000L, 11800L)) {
            val bill = (sale(method = PaymentMethod.CREDIT, customer = customerId, paid = paid) as Result.Success).data
            val before = credit.getBalanceForCustomer(customerId)
            assertFalse(db.creditDao().hasCustomerSettlements(customerId))
            assertTrue(billing.cancelBill(bill.id, "Cancel") is Result.Error)
            assertEquals(before, credit.getBalanceForCustomer(customerId))
            assertEquals("ACTIVE", db.billDao().getBillById(bill.id)!!.status)
        }
    }
    @Test fun customerRepaymentIsNotAssumedToBelongToAnotherBill() = runBlocking {
        val first = (sale(method = PaymentMethod.CREDIT, customer = customerId) as Result.Success).data
        sale(method = PaymentMethod.CREDIT, customer = customerId)
        credit.recordPayment(customerId, 4000, "Unallocated repayment")
        assertTrue(credit.getBalanceForCustomer(customerId) > first.creditPaisa)
        assertTrue(billing.cancelBill(first.id, "Cancel") is Result.Error)
        assertEquals(19600L, credit.getBalanceForCustomer(customerId))
        assertEquals(8.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun staleStockDialogCannotOverwriteSale() = runBlocking {
        sale()
        assertTrue(stock.adjustStock(productId, 20.0, "Count", expectedStock = 10.0) is Result.Error)
        assertTrue(db.stockDao().getAllAdjustments().isEmpty())
        assertEquals(9.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun lineTaxMatchesDiscountedInvoiceAndClosingPersists() = runBlocking {
        val bill = (sale(discount = 1000) as Result.Success).data
        assertEquals(1620L, bill.gstAmountPaisa)
        assertEquals(bill.gstAmountPaisa, bill.items.sumOf { it.gstAmountPaisa })
        val tools = OwnerToolsRepository(db, session)
        tools.closeToday(); tools.closeToday()
        assertEquals(1, db.billDao().getAllClosings().size)
        assertEquals(bill.totalPaisa, db.billDao().getAllClosings().single().totalSalesPaisa)
    }

    @Test fun backupRestoresPhotoAndClosingAndClearsUnfinishedCart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = java.io.File(context.filesDir, "product_images/test_${java.util.UUID.randomUUID()}.jpg")
        photo.parentFile!!.mkdirs(); photo.writeBytes(byteArrayOf(1, 2, 3, 4))
        val product = db.productDao().getProductById(productId)!!
        db.productDao().updateProduct(product.copy(imagePath = "product_images/${photo.name}"))
        val bill = (sale() as Result.Success).data
        OwnerToolsRepository(db, session).closeToday()
        val backup = BackupManager(context, db, AuditRepository(db.auditLogDao(), session), session)
        val uri = (backup.createBackup() as Result.Success).data
        db.settingsDao().upsertSetting(SettingsEntity("cart:owner", "old draft"))
        assertTrue(backup.restoreBackup(uri) is Result.Success)
        val restored = db.productDao().getProductById(productId)!!
        assertArrayEquals(photo.readBytes(), java.io.File(context.filesDir, restored.imagePath!!).readBytes())
        assertEquals(bill.totalPaisa, db.billDao().getAllClosings().single().totalSalesPaisa)
        assertNull(db.settingsDao().getSetting("cart:owner"))
        assertTrue((db.settingsDao().getSetting("bill_sequence")!!.value.toLong()) >= bill.billNumber.toLong())
    }

    @Test fun incompleteBackupIsRejectedBeforeExistingProductsChange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "broken_${java.util.UUID.randomUUID()}.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            it.write("""{"magic":"HARDWARE_SHOP_POS_BACKUP","version":4}""".toByteArray())
            it.closeEntry()
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val backup = BackupManager(context, db, AuditRepository(db.auditLogDao(), session), session)
        assertTrue(backup.restoreBackup(uri) is Result.Error)
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        file.delete()
        Unit
    }
}
