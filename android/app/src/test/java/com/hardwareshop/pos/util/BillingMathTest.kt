package com.hardwareshop.pos.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BillingMathTest {
    @Test fun allocationPreservesLongMaxWithOversizedIntermediates() {
        val half = Long.MAX_VALUE / 2
        assertEquals(listOf(half + 1, half), CalculationEngine.allocate(Long.MAX_VALUE, listOf(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals(listOf(Long.MAX_VALUE), CalculationEngine.allocate(Long.MAX_VALUE, listOf(1L)))
    }
    @Test fun allocationHandlesZeroAndTiedRemainders() {
        assertEquals(listOf(0L, 0L), CalculationEngine.allocate(0, listOf(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals(listOf(0L, 1L, 1L, 0L), CalculationEngine.allocate(2, listOf(0L, 1L, 1L, 1L)))
    }
    @Test fun decimalInputIsExact() {
        assertEquals(1001L, CalculationEngine.parseRupeesToPaisa("10.01"))
        assertEquals(123450L, CalculationEngine.parseRupeesToPaisa("₹1,234.50"))
        listOf("NaN", "Infinity", "-1", "1.001", "1e9", "92233720368547758.08", "").forEach {
            assertNull(it, CalculationEngine.parseRupeesToPaisa(it))
        }
    }
    @Test fun fractionalPaisaRoundsHalfUp() {
        assertEquals(2L, CalculationEngine.itemTotalPaisa(0.5, 3))
        assertEquals(1L, CalculationEngine.gstAmountPaisa(5, 1000))
    }
    @Test fun invalidQuantitiesAndOverflowAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.001).forEach { quantity ->
            assertThrows(Exception::class.java) { CalculationEngine.itemTotalPaisa(quantity, 100) }
        }
        assertThrows(ArithmeticException::class.java) { CalculationEngine.itemTotalPaisa(2.0, Long.MAX_VALUE) }
        assertThrows(ArithmeticException::class.java) { CalculationEngine.calculateBill(listOf(CalculationEngine.CartItemAmount(1.0, Long.MAX_VALUE, 1800)), gstEnabled = true) }
    }
    @Test fun intermediatesMayExceedLongWithoutCorruptingResult() {
        assertEquals(9_000_000_000_000_000L, CalculationEngine.itemTotalPaisa(1.0, 9_000_000_000_000_000L))
        assertEquals(900_000_000_000_000L, CalculationEngine.gstAmountPaisa(9_000_000_000_000_000L, 1000))
    }
    @Test fun discountAndLineTaxReconcileAcrossMixedRates() {
        val items = listOf(CalculationEngine.CartItemAmount(1.0, 101, 1800), CalculationEngine.CartItemAmount(1.0, 101, 500), CalculationEngine.CartItemAmount(1.0, 101, 0))
        val calc = CalculationEngine.calculateBill(items, 100, true)
        assertEquals(303L, calc.subtotalPaisa)
        assertEquals(203L, calc.afterDiscountPaisa)
        assertEquals(calc.gstAmountPaisa, calc.itemGstPaisa.sum())
        assertEquals(calc.totalPaisa, calc.afterDiscountPaisa + calc.gstAmountPaisa)
        assertEquals(0L, calc.itemGstPaisa[2])
    }
    @Test fun completeDiscountAndZeroBaseNeverLeaveTax() {
        val c = CalculationEngine.calculateBill(listOf(CalculationEngine.CartItemAmount(1.0, 1, 1800), CalculationEngine.CartItemAmount(1.0, 1, 500)), 2, true)
        assertEquals(0L, c.totalPaisa)
        assertEquals(listOf(0L, 0L), c.itemGstPaisa)
        assertEquals(emptyList<Long>(), CalculationEngine.calculateBill(emptyList()).itemGstPaisa)
    }
    @Test fun allocationPreservesEveryPaisaForRandomBaskets() {
        val rng = Random(2026)
        repeat(2000) {
            val weights = List(rng.nextInt(1, 50)) { rng.nextLong(0, 100000) }
            val amount = rng.nextLong(0, weights.sum() + 1)
            val shares = CalculationEngine.allocate(amount, weights)
            assertEquals(amount, shares.sum())
            shares.forEachIndexed { i, share -> assertTrue(share in 0..weights[i]) }
            val calc = CalculationEngine.calculateBill(weights.map { CalculationEngine.CartItemAmount(1.0, it, listOf(0, 500, 1200, 1800)[rng.nextInt(4)]) }, amount, true)
            assertEquals(calc.gstAmountPaisa, calc.itemGstPaisa.sum())
            assertEquals(calc.gstAmountPaisa, calc.gstBreakdown.values.sum())
        }
    }
    @Test fun negativeRefundAmountsFormatCorrectly() {
        assertEquals("-0.50", CalculationEngine.formatRupeesRaw(-50))
        assertEquals("-1,234.56", CalculationEngine.formatRupeesRaw(-123456))
    }
}
