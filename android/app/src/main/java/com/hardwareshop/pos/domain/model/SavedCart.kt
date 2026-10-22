package com.hardwareshop.pos.domain.model

/** A private unfinished bill for one local user; never changes stock or customer credit. */
data class SavedCart(
    val checkoutId: String,
    val items: List<CartItem>,
    val customerId: Long?,
    val discountPaisa: Long,
    val discountNote: String,
    val gstEnabled: Boolean,
    val gstInclusive: Boolean = true,        // true = tax is embedded in item price (default)
    val customerName: String = "",           // customer name entered on bill
    val customerPhone: String = "",          // customer phone entered on bill
    val customerGstNumber: String = "",      // customer GSTIN
    val paymentMethod: PaymentMethod,
    val paidPaisa: Long,
    val notes: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val label: String? = null,
    val sourceQuotationId: String? = null,
    val splitCashPaisa: Long = 0,
    val splitUpiPaisa: Long = 0,
    val sourceWebsiteOrderId: String? = null
)
