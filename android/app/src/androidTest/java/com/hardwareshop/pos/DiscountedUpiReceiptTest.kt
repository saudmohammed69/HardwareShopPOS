package com.hardwareshop.pos

import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.ReceiptBuilder
import org.junit.Assert.*
import org.junit.Test

/** Builds bytes only: never connects to a printer or changes business records. */
class DiscountedUpiReceiptTest {
    private fun bill(discount: Long = 250) = Bill(1, "TEST-1", null, "Audit worker",
        listOf(BillItem(1, 1, "PVC Elbow", "1/2", "Piece", 1.0, 1000, 1800, 1000, 0)),
        1000, discount, false, 0, 1000 - discount, 1000 - discount, 0,
        PaymentMethod.UPI, BillStatus.ACTIVE, "", "", false, 0, discountNote = "Direct discount")
    private fun shop(upi: String = "", name: String = "") = SettingsRepository.ShopInfo(
        "Audit shop", "", "", "", upi, name)

    @Test fun missingOrInvalidOptionalQrDoesNotBlockTheDiscountedUpiReceipt() {
        for (info in listOf(shop(), shop("invalid", "Shop"), shop("shop@upi", ""))) {
            val text = ReceiptBuilder.buildReceipt(bill(), info).toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("Payment: UPI"))
            assertTrue(text.contains("Direct discount"))
            assertTrue(text.contains("-2.50"))
            assertTrue(text.contains("Rs.7.50"))
            assertFalse(text.contains("SCAN & PAY"))
        }
    }
    @Test fun fullDiscountPrintsAZeroTotalWithoutGeneratingAZeroValuePaymentRequest() {
        val text = ReceiptBuilder.buildReceipt(bill(1000), shop("shop@upi", "Audit Shop"))
            .toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("Payment: UPI"))
        assertTrue(text.contains("Rs.0.00"))
        assertTrue(text.contains("-10.00"))
        assertFalse(text.contains("SCAN & PAY"))
    }
    @Test fun validQrKeepsTheExactDiscountedAmount() {
        val text = ReceiptBuilder.buildReceipt(bill(), shop("shop@upi", "Audit Shop"))
            .toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("SCAN & PAY Rs.7.50"))
        assertTrue(text.contains("Payment confirmation required"))
        assertTrue(text.contains("Payment: UPI"))
    }
}
