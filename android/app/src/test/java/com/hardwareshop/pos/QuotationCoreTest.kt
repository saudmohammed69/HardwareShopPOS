package com.hardwareshop.pos

import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.ManualBillItem
import com.hardwareshop.pos.util.PipeBilling
import org.junit.Assert.*
import org.junit.Test

class QuotationCoreTest {
    private fun pipe() = Product(1, 1, "PVC", 1, "Pipe", "PVC Pipe", "1 inch", "Length", 40000,
        0, 1800, 10.0, 0.0, null, isActive = true, isPipe = true,
        pipeLengthPricePaisa = 40000, pipeFootPricePaisa = 3500, pipeFeetPerLength = 20)

    @Test fun pipeIndependentRatesAndDiscountUseTheBillingTaxEngine() {
        val items = listOf(PipeBilling.item(pipe(), 1, 2.0))
        val exclusive = QuotationRepository.calculateSnapshot(items, 1000, true, false)
        assertEquals(47000L, exclusive.subtotalPaisa)
        assertEquals(8280L, exclusive.gstAmountPaisa)
        assertEquals(54280L, exclusive.totalPaisa)
        val inclusive = QuotationRepository.calculateSnapshot(items, 1000, true, true)
        assertEquals(46000L, inclusive.totalPaisa)
        assertEquals(7017L, inclusive.gstAmountPaisa)
        assertEquals(38983L, inclusive.subtotalPaisa)
    }

    @Test fun invalidAmountsAndDiscountsAreRejectedBeforeSaving() {
        val item = PipeBilling.item(pipe(), 1, 2.0)
        assertTrue(runCatching { QuotationRepository.calculateSnapshot(listOf(item.copy(itemTotalPaisa = 1)), 0, false, false) }.isFailure)
        assertTrue(runCatching { QuotationRepository.calculateSnapshot(listOf(item), 47001, false, false) }.isFailure)
        assertTrue(runCatching { QuotationRepository.calculateSnapshot(emptyList(), 0, false, false) }.isFailure)
        assertTrue(runCatching { QuotationRepository.calculateSnapshot(listOf(item.copy(quantity = 2.0)), 0, false, false) }.isFailure)
    }

    @Test fun conversionUsesCurrentLengthAndFootRatesWithoutMutatingSnapshot() {
        val original = PipeBilling.item(pipe(), 1, 2.0)
        val line = QuotationRepository.Line(1, "PVC Pipe", "1 inch", "Length", "PVC", "Pipe", original.quantity,
            40000, 47000, 1800, true, 1, 2.0, 3500, 20)
        val converted = QuotationRepository.conversionLine(line, pipe().copy(pipeLengthPricePaisa = 42000, pipeFootPricePaisa = 4000))
        assertEquals(50000L, converted.itemTotalPaisa)
        assertEquals(42000L, converted.unitPricePaisa)
        assertEquals(4000L, converted.pipeFootPricePaisa)
        assertEquals(47000L, line.itemTotalPaisa)
        assertTrue(runCatching { QuotationRepository.conversionLine(line, pipe().copy(pipeFeetPerLength = 10)) }.isFailure)
    }

    @Test fun manualLineIsAValidQuotationSnapshot() {
        val manual = ManualBillItem.create("Emergency fitting", "50", emptyList())
        val calc = QuotationRepository.calculateSnapshot(listOf(manual), 0, true, false)
        assertEquals(5900L, calc.totalPaisa)
        val line = QuotationRepository.Line(manual.product.id, manual.product.name, "", "Piece", "Manual", "Manual",
            1.0, 5000, 5000, 1800)
        ManualBillItem.validate(line.snapshotCartItem())
        assertEquals(manual.unitPricePaisa, line.snapshotCartItem().unitPricePaisa)
    }

    @Test fun quotationExpiryIncludesTheValidityDate() {
        val date = java.time.LocalDate.of(2026, 10, 15)
        val record = QuotationRepository.Record("id", "Q-20261015-12345678", "Customer", "", date.toString(), "", 0,
            false, false, 1, emptyList(), 0, 0, 0)
        assertFalse(record.isExpired(date))
        assertTrue(record.isExpired(date.plusDays(1)))
    }
}
