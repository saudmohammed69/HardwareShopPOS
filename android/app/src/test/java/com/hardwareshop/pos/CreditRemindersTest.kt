package com.hardwareshop.pos

import com.hardwareshop.pos.util.CreditReminders as R
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class CreditRemindersTest {
    private val today=LocalDate.of(2026,10,17)
    @Test fun overdueRequiresAnExplicitDateAndPositiveLedgerBalance() {
        assertEquals(R.DueState.NO_DATE,R.state(10000,null,today))
        assertEquals(R.DueState.NO_BALANCE,R.state(0,today.minusDays(1),today))
        assertEquals(R.DueState.NO_BALANCE,R.state(-1,today.minusDays(1),today))
        assertEquals(R.DueState.OVERDUE,R.state(10000,today.minusDays(1),today))
        assertEquals(R.DueState.TODAY,R.state(10000,today,today))
        assertEquals(R.DueState.FUTURE,R.state(10000,today.plusDays(1),today))
    }
    @Test fun reminderUsesCurrentExactPaisaAndNeverInventsADueDate() {
        val message=R.message("Customer","Shop",123456,null,today)
        assertTrue(message.contains("1234.56") || message.contains("1,234.56"))
        assertFalse(message.contains("overdue",true))
        assertFalse(message.contains("due date",true))
        assertTrue(R.message("Customer","Shop",123456,today.minusDays(1),today).contains("2026-10-16"))
        assertTrue(runCatching {R.message("Customer","Shop",0,null,today)}.isFailure)
    }
    @Test fun datesMustBeExplicitValidCalendarDates() {
        assertNull(R.parseDate(""))
        assertEquals(LocalDate.of(2024,2,29),R.parseDate("2024-02-29"))
        listOf("2026-02-29","17/10/2026","2026-13-01","2026-10-32").forEach {assertTrue(runCatching {R.parseDate(it)}.isFailure)}
    }
}
