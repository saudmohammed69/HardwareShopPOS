package com.hardwareshop.pos

import com.hardwareshop.pos.util.WarrantyRules
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WarrantyRulesTest {
    @Test fun monthEndAndLeapYearExpiryUsesCalendarMonths() {
        assertEquals(LocalDate.of(2024, 2, 29), WarrantyRules.expiry("2024-01-31", 1))
        assertEquals(LocalDate.of(2023, 2, 28), WarrantyRules.expiry("2023-01-31", 1))
        assertEquals(LocalDate.of(2025, 2, 28), WarrantyRules.expiry("2024-02-29", 12))
        assertEquals(LocalDate.of(2025, 1, 31), WarrantyRules.expiry("2024-01-31", 12))
    }

    @Test fun expiryDateIsStillCoveredAndNextDayIsExpired() {
        assertFalse(WarrantyRules.expired("2024-01-31", 1, LocalDate.of(2024, 2, 29)))
        assertTrue(WarrantyRules.expired("2024-01-31", 1, LocalDate.of(2024, 3, 1)))
    }

    @Test fun invalidAndAmbiguousDatesAreRejected() {
        listOf("2023-02-29", "2024-04-31", "15/10/2024", "2024-1-01", "2024-01-1", "0000-01-01", "", "2024-01-01T10:00:00")
            .forEach { rejects { WarrantyRules.date(it) } }
        assertEquals(LocalDate.of(2024, 2, 29), WarrantyRules.date(" 2024-02-29 "))
    }

    @Test fun warrantyPeriodIsBoundedWholeMonths() {
        assertEquals(1, WarrantyRules.months("1"))
        assertEquals(120, WarrantyRules.months("120"))
        listOf("0", "121", "-1", "12.5", "abc", "999999999999999999999", "").forEach { rejects { WarrantyRules.months(it) } }
        rejects { WarrantyRules.expiry("9999-12-31", 12) }
    }

    @Test fun duplicateSerialIsCaseInsensitiveWithinTheSameProduct() {
        val existing = listOf(7L to " Pump-ABC-123 ", 8L to "XYZ-111")
        assertTrue(WarrantyRules.duplicate(7, "pump-abc-123", existing))
        assertFalse(WarrantyRules.duplicate(9, "pump-abc-123", existing))
        assertFalse(WarrantyRules.duplicate(7, "PumpABC123", existing))
        assertEquals("Pump-ABC-123", WarrantyRules.serial("  Pump-ABC-123  "))
    }

    @Test fun serialCannotBeBlankOrHaveControlCharacters() {
        listOf("", "   ", "a".repeat(101), "A\nB", "A\tB").forEach { rejects { WarrantyRules.serial(it) } }
        assertEquals("A".repeat(100), WarrantyRules.serial("A".repeat(100)))
    }

    @Test fun phoneAllowsFormattedNumbersAndOptionalBlank() {
        assertEquals("+91 (98765) 43210", WarrantyRules.phone(" +91 (98765) 43210 "))
        assertEquals("", WarrantyRules.phone(""))
        listOf("123", "not a phone", "1234567890123456", "9876543210@a").forEach { rejects { WarrantyRules.phone(it) } }
    }

    @Test fun saleAndServiceDatesCannotBeFutureOrBeforeSale() {
        val today = LocalDate.of(2025, 6, 10)
        assertEquals("2025-06-10", WarrantyRules.saleDate("2025-06-10", today))
        rejects { WarrantyRules.saleDate("2025-06-11", today) }
        assertEquals("2025-06-10", WarrantyRules.serviceDate("2025-06-10", "2025-01-01", today))
        rejects { WarrantyRules.serviceDate("2024-12-31", "2025-01-01", today) }
        rejects { WarrantyRules.serviceDate("2025-06-11", "2025-01-01", today) }
    }

    private fun rejects(block: () -> Any?) {
        try { block(); fail("Expected validation to reject input") } catch (_: IllegalArgumentException) { }
    }
}
