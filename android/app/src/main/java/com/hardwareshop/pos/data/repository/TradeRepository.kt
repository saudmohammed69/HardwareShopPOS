package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.dao.SalesTotals
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import javax.inject.Inject

data class PurchaseLine(val productId: Long, val quantity: Double, val price: Long, val gstBp: Int = 0)
data class ReturnLine(val billItemId: Long, val quantity: Double, val restock: Boolean = true)
data class ReplacementLine(val productId: Long, val quantity: Double)
data class PurchaseSummary(val bill: PurchaseBillEntity, val supplier: String, val paid: Long) { val due get() = bill.totalPaisa - paid }
data class ReturnBill(val bill: BillEntity, val items: List<BillItemEntity>, val returned: Map<Long, Double>)
data class ReturnQuote(val total: Long, val debtReduction: Long, val refund: Long, val replacement: Long) {
    val collect get() = replacement - refund
}
data class TradeData(val suppliers: List<SupplierEntity>, val products: List<ProductEntity>,
    val purchases: List<PurchaseSummary>, val payments: List<SupplierPaymentEntity>, val returns: List<SaleReturnEntity>)
data class ReturnDetail(val header: SaleReturnEntity, val originalNumber: String, val customer: String,
    val items: List<Pair<BillItemEntity, SaleReturnItemEntity>>, val replacementNumber: String?, val replacementTotal: Long)

suspend fun AppDatabase.netSales(from: Long, to: Long): SalesTotals = withTransaction {
    val gross = billDao().getReportTotals(from, to)
    val returns = tradeDao().totals(from, to)
    gross.copy(sales = gross.sales - returns.total, discounts = gross.discounts - returns.discount,
        tax = gross.tax - returns.tax, cash = gross.cash - returns.cash, upi = gross.upi - returns.upi,
        card = gross.card - returns.card, credit = gross.credit - returns.credit)
}

