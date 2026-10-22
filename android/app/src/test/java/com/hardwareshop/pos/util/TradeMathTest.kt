package com.hardwareshop.pos.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TradeMathTest {
    @Test fun repeatedPartialReturnsExactlyReconcileOriginalAmounts() {
        val random = Random(197)
        repeat(2000) {
            val units = random.nextInt(1, 2000)
            val amount = random.nextLong(0, 100000000)
            var returned = 0; var paid = 0L
            while (returned < units) {
                val part = random.nextInt(1, units - returned + 1)
                paid += TradeMath.portion(amount, units / 100.0, returned / 100.0, part / 100.0)
                returned += part
                assertTrue(paid <= amount)
            }
            assertEquals(amount, paid)
        }
    }
    @Test fun fractionRoundingAssignsEveryPaisaOnce() {
        assertEquals(33L, TradeMath.portion(100, 3.0, 0.0, 1.0))
        assertEquals(34L, TradeMath.portion(100, 3.0, 1.0, 1.0))
        assertEquals(33L, TradeMath.portion(100, 3.0, 2.0, 1.0))
    }
    @Test fun invalidQuantitiesAndOverReturnsAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 0.001).forEach {
            assertTrue(runCatching { TradeMath.quantity(it) }.isFailure)
        }
        assertTrue(runCatching { TradeMath.portion(100, 1.0, 0.75, 0.26) }.isFailure)
        assertTrue(runCatching { TradeMath.portion(100, 1.0, -0.1, 0.1) }.isFailure)
    }
    @Test fun stockUpdatesAvoidBinaryDecimalDriftAndRejectOverflow() {
        assertEquals(0.3, TradeMath.addStock(0.1, 0.2), 0.0)
        assertEquals(0.01, TradeMath.addStock(-0.99, 1.0), 0.0)
        assertTrue(runCatching { TradeMath.addStock(1000000000.0, 1.0) }.isFailure)
    }
}
