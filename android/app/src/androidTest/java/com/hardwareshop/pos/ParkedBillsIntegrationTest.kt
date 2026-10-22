package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.worker.billing.BillingViewModel
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ParkedBillsIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var billing: BillingRepository
    private lateinit var products: ProductRepository
    private lateinit var customers: CustomerRepository
    private val vms = mutableListOf<BillingViewModel>()
    private var productId = 0L
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Owner")) }
        val audit = AuditRepository(db.auditLogDao(), session)
        products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(), audit, session)
        customers = CustomerRepository(db.customerDao(), db.creditDao(), session)
        billing = BillingRepository(db, db.billDao(), db.productDao(), db.creditDao(), audit, BillNumberGenerator(db),
            SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), session), session)
        val cat = db.categoryDao().insertCategory(CategoryEntity(name = "Tools"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = cat, name = "Pipe"))
        productId = db.productDao().insertProduct(ProductEntity(categoryId = cat, typeId = type, name = "Pipe", unit = "Meter", sellingPricePaisa = 100, currentStock = 10.0))
    }
    @After fun close() = runBlocking { withContext(Dispatchers.Main) { vms.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }; db.close() }
    private suspend fun vm(): BillingViewModel {
        val vm = withContext(Dispatchers.Main) { BillingViewModel(billing, customers, products, db.settingsDao(), SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), session), db, session).also { vms.add(it) } }
        withTimeout(5000) { vm.state.first { !it.isLoading } }; return vm
    }
    private suspend fun done(vm: BillingViewModel) { withTimeout(5000) { vm.state.first { !it.isLoading } } }
    @Test fun directCashAndUpiSkipCustomerTaxAndPreviewAndRequestPrintingOnce() = runBlocking {
        db.productDao().updateProduct(db.productDao().getProductById(productId)!!.copy(gstRateBp = 1800))
        val customerId = db.customerDao().insertCustomer(CustomerEntity(name = "Previous customer", phone = "9876543210", gstNumber = "Customer GST"))
        for (method in listOf(PaymentMethod.CASH, PaymentMethod.UPI)) {
            val vm = vm()
            withContext(Dispatchers.Main) {
                vm.addToCart(products.getProductById(productId)!!, 2.0)
                vm.selectCustomer(customers.getCustomerById(customerId)!!)
                vm.setGstInclusive(false)
                vm.setDiscount(50, "Approved")
                vm.setPaymentMethod(PaymentMethod.CREDIT)
                vm.saveDirectBill(method)
                vm.saveDirectBill(method)
            }
            done(vm)
            val bill = billing.getBillById(requireNotNull(vm.state.value.savedBillId))!!
            assertEquals(150L, bill.totalPaisa)
            assertEquals(150L, bill.paidPaisa)
            assertEquals(0L, bill.creditPaisa)
            assertEquals(method, bill.paymentMethod)
            assertFalse(bill.gstEnabled)
            assertEquals(0L, bill.gstAmountPaisa)
            assertNull(bill.customer)
            assertEquals("", bill.customerName)
            assertEquals("", bill.customerPhone)
            assertEquals("", bill.customerGstNumber)
            assertTrue(vm.state.value.printAfterSave)
            assertNull(vm.state.value.previewLines)
            withContext(Dispatchers.Main) { vm.saveDirectBill(method) }
        }
        assertEquals(2, db.billDao().getAllBills().size)
        assertEquals(2, db.billDao().getAllPayments().size)
        assertTrue(db.creditDao().getAllTransactions().isEmpty())
        assertEquals(6.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun directBillRejectsCreditAndEmptyCartWithoutSavingOrChangingNormalCheckout() = runBlocking {
        val vm = vm()
        withContext(Dispatchers.Main) { vm.saveDirectBill(PaymentMethod.CASH) }
        done(vm)
        assertNull(vm.state.value.savedBillId)
        withContext(Dispatchers.Main) {
            vm.addToCart(products.getProductById(productId)!!, 1.0)
            vm.saveDirectBill(PaymentMethod.CREDIT)
        }
        assertTrue(vm.state.value.gstEnabled)
        assertEquals(PaymentMethod.CASH, vm.state.value.paymentMethod)
        assertNull(vm.state.value.savedBillId)
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun directBillStillChecksTheWorkersCurrentDiscountLimit() = runBlocking {
        val worker = WorkerEntity(name = "Worker", username = "direct-worker", passwordHash = "test",
            passwordSalt = "test", maxDiscountPaisa = 100)
        val workerId = db.workerDao().insertWorker(worker)
        session.setSession(UserSession.WorkerSession(workerId, "Worker", "direct-worker", 100, true))
        val vm = vm()
        withContext(Dispatchers.Main) {
            vm.addToCart(products.getProductById(productId)!!, 1.0)
            vm.setDiscount(50, "Earlier limit")
        }
        db.workerDao().updateWorker(worker.copy(id = workerId, maxDiscountPaisa = 0))
        withContext(Dispatchers.Main) { vm.saveDirectBill(PaymentMethod.CASH) }
        done(vm)
        assertNull(vm.state.value.savedBillId)
        assertNotNull(vm.state.value.error)
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun directDiscountOverrideIsSavedForCashAndUpiWithoutChangingTheNormalForm() = runBlocking {
        for ((method, discount) in listOf(PaymentMethod.CASH to 50L, PaymentMethod.UPI to 200L)) {
            val vm = vm()
            withContext(Dispatchers.Main) {
                vm.addToCart(products.getProductById(productId)!!, 2.0)
                vm.setDiscount(25, "Normal bill")
                vm.saveDirectBill(method, discount)
                vm.saveDirectBill(method, discount)
            }
            done(vm)
            val bill = billing.getBillById(requireNotNull(vm.state.value.savedBillId))!!
            assertEquals(discount, bill.discountPaisa)
            assertEquals(200L - discount, bill.totalPaisa)
            assertEquals(bill.totalPaisa, bill.paidPaisa)
            assertEquals(0L, bill.gstAmountPaisa)
            assertEquals("Direct bill discount", bill.discountNote)
            assertEquals(25L, vm.state.value.discountPaisa)
            assertTrue(vm.state.value.printAfterSave)
        }
        assertEquals(2, db.billDao().getAllBills().size)
        assertEquals(6.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun directDiscountRejectsNegativeAndExcessWithoutChangingStockOrDraft() = runBlocking {
        val vm = vm()
        withContext(Dispatchers.Main) { vm.addToCart(products.getProductById(productId)!!, 1.0) }
        for (discount in listOf(-1L, 101L, Long.MAX_VALUE)) {
            assertNull(vm.state.value.directBillCalculation(discount))
            withContext(Dispatchers.Main) { vm.saveDirectBill(PaymentMethod.CASH, discount) }
            done(vm)
            assertNull(vm.state.value.savedBillId)
            assertNotNull(vm.state.value.error)
            assertEquals(0L, vm.state.value.discountPaisa)
        }
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun deniedDirectDiscountCanBeCorrectedWithoutBypassingWorkerLimit() = runBlocking {
        val workerId = db.workerDao().insertWorker(WorkerEntity(name = "Worker", username = "limited",
            passwordHash = "test", passwordSalt = "test", maxDiscountPaisa = 50))
        session.setSession(UserSession.WorkerSession(workerId, "Worker", "limited", 50, true))
        val vm = vm()
        withContext(Dispatchers.Main) {
            vm.addToCart(products.getProductById(productId)!!, 1.0)
            vm.saveDirectBill(PaymentMethod.CASH, 75)
        }
        done(vm)
        assertNull(vm.state.value.savedBillId)
        assertNotNull(vm.state.value.error)
        assertEquals(0L, vm.state.value.discountPaisa)
        assertTrue(db.billDao().getAllBills().isEmpty())
        withContext(Dispatchers.Main) { vm.saveDirectBill(PaymentMethod.CASH, 50) }
        done(vm)
        assertEquals(50L, billing.getBillById(requireNotNull(vm.state.value.savedBillId))!!.totalPaisa)
        assertEquals(9.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
    }
    @Test fun upiPreviewRequestsExactAmountButDoesNotSavePaymentOrDeductStock() = runBlocking {
        db.settingsDao().upsertSetting(SettingsEntity("merchant_upi_id", "audit@upi"))
        db.settingsDao().upsertSetting(SettingsEntity("merchant_upi_name", "Audit Shop"))
        val vm = vm()
        withContext(Dispatchers.Main) {
            vm.addToCart(products.getProductById(productId)!!, 2.0)
            vm.setPaymentMethod(PaymentMethod.UPI)
            vm.previewBill()
        }
        done(vm)
        val uri = android.net.Uri.parse(requireNotNull(vm.state.value.previewQrUri))
        assertEquals("2.00", uri.getQueryParameter("am"))
        assertEquals("audit@upi", uri.getQueryParameter("pa"))
        assertNull(vm.state.value.savedBillId)
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertTrue(db.billDao().getAllPayments().isEmpty())
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        withContext(Dispatchers.Main) {
            vm.closePreview()
            vm.setPaymentMethod(PaymentMethod.CASH)
            vm.previewBill()
        }
        done(vm)
        assertNotNull(vm.state.value.previewLines)
        assertNull(vm.state.value.previewQrUri)
    }
    @Test fun workerManualItemStaysInThisCartAndAppearsForReviewOnlyAfterCheckout() = runBlocking {
        val workerId = db.workerDao().insertWorker(WorkerEntity(name = "Worker", username = "worker", passwordHash = "test", passwordSalt = "test"))
        session.setSession(UserSession.WorkerSession(workerId, "Worker", "worker", 0, true))
        val vm = vm()
        withContext(Dispatchers.Main) {
            vm.addManualItem("Loose fitting", "10") { }
            val id = vm.state.value.cart.single().product.id
            assertTrue(vm.editManualItem(id, "Special fitting", "12.50"))
            vm.updateQuantity(id, 2.0)
        }
        assertEquals(2500L, vm.state.value.calculation!!.totalPaisa)
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertTrue(db.settingsDao().getAllSettings().none { it.key.startsWith("manual-product:") })
        withContext(Dispatchers.Main) { vm.saveBill() }; done(vm)
        assertEquals("Special fitting", db.billDao().getAllBillItems().single().productName)
        assertEquals(2500L, db.billDao().getAllBills().single().totalPaisa)
        session.setSession(UserSession.OwnerSession("Owner"))
        val review = ManualProductRepository(db, products, session).observeBills().first().single()
        assertEquals(db.billDao().getAllBills().single().id, review.bill.id)
        assertEquals("Special fitting", review.manualItems.single().name)
        assertEquals(1, db.productDao().getAllProducts().size)
        Unit
    }
    @Test fun ownerPriceAndPreviewMatchSavedAnonymousCashBill() = runBlocking {
        val vm = vm()
        val product = products.getProductById(productId)!!
        withContext(Dispatchers.Main) {
            vm.addToCart(product, 2.0)
            vm.setItemPrice(productId, "2.50")
            vm.previewBill()
        }
        done(vm)
        assertEquals(500L, vm.state.value.calculation!!.totalPaisa)
        assertEquals(0L, vm.state.value.calculation!!.gstAmountPaisa)
        assertNotNull(vm.state.value.previewLines)
        assertEquals(0, db.billDao().getAllBills().size)
        assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        withContext(Dispatchers.Main) { vm.saveBill() }
        done(vm)
        val saved = billing.getBillById(requireNotNull(vm.state.value.savedBillId))!!
        assertEquals("", saved.customerName)
        assertEquals(500L, saved.totalPaisa)
        assertEquals(250L, saved.items.single().unitPricePaisa)
        assertEquals(100L, db.productDao().getProductById(productId)!!.sellingPricePaisa)
    }

    @Test fun creditWithoutCustomerNameAndPhoneCannotPreviewOrSave() = runBlocking {
        val vm = vm()
        withContext(Dispatchers.Main) {
            vm.addToCart(products.getProductById(productId)!!, 1.0)
            vm.setPaymentMethod(PaymentMethod.CREDIT)
            vm.previewBill()
            vm.saveBill()
        }
        done(vm)
        assertNull(vm.state.value.previewLines)
        assertNull(vm.state.value.savedBillId)
        assertNotNull(vm.state.value.error)
        assertTrue(db.billDao().getAllBills().isEmpty())
    }
    @Test fun multipleParkedBillsSurviveRecreationAndDoNotChangeStock() = runBlocking {
        val vm = vm(); val product = products.getProductById(productId)!!
        withContext(Dispatchers.Main) { vm.addToCart(product, 1.0); vm.parkCurrent("Customer A") }; done(vm)
        withContext(Dispatchers.Main) { vm.addToCart(product, 2.0); vm.parkCurrent("Customer B") }; done(vm)
        val parked = db.settingsDao().getAllSettings().filter { it.key.startsWith("cart:owner:parked:") }
        assertEquals(2, parked.size); assertEquals(10.0, db.productDao().getProductById(productId)!!.currentStock, 0.0)
        withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
        val next = vm(); val first = parked.first { Gson().fromJson(it.value, SavedCart::class.java).label == "Customer A" }
        withContext(Dispatchers.Main) { next.resumeParked(first.key) }; done(next)
        assertEquals(1.0, next.state.value.cart.single().quantity, 0.0)
        assertFalse(next.state.value.draftMessage!!.contains("previous cart was parked"))
        assertEquals(1, db.settingsDao().getAllSettings().count { it.key.startsWith("cart:owner:parked:") })
    }
    @Test fun switchingParksCurrentCartAndUsesLatestPrice() = runBlocking {
        val vm = vm(); val product = products.getProductById(productId)!!
        withContext(Dispatchers.Main) { vm.addToCart(product, 1.0); vm.parkCurrent("A") }; done(vm)
        val key = db.settingsDao().getAllSettings().first { it.key.startsWith("cart:owner:parked:") }.key
        val entity = db.productDao().getProductById(productId)!!
        db.productDao().updateProduct(entity.copy(sellingPricePaisa = 200))
        withContext(Dispatchers.Main) { vm.addToCart(product, 2.0); vm.resumeParked(key) }; done(vm)
        assertEquals(200L, vm.state.value.cart.single().unitPricePaisa)
        val parked = db.settingsDao().getAllSettings().single { it.key.startsWith("cart:owner:parked:") }
        assertEquals(2.0, Gson().fromJson(parked.value, SavedCart::class.java).items.single().quantity, 0.0)
    }
    @Test fun anotherUsersParkedCartCannotBeResumedOrDeleted() = runBlocking {
        val vm = vm()
        db.settingsDao().upsertSetting(SettingsEntity("cart:99:parked:other", "{}"))
        withContext(Dispatchers.Main) { vm.resumeParked("cart:99:parked:other") }; done(vm)
        assertNotNull(vm.state.value.error)
        withContext(Dispatchers.Main) { vm.discardParked("cart:99:parked:other") }; done(vm)
        assertNotNull(db.settingsDao().getSetting("cart:99:parked:other"))
    }
    @Test fun corruptParkedBillIsKeptAndCurrentCartIsNotLost() = runBlocking {
        val vm = vm(); val product = products.getProductById(productId)!!
        db.settingsDao().upsertSetting(SettingsEntity("cart:owner:parked:bad", "not-json"))
        withContext(Dispatchers.Main) { vm.addToCart(product, 2.0); vm.resumeParked("cart:owner:parked:bad") }; done(vm)
        assertEquals(2.0, vm.state.value.cart.single().quantity, 0.0)
        assertNotNull(db.settingsDao().getSetting("cart:owner:parked:bad"))
    }
    @Test fun unavailableParkedProductPreservesBothCartsAndCanBeRetried() = runBlocking {
        val vm = vm(); val product = products.getProductById(productId)!!
        withContext(Dispatchers.Main) { vm.addToCart(product, 1.0); vm.parkCurrent("A") }; done(vm)
        val row = db.settingsDao().getAllSettings().single { it.key.startsWith("cart:owner:parked:") }
        val entity = db.productDao().getProductById(productId)!!
        db.productDao().updateProduct(entity.copy(isActive = false))
        withContext(Dispatchers.Main) { vm.addToCart(product, 2.0); vm.resumeParked(row.key) }; done(vm)
        assertEquals(row.value, db.settingsDao().getSetting(row.key)!!.value)
        assertEquals(2.0, vm.state.value.cart.single().quantity, 0.0)
        assertTrue(vm.state.value.error!!.contains("reactivate"))
        db.productDao().updateProduct(entity)
        withContext(Dispatchers.Main) { vm.resumeParked(row.key) }; done(vm)
        assertEquals(1.0, vm.state.value.cart.single().quantity, 0.0)
        assertNull(db.settingsDao().getSetting(row.key))
    }
    @Test fun unavailableActiveProductDoesNotEraseSavedDraftDuringAutosave() = runBlocking {
        val vm = vm(); val product = products.getProductById(productId)!!
        withContext(Dispatchers.Main) { vm.addToCart(product, 1.0); vm.holdBill {} }; done(vm)
        val before = db.settingsDao().getSetting("cart:owner")!!.value
        withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
        val entity = db.productDao().getProductById(productId)!!
        db.productDao().updateProduct(entity.copy(isActive = false))
        val recovered = vm()
        assertTrue(recovered.state.value.recoveryFailed)
        delay(600) // Allow the debounced autosave to run if recovery incorrectly enabled it.
        assertEquals(before, db.settingsDao().getSetting("cart:owner")!!.value)
        withContext(Dispatchers.Main) { recovered.viewModelScope.cancel() }
        db.productDao().updateProduct(entity)
        val retry = vm()
        assertFalse(retry.state.value.recoveryFailed)
        assertEquals(1.0, retry.state.value.cart.single().quantity, 0.0)
    }
}
