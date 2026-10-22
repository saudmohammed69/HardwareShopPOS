package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.dao.*
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// BILLING REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class BillingRepository @Inject constructor(
    private val db: AppDatabase,
    private val billDao: BillDao,
    private val productDao: ProductDao,
    private val creditDao: CreditDao,
    private val auditRepository: AuditRepository,
    private val billNumberGenerator: BillNumberGenerator,
    private val settingsRepository: SettingsRepository,
    private val session: SessionHolder
) {
    /**
     * The core billing transaction — ATOMIC.
     *
     * Steps (all-or-nothing inside a single Room transaction):
     *  1. Validate cart: non-empty, all quantities positive, all products exist
     *  2. Stock check (warn but allow — hardware shops sometimes oversell)
     *  3. Generate bill number
     *  4. Build and persist BillEntity
     *  5. Persist BillItemEntity list
     *  6. Reduce stock for each line item
     *  7. Persist PaymentEntity (cash / UPI / card)
     *  8. If CREDIT — read prior balance, create CreditTransactionEntity
     *  9. Persist AuditLog
     *
     * If any step throws, Room rolls back everything; no partial state is
     * committed to the database.  The bill is never lost due to a print failure
     * because print happens AFTER this method returns successfully.
     */
    suspend fun createBill(
        cartItems: List<CartItem>,
        customerId: Long?,
        customerName: String,
        customerPhone: String,
        discountPaisa: Long,
        discountNote: String,
        gstEnabled: Boolean,
        paymentMethod: PaymentMethod,
        paidPaisa: Long,
        notes: String,
        customerGstNumber: String = "",
        checkoutId: String = java.util.UUID.randomUUID().toString(),
        gstInclusive: Boolean = true,
        sourceQuotationId: String? = null,
        paymentSplit: PaymentSplit? = null
    ): Result<Bill> {

        return try {
            PermissionChecker.enforceLogin(session.current)
            require(checkoutId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid checkout reference" }
            val savedBill = db.withTransaction {
                val checkoutKey = "checkout:$checkoutId"
                db.settingsDao().getSetting(checkoutKey)?.value?.toLongOrNull()?.let { id ->
                    return@withTransaction requireNotNull(getBillById(id)) { "Saved bill is unavailable" }
                }
                sourceQuotationId?.let { quoteId ->
                    PermissionChecker.enforceOwner(session.current)
                    require(quoteId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid quotation reference" }
                    require(db.settingsDao().getSetting("quotation:$quoteId") != null) { "Quotation is no longer available" }
                    require(db.settingsDao().getSetting("quotation-bill:$quoteId") == null) { "This quotation already has a bill" }
                }
                require(cartItems.isNotEmpty()) { "Cart is empty" }
                require(cartItems.all { it.quantity.isFinite() && it.quantity > 0 }) { "Enter valid quantities" }
                require(cartItems.map { it.product.id }.distinct().size == cartItems.size) { "Duplicate cart lines" }
                require(discountPaisa >= 0 && paidPaisa >= 0) { "Amounts cannot be negative" }
                val workerSession = session.current as? UserSession.WorkerSession
                workerSession?.let { worker ->
                    val active = db.workerDao().getWorkerById(worker.workerId)
                    require(active?.isActive == true) { "Worker account is disabled. Log in again." }
                    require(!active.requireOwnerApprovalForDiscount || discountPaisa <= active.maxDiscountPaisa) {
                        "Discount exceeds the worker's current limit"
                    }
                }
                cartItems.forEach { item ->
                    if (item.product.id < 0) {
                        ManualBillItem.validate(item)
                        return@forEach
                    }
                    val current = productDao.getProductById(item.product.id)
                    require(current?.isActive == true) { "${item.product.name} is no longer available" }
                    if (item.product.isPipe) {
                        require(current.isPipe && current.pipeFeetPerLength == item.product.pipeFeetPerLength && current.unit == item.product.unit && current.gstRateBp == item.product.gstRateBp) { "Pipe configuration changed. Remove and select it again." }
                        require(session.current is UserSession.OwnerSession || (current.pipeLengthPricePaisa == item.unitPricePaisa && current.pipeFootPricePaisa == item.pipeFootPricePaisa)) { "Pipe prices changed. Remove and select it again." }
                        val verified = PipeBilling.item(item.product, item.pipeLengths, item.pipeFeet, item.unitPricePaisa, item.pipeFootPricePaisa)
                        require(verified.quantity == item.quantity && verified.itemTotalPaisa == item.itemTotalPaisa) { "Invalid pipe bill line" }
                        return@forEach
                    }
                    require(!current.isPipe) { "Select the pipe using Length No. and Ft No." }
                    require(item.unitPricePaisa > 0) { "Selling price must be greater than zero" }
                    require((session.current is UserSession.OwnerSession || current.sellingPricePaisa == item.unitPricePaisa) &&
                        current.gstRateBp == item.product.gstRateBp && current.unit == item.product.unit) {
                        "Price, unit or tax changed for ${item.product.name}. Remove it and add it again."
                    }
                }
                val customer = customerId?.let { db.customerDao().getCustomerById(it) }
                require(customerId == null || customer?.isActive == true) { "Customer is no longer active" }
                require(paymentMethod != PaymentMethod.CREDIT || customer != null) { "Select a customer for credit" }
                require(paymentMethod != PaymentMethod.CREDIT || (customerName.isNotBlank() && customerPhone.isNotBlank())) {
                    "Customer name and phone are required for credit"
                }
                val calcItems = cartItems.map {
                    PipeBilling.calculation(it)
                }
                val originalSubtotal = calcItems.map {
                    it.amountOverridePaisa ?: CalculationEngine.itemTotalPaisa(it.quantity, it.unitPricePaisa)
                }.fold(0L, Math::addExact)
                require(discountPaisa <= originalSubtotal) { "Discount exceeds subtotal" }
                // Product selling prices are customer-facing prices. With inclusive GST,
                // split the tax out of that price instead of adding tax on top of it.
                val calc = if (gstEnabled && gstInclusive)
                    CalculationEngine.calculateBillInclusive(calcItems, discountPaisa)
                else
                    CalculationEngine.calculateBill(calcItems, discountPaisa, gstEnabled)
                require(paymentMethod != PaymentMethod.CREDIT || paidPaisa <= calc.totalPaisa) { "Paid amount exceeds total" }
                if (paymentMethod == PaymentMethod.SPLIT) requireNotNull(paymentSplit) { "Enter cash and UPI amounts" }.validate(calc.totalPaisa)
                else require(paymentSplit==null) { "Split components require Split Cash + UPI" }
                val actualPaid = if (paymentMethod == PaymentMethod.CREDIT) paidPaisa else calc.totalPaisa
                val creditPaisa = calc.totalPaisa - actualPaid
                val billNumber = billNumberGenerator.next()
                val shopInfo = settingsRepository.getShopInfo()
                // 4. Bill
                val billEntity = BillEntity(
                    billNumber       = billNumber,
                    customerId       = customerId,
                    customerName     = customerName.trim(),
                    customerPhone    = customerPhone.trim(),
                    customerGstNumber = (customer?.gstNumber?.takeIf { it.isNotBlank() } ?: customerGstNumber).trim(),
                    workerId         = workerSession?.workerId,
                    workerName       = workerSession?.workerName ?: "Owner",
                    subtotalPaisa    = calc.subtotalPaisa,
                    discountPaisa    = calc.discountPaisa,
                    discountNote     = discountNote.trim(),
                    gstEnabled       = gstEnabled,
                    gstAmountPaisa   = calc.gstAmountPaisa,
                    totalPaisa       = calc.totalPaisa,
                    paymentMethod    = paymentMethod.name,
                    paidPaisa        = actualPaid,
                    creditPaisa      = creditPaisa,
                    shopGstNumber    = shopInfo.gstNumber,
                    notes            = notes.trim()
                )
                val savedBillId = billDao.insertBill(billEntity)

                // 5. Bill items
                val itemEntities = cartItems.mapIndexed { index, item ->
                    val itemTotal = PipeBilling.amount(item)
                    val gstAmt = calc.itemGstPaisa[index]
                    BillItemEntity(
                        billId         = savedBillId,
                        productId      = item.product.id.coerceAtLeast(0),
                        productName    = item.receiptName,
                        productSize    = item.product.size,
                        productUnit    = item.product.unit,
                        quantity       = item.quantity,
                        unitPricePaisa = item.unitPricePaisa,
                        gstRateBp      = item.product.gstRateBp,
                        itemTotalPaisa = itemTotal,
                        gstAmountPaisa = gstAmt,
                        isPipe = item.product.isPipe, pipeLengths = item.pipeLengths,
                        pipeFeet = item.pipeFeet, pipeFootPricePaisa = item.pipeFootPricePaisa
                    )
                }
                billDao.insertBillItems(itemEntities)

                PipeOffcutRepository(db,session).consumeForBill(cartItems,savedBillId)

                // 6. Reduce stock
                cartItems.filter { it.product.id > 0 }.forEach { item ->
                    productDao.decrementStock(item.product.id, item.quantity)
                }

                // 7. Payment record (for non-credit or partial credit)
                if (paymentMethod == PaymentMethod.SPLIT) {
                    val split=requireNotNull(paymentSplit)
                    listOf(PaymentMethod.CASH to split.cashPaisa,PaymentMethod.UPI to split.upiPaisa).forEach { (method,amount)->
                        if(amount>0) billDao.insertPayment(PaymentEntity(billId=savedBillId,method=method.name,amountPaisa=amount))
                    }
                } else if (actualPaid > 0) {
                    billDao.insertPayment(
                        PaymentEntity(
                            billId   = savedBillId,
                            method   = if (paymentMethod == PaymentMethod.CREDIT) PaymentMethod.CASH.name
                                       else paymentMethod.name,
                            amountPaisa = actualPaid
                        )
                    )
                }

                // 8. Credit transaction
                if (creditPaisa > 0 && customerId != null) {
                    val prevBalance = creditDao.getBalanceForCustomer(customerId)
                    creditDao.insertTransaction(
                        CreditTransactionEntity(
                            customerId           = customerId,
                            billId               = savedBillId,
                            type                 = CreditTransactionType.CREDIT_CREATED.name,
                            amountPaisa          = creditPaisa,
                            previousBalancePaisa = prevBalance,
                            newBalancePaisa      = Math.addExact(prevBalance, creditPaisa),
                            notes                = "Bill #$billNumber"
                        )
                    )
                }
                db.settingsDao().upsertSetting(SettingsEntity(checkoutKey, savedBillId.toString()))
                sourceQuotationId?.let { quoteId ->
                    db.settingsDao().upsertSetting(SettingsEntity("quotation-bill:$quoteId", savedBillId.toString()))
                }
                cartItems.filter { it.product.id>0 }.forEach { item ->
                    val original=productDao.getProductById(item.product.id)!!
                    val price=if(item.product.isPipe)original.pipeLengthPricePaisa else original.sellingPricePaisa
                    if(price!=item.unitPricePaisa || (item.product.isPipe && original.pipeFootPricePaisa!=item.pipeFootPricePaisa))
                        auditRepository.log(actionType="BILL_PRICE_OVERRIDDEN",entityType="PRODUCT",entityId=item.product.id,
                            productId=item.product.id,billId=savedBillId,beforeValue="unit=$price, foot=${original.pipeFootPricePaisa}",
                            afterValue="unit=${item.unitPricePaisa}, foot=${item.pipeFootPricePaisa}",reason="Current bill only")
                }
                auditRepository.log(actionType = "BILL_CREATED", entityType = "BILL",
                    entityId = savedBillId, billId = savedBillId, afterValue = "Bill #$billNumber")
                requireNotNull(getBillById(savedBillId))
            }
            Result.Success(savedBill)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.e(e, "createBill failed")
            Result.Error("Bill creation failed: ${e.message}", e)
        }
    }

    // ── Fetch ────────────────────────────────────────────────────────────────

    suspend fun getBillById(id: Long): Bill? {
        PermissionChecker.enforceLogin(session.current)
        val entity = billDao.getBillById(id) ?: return null
        val worker = session.current as? UserSession.WorkerSession
        require(worker == null || entity.workerId == worker.workerId) { "This bill belongs to another user" }
        val items   = billDao.getBillItems(id).map { it.toDomain() }
        val customer = entity.customerId?.let {
            val cEntity  = db.customerDao().getCustomerById(it)
            val balance  = creditDao.getBalanceForCustomer(it)
            cEntity?.toDomain(balance)
        }
        val payments=billDao.getPaymentsForBill(id).map { BillPayment(PaymentMethod.fromString(it.method),it.amountPaisa) }
        return entity.toDomain(items, customer).copy(payments=payments)
    }

    fun observeWorkerRecentBills(workerId: Long): Flow<List<BillEntity>> =
        billDao.observeWorkerRecentBills(workerId)

    suspend fun getBillsFiltered(
        from: Long = 0, to: Long = 0,
        workerId: Long = 0,
        method: String = "",
        status: String = "",
        limit: Int = 50,
        offset: Int = 0, query: String = ""
    ): List<BillEntity> {
        PermissionChecker.enforceOwner(session.current)
        return billDao.getBillsFiltered(from, to, workerId, method, status, limit, offset, query)
    }

    // ── Cancel (owner) ───────────────────────────────────────────────────────

    suspend fun cancelBill(billId: Long, reason: String): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        if (reason.isBlank()) return Result.Error("Cancellation reason is required")
        return try {
            db.withTransaction {
                val bill = requireNotNull(billDao.getBillById(billId)) { "Bill not found" }
                require(bill.status != "CANCELLED") { "Bill is already cancelled" }
                require(db.tradeDao().linkedReturns(billId) == 0) { "This bill is linked to a return or exchange. Use Returns for the remaining items; cancellation would reverse stock twice." }
                if (bill.paymentMethod == PaymentMethod.CREDIT.name) {
                    // Repayments/adjustments belong to the customer, not an individual invoice.
                    // Do not guess their allocation or turn money owed back into an untracked negative balance.
                    val customerId = requireNotNull(bill.customerId) { "Credit bill has no customer. Review the imported bill." }
                    require(bill.paidPaisa == 0L &&
                        !creditDao.hasCustomerSettlements(customerId) &&
                        creditDao.getBalanceForCustomer(customerId) >= bill.creditPaisa) {
                        "This credit bill has money received, or the customer has repayments/adjustments. Use Return / Exchange Items to record the debt reduction and any refund."
                    }
                }
                // Restore stock
                val items = billDao.getBillItems(billId)
                items.filter { it.productId > 0 }.forEach { item ->
                    productDao.decrementStock(item.productId, -item.quantity) // negative = restore
                }
                // If there's a credit component, reverse it
                if (bill.creditPaisa > 0 && bill.customerId != null) {
                    val prevBalance = creditDao.getBalanceForCustomer(bill.customerId)
                    creditDao.insertTransaction(
                        CreditTransactionEntity(
                            customerId           = bill.customerId,
                            billId               = billId,
                            type                 = CreditTransactionType.ADJUSTMENT.name,
                            amountPaisa          = -bill.creditPaisa,
                            previousBalancePaisa = prevBalance,
                            newBalancePaisa      = prevBalance - bill.creditPaisa,
                            notes                = "Reversal: Bill #${bill.billNumber} cancelled"
                        )
                    )
                }
                PipeOffcutRepository(db,session).restoreCancelledBill(billId)
                billDao.cancelBill(billId, reason)
            }

            auditRepository.log(
                actionType  = "BILL_CANCELLED",
                entityType  = "BILL",
                entityId    = billId,
                billId      = billId,
                beforeValue = "ACTIVE",
                afterValue  = "CANCELLED",
                reason      = reason
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "cancelBill failed")
            Result.Error("Failed to cancel bill: ${e.message}", e)
        }
    }

    // ── Dashboard aggregates ─────────────────────────────────────────────────

    suspend fun getDashboardSummary(fromMs: Long): DashboardSummary {
        PermissionChecker.enforceOwner(session.current)
        val totals = db.netSales(fromMs, System.currentTimeMillis() + 1)
        val total = totals.sales
        val count = totals.billCount
        val cash = totals.cash
        val upi = totals.upi
        val card = totals.card
        val credit = totals.credit
        val discount = totals.discounts
        val outstanding = creditDao.getTotalOutstanding()
        val cancelled   = billDao.getCancelledBillCountSince(fromMs)
        val lowStock    = productDao.observeLowStockProducts()
                            .first()
                            .size

        return DashboardSummary(
            totalSalesPaisa             = total,
            billCount                   = count,
            cashSalesPaisa              = cash,
            upiSalesPaisa               = upi,
            cardSalesPaisa              = card,
            creditSalesPaisa            = credit,
            totalDiscountsPaisa         = discount,
            totalOutstandingCreditPaisa = outstanding,
            lowStockCount               = lowStock,
            cancelledBillCount          = cancelled
        )
    }

    // ── Mark printed ─────────────────────────────────────────────────────────

    suspend fun markBillPrinted(billId: Long) {
        billDao.markPrinted(billId)
        auditRepository.log(actionType = "PRINT_OK", entityType = "BILL", billId = billId)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CREDIT REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CreditRepository @Inject constructor(
    private val db: AppDatabase,
    private val creditDao: CreditDao,
    private val auditRepository: AuditRepository,
    private val session: SessionHolder
) {
    fun observeTransactionsForCustomer(customerId: Long): Flow<List<CreditTransaction>> =
        creditDao.observeTransactionsForCustomer(customerId)
            .map { list -> list.map { it.toDomain() } }

    suspend fun getBalanceForCustomer(customerId: Long): Long =
        creditDao.getBalanceForCustomer(customerId)

    fun observeTotalOutstanding(): Flow<Long> = creditDao.observeTotalOutstanding()

    /**
     * Record a credit payment (reduces the customer's outstanding balance).
     * Atomic — balance snapshot is captured inside the transaction.
     */
    suspend fun recordPayment(
        customerId: Long,
        amountPaisa: Long,
        notes: String,
        billId: Long? = null,
        requestId: String = java.util.UUID.randomUUID().toString()
    ): Result<Unit> {
        PermissionChecker.enforceLogin(session.current)
        if (amountPaisa <= 0) return Result.Error("Payment amount must be positive")
        return try {
            require(requestId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid payment reference" }
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$customerId|$amountPaisa|$billId|${notes.trim()}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            db.withTransaction {
                val key = "credit-payment:$requestId"
                db.settingsDao().getSetting(key)?.let {
                    require(it.value == fingerprint) { "This payment reference was already used for different details" }
                    return@withTransaction
                }
                val prevBalance = creditDao.getBalanceForCustomer(customerId)
                require(db.customerDao().getCustomerById(customerId)?.isActive == true) { "Customer is unavailable" }
                require(prevBalance > 0 && amountPaisa <= prevBalance) { "Payment must not exceed the outstanding balance" }
                val newBalance = prevBalance - amountPaisa
                val type = if (newBalance == 0L)
                    CreditTransactionType.FULL_PAYMENT
                else
                    CreditTransactionType.PARTIAL_PAYMENT
                creditDao.insertTransaction(
                    CreditTransactionEntity(
                        customerId           = customerId,
                        billId               = billId,
                        type                 = type.name,
                        amountPaisa          = -amountPaisa,   // Negative = balance decreases
                        previousBalancePaisa = prevBalance,
                        newBalancePaisa      = newBalance,
                        notes                = notes.trim()
                    )
                )
                auditRepository.log(
                actionType = "CREDIT_PAYMENT",
                entityType = "CREDIT",
                customerId = customerId,
                afterValue = "Payment: ₹${amountPaisa / 100}",
                reason     = notes,
                requireSuccess = true
                )
                db.settingsDao().upsertSetting(SettingsEntity(key, fingerprint))
            }
            Result.Success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.e(e, "recordPayment failed")
            Result.Error("Payment recording failed: ${e.message}", e)
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// STOCK REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class StockRepository @Inject constructor(
    private val db: AppDatabase,
    private val productDao: ProductDao,
    private val stockDao: StockDao,
    private val auditRepository: AuditRepository,
    private val session: SessionHolder
) {
    /** Owner-only: adjust stock with mandatory reason */
    suspend fun adjustStock(
        productId: Long,
        newStock: Double,
        reason: String,
        notes: String = "", expectedStock: Double? = null
    ): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        if (reason.isBlank()) return Result.Error("Adjustment reason is required")
        if (!newStock.isFinite() || newStock < 0) return Result.Error("Stock cannot be negative")

        return try {
            db.withTransaction {
            val product = requireNotNull(productDao.getProductById(productId)) { "Product not found" }
            require(expectedStock == null || product.currentStock == expectedStock) {
                "Stock changed while this dialog was open. Close it and try again."
            }
            val diff = newStock - product.currentStock
            productDao.setStock(productId, newStock)
            stockDao.insertAdjustment(
                StockAdjustmentEntity(
                    productId         = productId,
                    productName       = product.name,
                    oldStock          = product.currentStock,
                    newStock          = newStock,
                    difference        = diff,
                    reason            = reason.trim(),
                    notes             = notes.trim(),
                    adjustedByOwnerId = 1L   // Single owner account
                )
            )
            auditRepository.log(
                actionType  = "STOCK_ADJUSTED",
                entityType  = "STOCK",
                productId   = productId,
                beforeValue = "stock=${product.currentStock}",
                afterValue  = "stock=$newStock",
                reason      = reason
            )
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "adjustStock failed")
            Result.Error("Stock adjustment failed: ${e.message}", e)
        }
    }
}

