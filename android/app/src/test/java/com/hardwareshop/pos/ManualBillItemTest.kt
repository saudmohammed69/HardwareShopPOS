package com.hardwareshop.pos

import com.google.gson.Gson
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.ManualBillItem
import org.junit.Assert.*
import org.junit.Test

class ManualBillItemTest {
    @Test fun createsIndependentBillOnlyLines() {
        val first = ManualBillItem.create("  Special washer  ", "12.50", listOf(3))
        val second = ManualBillItem.create("Special washer", "15", listOf(3, first.product.id))
        assertEquals("Special washer", first.product.name)
        assertEquals(1250L, first.unitPricePaisa)
        assertEquals(1.0, first.quantity, 0.0)
        assertTrue(first.product.id < 0 && second.product.id < first.product.id)
        assertEquals(1800, first.product.gstRateBp)
        ManualBillItem.validate(first)
    }
    @Test fun rejectsInvalidNamesAndPrices() {
        for ((name, price) in listOf("" to "10", "x\ny" to "10", "x".repeat(121) to "10",
            "Item" to "0", "Item" to "-1", "Item" to "12.345", "Item" to "abc")) {
            try { ManualBillItem.create(name, price, emptyList()); fail("Accepted invalid input") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun draftRoundTripRetainsNamePriceAndNegativeKey() {
        val item = ManualBillItem.create("Loose fitting", "118", emptyList()).copy(quantity = 2.0)
        val restored = Gson().fromJson(Gson().toJson(item), CartItem::class.java)
        ManualBillItem.validate(restored)
        assertEquals(item, restored)
        val calc = CalculationEngine.calculateBillInclusive(listOf(CalculationEngine.CartItemAmount(2.0, restored.unitPricePaisa, 1800)), 0)
        assertEquals(23600L, calc.totalPaisa)
        assertEquals(3600L, calc.gstAmountPaisa)
    }
    @Test fun manualValidationDoesNotAcceptCatalogueIdentity() {
        val item = ManualBillItem.create("Item", "10", emptyList())
        try { ManualBillItem.validate(item.copy(product = item.product.copy(id = 1))); fail("Accepted real identity") }
        catch (_: IllegalArgumentException) { }
    }
}
