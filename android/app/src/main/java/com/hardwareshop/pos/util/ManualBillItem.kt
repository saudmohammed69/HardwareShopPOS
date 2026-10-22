package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.Product

/** Negative IDs are private cart-line keys. Saved bill-only lines use productId 0. */
object ManualBillItem {
    fun create(name: String, price: String, existingIds: List<Long>): CartItem {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 120 && clean.none { it.isISOControl() }) {
            "Enter an item name (1–120 characters)"
        }
        val amount = CalculationEngine.parseRupeesToPaisa(price)
        require(amount != null && amount > 0) { "Enter a valid price greater than zero" }
        val lowest = minOf(0L, existingIds.minOrNull() ?: 0L)
        require(lowest > Long.MIN_VALUE) { "Too many manual items" }
        val product = Product(lowest - 1, 0, "Manual", 0, "Manual", clean, "", "Piece",
            amount, 0, 1800, 0.0, 0.0, null, "", true)
        return CartItem(product, 1.0, amount, amount)
    }
    fun validate(line: CartItem) {
        require(line.product.id < 0 && line.product.categoryId == 0L && line.product.typeId == 0L)
        require(line.product.name.isNotBlank() && line.product.name.length <= 120 && line.product.name.none { it.isISOControl() })
        require(line.unitPricePaisa > 0 && line.quantity.isFinite() && line.quantity > 0)
        require(line.product.gstRateBp == 1800 && line.product.unit == "Piece")
    }
}