class TradeRepository @Inject constructor(private val db: AppDatabase, private val billing: BillingRepository,
    private val session: SessionHolder, private val audit: AuditRepository) {
    private fun owner() = PermissionChecker.enforceOwner(session.current)
    private fun request(id: String) { require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid request reference" } }
    private fun method(value: String) { require(value in listOf("CASH", "UPI", "CARD")) { "Choose Cash, UPI or Card" } }
    suspend fun load(): TradeData {
        owner()
        return db.withTransaction {
            val suppliers = db.supplierDao().getAllSuppliers()
            val payments = db.tradeDao().payments()
            val paid = payments.groupBy { it.purchaseBillId }.mapValues { (_, rows) -> rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountPaisa) } }
            TradeData(suppliers, db.productDao().getAllProducts(), db.purchaseBillDao().getAllPurchaseBills().reversed().map {
                PurchaseSummary(it, suppliers.firstOrNull { s -> s.id == it.supplierId }?.name ?: "Supplier", paid[it.id] ?: 0)
            }, payments, db.tradeDao().returns())
        }
    }
    suspend fun saveSupplier(supplier: SupplierEntity): Long {
        owner(); require(supplier.name.isNotBlank()) { "Supplier name is required" }
        return db.withTransaction {
            require(db.supplierDao().getAllSuppliers().none { it.id != supplier.id && it.name.trim().equals(supplier.name.trim(), true) }) {
                "A supplier with this name already exists. Choose it or use a distinct branch name."
            }
            val clean = supplier.copy(name = supplier.name.trim(), phone = supplier.phone.trim(), updatedAt = System.currentTimeMillis())
            if (clean.id == 0L) db.supplierDao().insertSupplier(clean) else {
                requireNotNull(db.supplierDao().getSupplierById(clean.id)); db.supplierDao().updateSupplier(clean); clean.id
            }
        }
    }
    suspend fun purchase(id: String, supplierId: Long, invoice: String, date: Long, lines: List<PurchaseLine>,
        paid: Long, paymentMethod: String, notes: String): Long {
        owner(); request(id); method(paymentMethod)
        return db.withTransaction {
            val key = "purchase:request:$id"
            db.settingsDao().getSetting(key)?.value?.toLongOrNull()?.let { return@withTransaction it }
            require(db.supplierDao().getSupplierById(supplierId)?.isActive == true) { "Select an active supplier" }
            require(invoice.isNotBlank()) { "Supplier invoice number is required" }
            require(db.purchaseBillDao().getBySupplier(supplierId).none { it.supplierBillNumber.trim().equals(invoice.trim(), true) }) { "This supplier invoice is already recorded" }
            require(date > 0 && date < System.currentTimeMillis() + 86400000) { "Invalid purchase date" }
            require(lines.isNotEmpty() && lines.map { it.productId }.distinct().size == lines.size) { "Add unique purchase items" }
            val products = lines.map { line ->
                TradeMath.quantity(line.quantity)
                require(line.price >= 0 && line.gstBp in 0..10000) { "Invalid purchase price or GST" }
                requireNotNull(db.productDao().getProductById(line.productId)?.takeIf { it.isActive }) { "Product is unavailable" }
            }
            val bases = lines.map { CalculationEngine.itemTotalPaisa(it.quantity, it.price) }
            val taxes = lines.mapIndexed { index, it -> CalculationEngine.gstAmountPaisa(bases[index], it.gstBp) }
            val total = bases.fold(0L, Math::addExact).let { Math.addExact(it, taxes.fold(0L, Math::addExact)) }
            require(paid in 0..total) { "Paid amount must be between zero and the invoice total" }
            val now = System.currentTimeMillis()
            val billId = db.purchaseBillDao().insertPurchaseBill(PurchaseBillEntity(supplierId = supplierId,
                billDate = date, supplierBillNumber = invoice.trim(), totalPaisa = total, notes = notes.trim()))
            lines.forEachIndexed { index, line ->
                val p = products[index]
                db.purchaseBillItemDao().insert(PurchaseBillItemEntity(purchaseBillId = billId, productId = p.id,
                    productName = p.name, productSize = p.size, productUnit = p.unit, quantity = line.quantity,
                    purchasePricePaisa = line.price, gstRateBp = line.gstBp, itemTotalPaisa = bases[index], gstAmountPaisa = taxes[index], createdAt = now))
                val stock = TradeMath.addStock(p.currentStock, line.quantity)
                db.productDao().updateProduct(p.copy(currentStock = stock, purchasePricePaisa = line.price, updatedAt = now))
                db.stockDao().insertAdjustment(StockAdjustmentEntity(productId = p.id, productName = p.name,
                    oldStock = p.currentStock, newStock = stock, difference = line.quantity, reason = "Purchase received",
                    notes = "Purchase #$billId / ${invoice.trim()}", adjustedByOwnerId = 1))
                db.settingsDao().deleteSetting("reorder:${p.id}")
            }
            if (paid > 0) db.tradeDao().insertPayment(SupplierPaymentEntity(id, billId, paid, paymentMethod, "Paid at purchase", now))
            db.settingsDao().upsertSetting(SettingsEntity(key, billId.toString()))
            audit.log(actionType = "PURCHASE_RECEIVED", entityType = "PURCHASE", entityId = billId, afterValue = "Total $total paisa; paid $paid")
            billId
        }
    }
    suspend fun paySupplier(id: String, billId: Long, amount: Long, paymentMethod: String, reference: String) {
        owner(); request(id); method(paymentMethod)
        db.withTransaction {
            if (db.tradeDao().payment(id) != null) return@withTransaction
            val bill = requireNotNull(db.purchaseBillDao().getPurchaseBillById(billId))
            require(amount > 0 && amount <= bill.totalPaisa - db.tradeDao().paid(billId)) { "Payment exceeds the remaining supplier due" }
            db.tradeDao().insertPayment(SupplierPaymentEntity(id, billId, amount, paymentMethod, reference.trim(), System.currentTimeMillis()))
            audit.log(actionType = "SUPPLIER_PAYMENT", entityType = "PURCHASE", entityId = billId, afterValue = "$amount paisa via $paymentMethod")
        }
    }
    suspend fun purchaseItems(id: Long): List<PurchaseBillItemEntity> { owner(); return db.purchaseBillItemDao().getByBill(id) }
    suspend fun returnDetail(id: String): ReturnDetail {
        owner()
        return db.withTransaction {
            val header = requireNotNull(db.tradeDao().returnById(id))
            val bill = requireNotNull(db.billDao().getBillById(header.billId))
            val original = db.billDao().getBillItems(bill.id).associateBy { it.id }
            val replacement = header.exchangeBillId?.let { db.billDao().getBillById(it) }
            ReturnDetail(header, bill.billNumber, bill.customerName,
                db.tradeDao().returnItems().filter { it.returnId == id }.map { requireNotNull(original[it.billItemId]) to it },
                replacement?.billNumber, replacement?.totalPaisa ?: 0)
        }
    }
    suspend fun findBill(number: String): ReturnBill {
        owner()
        return db.withTransaction {
            val bill = requireNotNull(db.billDao().getBillByNumber(number.trim())) { "Bill number not found; include leading zeros" }
            require(bill.status == "ACTIVE") { "Only active bills can be returned" }
            val items = db.billDao().getBillItems(bill.id)
            ReturnBill(bill, items, items.associate { it.id to db.tradeDao().returnedLine(it.id).fold(0.0) { sum, row -> TradeMath.addStock(sum, row.quantity) } })
        }
    }
    private suspend fun calculate(billId: Long, lines: List<ReturnLine>, replacement: List<ReplacementLine>, returnId: String): Pair<ReturnQuote,List<SaleReturnItemEntity>> {
        val bill = requireNotNull(db.billDao().getBillById(billId)) { "Original bill not found" }
        require(bill.status == "ACTIVE") { "Cancelled or edited bills cannot be returned" }
        require(lines.isNotEmpty() && lines.map { it.billItemId }.distinct().size == lines.size) { "Select unique items to return" }
        val items = db.billDao().getBillItems(billId)
        val subtotal = items.map { it.itemTotalPaisa }.fold(0L, Math::addExact)
        val discountedSubtotal = subtotal - bill.discountPaisa
        // Selling prices are normally GST-inclusive. Older/exported bills may use the
        // traditional exclusive format, so accept and preserve either representation.
        val inclusiveBill = bill.gstEnabled && bill.totalPaisa == discountedSubtotal
        val exclusiveBill = bill.totalPaisa == discountedSubtotal + items.sumOf { it.gstAmountPaisa }
        require(bill.discountPaisa in 0..subtotal && (inclusiveBill || exclusiveBill)) {
            "Original bill totals are inconsistent; review the imported bill"
        }
        val discounts = CalculationEngine.allocate(bill.discountPaisa, items.map { it.itemTotalPaisa })
        val rows = lines.map { line ->
            val index = items.indexOfFirst { it.id == line.billItemId }; require(index >= 0) { "Item does not belong to this bill" }
            val item = items[index]
            require(!item.isPipe) { "Pipe returns need separate length/foot quantities. Use full bill cancellation where eligible; partial pipe returns are not supported." }
            val returned = db.tradeDao().returnedLine(item.id).fold(0.0) { sum, row -> TradeMath.addStock(sum, row.quantity) }
            val base = TradeMath.portion(item.itemTotalPaisa - discounts[index], item.quantity, returned, line.quantity)
            val tax = TradeMath.portion(item.gstAmountPaisa, item.quantity, returned, line.quantity)
            val discount = TradeMath.portion(discounts[index], item.quantity, returned, line.quantity)
            if (line.restock) requireNotNull(db.productDao().getProductById(item.productId)) { "Original product is missing; choose no restock" }
            // For an inclusive bill, itemTotalPaisa already contains the tax. Do not add
            // the embedded tax a second time (₹250 must return as ₹250, not ₹288.40).
            val returnedTotal = if (inclusiveBill) base else Math.addExact(base, tax)
            SaleReturnItemEntity(returnId = returnId, billItemId = item.id, quantity = line.quantity,
                totalPaisa = returnedTotal, discountPaisa = discount, taxPaisa = tax, restock = line.restock)
        }
        val total = rows.map { it.totalPaisa }.fold(0L, Math::addExact)
        // Credit repayments are customer-level, so returns of credit bills reduce overall positive debt first.
        val debt = if (bill.paymentMethod == "CREDIT" && bill.customerId != null)
            minOf(total, db.creditDao().getBalanceForCustomer(bill.customerId).coerceAtLeast(0)) else 0L
        require(replacement.map { it.productId }.distinct().size == replacement.size) { "Duplicate replacement products" }
        val amounts = replacement.map {
            TradeMath.quantity(it.quantity)
            val p = requireNotNull(db.productDao().getProductById(it.productId)?.takeIf { p -> p.isActive }) { "Replacement product is unavailable" }
            CalculationEngine.CartItemAmount(it.quantity, p.sellingPricePaisa, p.gstRateBp)
        }
        val replacementTotal = if (bill.gstEnabled && inclusiveBill)
            CalculationEngine.calculateBillInclusive(amounts, 0).totalPaisa
        else
            CalculationEngine.calculateBill(amounts, 0, bill.gstEnabled).totalPaisa
        return ReturnQuote(total, debt, total - debt, replacementTotal) to rows
    }
    suspend fun quote(billId: Long, lines: List<ReturnLine>, replacement: List<ReplacementLine>): ReturnQuote {
        owner(); return db.withTransaction { calculate(billId, lines, replacement, "preview").first }
    }
    suspend fun returnSale(id: String, billId: Long, lines: List<ReturnLine>, replacement: List<ReplacementLine>,
        paymentMethod: String, reason: String, expected: ReturnQuote): SaleReturnEntity {
        owner(); request(id); method(paymentMethod); require(reason.isNotBlank()) { "Return reason is required" }
        return db.withTransaction {
            db.tradeDao().returnById(id)?.let { return@withTransaction it }
            val (quote, rows) = calculate(billId, lines, replacement, id)
            require(quote == expected) { "Prices or customer balance changed. Preview the return again." }
            val bill = requireNotNull(db.billDao().getBillById(billId))
            val items = db.billDao().getBillItems(billId).associateBy { it.id }
            rows.filter { it.restock }.forEach { line ->
                val p = requireNotNull(db.productDao().getProductById(items.getValue(line.billItemId).productId))
                val stock = TradeMath.addStock(p.currentStock, line.quantity)
                db.productDao().setStock(p.id, stock)
                db.stockDao().insertAdjustment(StockAdjustmentEntity(productId = p.id, productName = p.name,
                    oldStock = p.currentStock, newStock = stock, difference = line.quantity, reason = "Sale return",
                    notes = "Return $id; bill ${bill.billNumber}", adjustedByOwnerId = 1))
            }
            if (quote.debtReduction > 0) {
                val customerId = requireNotNull(bill.customerId)
                val balance = db.creditDao().getBalanceForCustomer(customerId)
                db.creditDao().insertTransaction(CreditTransactionEntity(customerId = customerId, billId = billId,
                    type = "ADJUSTMENT", amountPaisa = -quote.debtReduction, previousBalancePaisa = balance,
                    newBalancePaisa = balance - quote.debtReduction, notes = "Return $id / bill ${bill.billNumber}", recordedByOwnerId = 1))
            }
            val exchangeId = if (replacement.isEmpty()) null else {
                val cart = replacement.map {
                    val p = requireNotNull(db.productDao().getProductById(it.productId)).toDomain()
                    CartItem(p, it.quantity, p.sellingPricePaisa, CalculationEngine.itemTotalPaisa(it.quantity, p.sellingPricePaisa))
                }
                val activeCustomer = bill.customerId?.let { db.customerDao().getCustomerById(it) }?.takeIf { it.isActive }
                val inclusive = bill.gstEnabled && bill.totalPaisa ==
                    items.values.sumOf { it.itemTotalPaisa } - bill.discountPaisa
                when (val result = billing.createBill(
                    cartItems = cart, customerId = activeCustomer?.id,
                    customerName = bill.customerName, customerPhone = bill.customerPhone,
                    discountPaisa = 0, discountNote = "", gstEnabled = bill.gstEnabled,
                    paymentMethod = PaymentMethod.valueOf(paymentMethod), paidPaisa = quote.replacement,
                    notes = "Exchange against bill ${bill.billNumber}; return $id. Net settlement ${quote.collect} paisa.",
                    customerGstNumber = bill.customerGstNumber, checkoutId = "exchange-$id", gstInclusive = inclusive)) {
                    is Result.Success -> {
                        check(result.data.totalPaisa == quote.replacement) { "Exchange total changed; preview again" }
                        result.data.id
                    }
                    is Result.Error -> error(result.message)
                    else -> error("Exchange could not be saved")
                }
            }
            val row = SaleReturnEntity(id, billId, exchangeId, quote.total, rows.sumOf { it.discountPaisa }, rows.sumOf { it.taxPaisa },
                quote.debtReduction, quote.refund, paymentMethod, reason.trim(), System.currentTimeMillis())
            db.tradeDao().insertReturn(row); db.tradeDao().insertReturnItems(rows)
            audit.log(actionType = "SALE_RETURN", entityType = "BILL", billId = billId,
                afterValue = "Return $id; value ${quote.total}; debt reduction ${quote.debtReduction}; net collect ${quote.collect}", reason = reason)
            row
        }
    }
}
