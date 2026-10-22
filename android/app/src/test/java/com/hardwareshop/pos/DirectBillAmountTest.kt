package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.presentation.worker.billing.BillingUiState
import org.junit.Assert.*
import org.junit.Test

class DirectBillAmountTest {
    private val product = Product(1, 1, "Plumbing", 1, "Elbow", "PVC Elbow", "1/2", "Piece",
        1250, 800, 1800, 20.0, 1.0, null, isActive = true)
    private fun cart(quantity: Double = 1.0, price: Long = 1250) = BillingUiState(
        cart = listOf(CartItem(product, quantity, price, 0)), gstEnabled = true, gstInclusive = false)

    @Test fun amountUsesCartPriceAndDiscountWithoutAddingDefaultGst() {
        val state = cart(price = 1500).copy(discountPaisa = 125)
        val amount = requireNotNull(state.directBillCalculation())
        assertEquals(1500L, amount.subtotalPaisa)
        assertEquals(1375L, amount.totalPaisa)
        assertEquals(0L, amount.gstAmountPaisa)
        assertTrue(state.gstEnabled)
        assertEquals(125L, state.discountPaisa)
    }
    @Test fun fullDiscountProducesZeroAndExcessIsRejectedRatherThanClamped() {
        val state = cart()
        assertEquals(0L, requireNotNull(state.directBillCalculation(1250)).totalPaisa)
        assertNull(state.directBillCalculation(1251))
        assertNull(state.directBillCalculation(-1))
        assertNull(state.directBillCalculation(Long.MAX_VALUE))
    }
    @Test fun fractionalQuantityUsesExistingPaisaRoundingAndRejectsInvalidAmounts() {
        assertEquals(188L, requireNotNull(cart(quantity = 1.5, price = 125).directBillCalculation()).totalPaisa)
        assertNull(cart(quantity = Double.NaN).directBillCalculation())
        assertNull(cart(quantity = 1.001).directBillCalculation())
        assertNull(cart(quantity = 2.0, price = Long.MAX_VALUE).directBillCalculation())
    }
    @Test fun emptyCartCannotOfferACollectAmount() {
        assertNull(BillingUiState().directBillCalculation())
    }
}
