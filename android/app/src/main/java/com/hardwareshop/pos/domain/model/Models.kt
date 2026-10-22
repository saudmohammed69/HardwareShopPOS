package com.hardwareshop.pos.domain.model

/**
 * Domain models — pure Kotlin, no Android / Room dependencies.
 * The presentation layer binds to these; the data layer maps entities ↔ models.
 *
 * Money: Long (paisa). Format via CalculationEngine.formatRupees().
 */

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT HIERARCHY
// ─────────────────────────────────────────────────────────────────────────────

data class Category(
    val id: Long,
    val name: String,
    val displayOrder: Int,
    val isActive: Boolean
)

data class ProductType(
    val id: Long,
    val categoryId: Long,
    val categoryName: String,
    val name: String,
    val displayOrder: Int,
    val isActive: Boolean
)

data class Product(
    val id: Long,
    val categoryId: Long,
    val categoryName: String,
    val typeId: Long,
    val typeName: String,
    val name: String,
    val size: String,
    val unit: String,
    val sellingPricePaisa: Long,
    val purchasePricePaisa: Long,   // Owner only
    val gstRateBp: Int,
    val currentStock: Double,
    val minimumStock: Double,
    val imagePath: String?,
    val searchAliases: String = "",
    val isActive: Boolean,
    val piecesPerBox: Int = 0,
    val isPipe: Boolean = false,
    val pipeLengthPricePaisa: Long = 0,
    val pipeFootPricePaisa: Long = 0,
    val pipeFeetPerLength: Int = 0
) {
    val isLowStock: Boolean get() = currentStock <= minimumStock && minimumStock > 0
    val displayLabel: String get() = buildString {
        append(name)
        if (size.isNotBlank()) append(" – $size")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CART
// ─────────────────────────────────────────────────────────────────────────────

data class OffcutReference(val id:String,val revision:String,val capacityFeet:Int)

data class CartItem(
    val product: Product,
    val quantity: Double,
    val unitPricePaisa: Long,       // Locked from product at time of adding
    val itemTotalPaisa: Long,
    val gstAmountPaisa: Long = 0L,
    val pipeLengths: Long = 0,
    val pipeFeet: Double = 0.0,
    val pipeFootPricePaisa: Long = 0,
    val quantityEstimated: Boolean = false,
    val offcut: OffcutReference? = null
) {
    val receiptName: String get() = product.name + (if(quantityEstimated) " [Estimated quantity]" else "") + (if(offcut!=null) " [Offcut]" else "")
    val quantityDisplay: String get() = if (product.isPipe) "$pipeLengths Len + ${com.hardwareshop.pos.util.CalculationEngine.formatQuantity(pipeFeet)} Ft" else buildString {
        val q = if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString()
                else "%.2f".format(quantity).trimEnd('0')
        append(q)
        append(" ${product.unit}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CUSTOMER
// ─────────────────────────────────────────────────────────────────────────────

data class Customer(
    val id: Long,
    val name: String,
    val phone: String,
    val address: String,
    val gstNumber: String,
    val outstandingBalancePaisa: Long = 0L,
    val isActive: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// BILL
// ─────────────────────────────────────────────────────────────────────────────

enum class PaymentMethod(val displayName: String) {
    CASH("Cash"),
    UPI("UPI"),
    SPLIT("Split Cash + UPI"),
    CARD("Card"),
    CREDIT("Credit / Udhaar");

    companion object {
        fun fromString(s: String): PaymentMethod =
            entries.firstOrNull { it.name == s } ?: CASH
    }
}

enum class BillStatus { ACTIVE, CANCELLED, EDITED }

data class Bill(
    val id: Long,
    val billNumber: String,
    val customer: Customer?,
    val workerName: String,
    val items: List<BillItem>,
    val subtotalPaisa: Long,
    val discountPaisa: Long,
    val gstEnabled: Boolean,
    val gstAmountPaisa: Long,
    val totalPaisa: Long,
    val paidPaisa: Long,
    val creditPaisa: Long,
    val paymentMethod: PaymentMethod,
    val status: BillStatus,
    val cancellationReason: String,
    val notes: String,
    val isPrinted: Boolean,
    val createdAt: Long,
    val customerName: String = customer?.name.orEmpty(),
    val customerPhone: String = customer?.phone.orEmpty(),
    val customerGstNumber: String = customer?.gstNumber.orEmpty(),
    val discountNote: String = "",
    val payments: List<BillPayment> = emptyList(),
    val shopGstNumber:String = ""
)

data class BillPayment(val method: PaymentMethod, val amountPaisa: Long)

data class BillItem(
    val id: Long,
    val productId: Long,
    val productName: String,
    val productSize: String,
    val productUnit: String,
    val quantity: Double,
    val unitPricePaisa: Long,
    val gstRateBp: Int,
    val itemTotalPaisa: Long,
    val gstAmountPaisa: Long,
    val isPipe: Boolean = false,
    val pipeLengths: Long = 0,
    val pipeFeet: Double = 0.0,
    val pipeFootPricePaisa: Long = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// CREDIT
// ─────────────────────────────────────────────────────────────────────────────

enum class CreditTransactionType {
    CREDIT_CREATED,
    PARTIAL_PAYMENT,
    FULL_PAYMENT,
    ADJUSTMENT
}

data class CreditTransaction(
    val id: Long,
    val customerId: Long,
    val billId: Long?,
    val type: CreditTransactionType,
    val amountPaisa: Long,
    val previousBalancePaisa: Long,
    val newBalancePaisa: Long,
    val notes: String,
    val createdAt: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// WORKER
// ─────────────────────────────────────────────────────────────────────────────

data class Worker(
    val id: Long,
    val name: String,
    val username: String,
    val phone: String,
    val maxDiscountPaisa: Long,
    val requireOwnerApprovalForDiscount: Boolean,
    val isActive: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT
// ─────────────────────────────────────────────────────────────────────────────

data class AuditLog(
    val id: Long,
    val actionType: String,
    val entityType: String,
    val entityId: Long?,
    val actorRole: String,
    val actorName: String,
    val beforeValue: String?,
    val afterValue: String?,
    val reason: String,
    val createdAt: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD SUMMARY
// ─────────────────────────────────────────────────────────────────────────────

data class DashboardSummary(
    val totalSalesPaisa:             Long,
    val billCount:                   Int,
    val cashSalesPaisa:              Long,
    val upiSalesPaisa:               Long,
    val cardSalesPaisa:              Long,
    val creditSalesPaisa:            Long,
    val totalDiscountsPaisa:         Long,
    val totalOutstandingCreditPaisa: Long,
    val lowStockCount:               Int,
    val cancelledBillCount:          Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// STOCK ADJUSTMENT
// ─────────────────────────────────────────────────────────────────────────────

data class StockAdjustment(
    val id: Long,
    val productId: Long,
    val productName: String,
    val oldStock: Double,
    val newStock: Double,
    val difference: Double,
    val reason: String,
    val notes: String,
    val createdAt: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// COMMON
// ─────────────────────────────────────────────────────────────────────────────

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>()
    @Suppress("unused") // Part of the shared async-result contract; callers may expose loading state.
    object Loading : Result<Nothing>()
}


