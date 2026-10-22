package com.hardwareshop.pos

import com.hardwareshop.pos.util.BoxQuantity
import com.hardwareshop.pos.util.CalculationEngine
import org.junit.Assert.*
import org.junit.Test

class BoxQuantityTest {
    @Test fun boxesAndLoosePiecesUseOneInventoryQuantityAndPrice() {
        val total = BoxQuantity.pieces("2", 100)!! + 5
        assertEquals(205.0, total, 0.0)
        assertEquals(25_625L, CalculationEngine.itemTotalPaisa(total, 125))
        assertEquals(295.0, 500 - total, 0.0)
    }
    @Test fun invalidOrOverflowingBoxesCannotReachBilling() {
        for (quantity in listOf("", "0", "-1", "1.5", "1e3", "99999999999999999999"))
            assertNull(quantity, BoxQuantity.pieces(quantity, 100))
        assertNull(BoxQuantity.pieces("1", 0))
        assertNull(BoxQuantity.pieces("1", 1_000_001))
        assertNull(BoxQuantity.pieces(Long.MAX_VALUE.toString(), 100))
        assertNull(BoxQuantity.pieces("1001", 1_000_000))
    }
    @Test fun conversionOnlyAppliesToExplicitlyConfiguredPieceUnits() {
        for (unit in listOf("Piece", "pieces", "NOS")) BoxQuantity.validate(unit, 100)
        for (unit in listOf("Box", "Meter", "Kg")) {
            BoxQuantity.validate(unit, 0)
            assertTrue(runCatching { BoxQuantity.validate(unit, 100) }.isFailure)
        }
    }
}
