package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.domain.model.PaymentMethod
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** A single source of truth for the printer and receipt preview. */
object ReceiptLayout {
    const val WIDTH = 32
    enum class Align { LEFT, CENTER, RIGHT }
    data class Line(val text: String, val align: Align = Align.LEFT,
                    val bold: Boolean = false, val large: Boolean = false, val footerStart:Boolean=false)
    data class Shop(val name: String, val phone: String, val address: String, val gstNumber: String,val footer:String=ReceiptFooter.DEFAULT)

    fun build(bill: Bill, shop: Shop, copy: Boolean = bill.isPrinted, showPayment: Boolean = true): List<Line> = buildList {
        fun line(text: String, align: Align = Align.LEFT, bold: Boolean = false, large: Boolean = false) {
            wrap(text).forEach { add(Line(it, align, bold, large)) }
        }
        fun rule() = line("-".repeat(WIDTH))
        fun pair(left: String, right: String, bold: Boolean = false, large: Boolean = false) {
            val l = clean(left); val r = clean(right)
            val gap = WIDTH - points(l) - points(r)
            if (gap >= 1) line(l + " ".repeat(gap) + r, bold = bold, large = large)
            else {
                line(l, bold = bold, large = large)
                line(r, Align.RIGHT, bold, large)
            }
        }
        line(shop.name, Align.CENTER, bold = true, large = true)
        if (shop.phone.isNotBlank()) line(shop.phone, Align.CENTER)
        if (shop.address.isNotBlank()) line(shop.address, Align.CENTER)
        if (shop.gstNumber.isNotBlank()) line("GSTIN: ${shop.gstNumber}", Align.CENTER)
        if (copy) line("REPRINT / COPY", Align.CENTER, bold = true)
        if (bill.status == com.hardwareshop.pos.domain.model.BillStatus.CANCELLED)
            line("CANCELLED BILL", Align.CENTER, bold = true)
        rule()
        pair("Bill #${bill.billNumber}", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(bill.createdAt)))
        if (bill.customerName.isNotBlank()) line("Customer: ${bill.customerName}")
        if (bill.customerPhone.isNotBlank()) line("Phone: ${bill.customerPhone}")
        if (bill.customerGstNumber.isNotBlank()) line("Cust GST: ${bill.customerGstNumber}")
        if (bill.workerName.isNotBlank()) line("Worker: ${bill.workerName}")
        rule()
        pair("Item", "Qty".padStart(5) + " " + "Rate".padStart(9) + " " + "Amt".padStart(11), bold = true)
        rule()
        bill.items.forEach { item ->
            line(listOf(item.productName, item.productSize).filter { it.isNotBlank() }.joinToString(" "), bold = true)
            if (item.isPipe) {
                if (item.pipeLengths > 0) pair("${item.pipeLengths} Len x ${CalculationEngine.formatRupeesRaw(item.unitPricePaisa)}",
                    CalculationEngine.formatRupeesRaw(Math.multiplyExact(item.pipeLengths, item.unitPricePaisa)))
                if (item.pipeFeet > 0) pair("${CalculationEngine.formatQuantity(item.pipeFeet)} Ft x ${CalculationEngine.formatRupeesRaw(item.pipeFootPricePaisa)}",
                    CalculationEngine.formatRupeesRaw(CalculationEngine.itemTotalPaisa(item.pipeFeet, item.pipeFootPricePaisa)))
                pair("Amount", CalculationEngine.formatRupeesRaw(item.itemTotalPaisa))
                if (bill.gstEnabled && item.gstAmountPaisa > 0) line("GST: ${CalculationEngine.formatRupeesRaw(item.gstAmountPaisa)}", Align.RIGHT)
                line("")
                return@forEach
            }
            val qty = CalculationEngine.formatQuantity(item.quantity)
            val rate = CalculationEngine.formatRupeesRaw(item.unitPricePaisa)
            val amount = CalculationEngine.formatRupeesRaw(item.itemTotalPaisa)
            val figures = qty.padStart(5) + " " + rate.padStart(9) + " " + amount.padStart(11)
            if (points(figures) <= WIDTH) line(figures, Align.RIGHT)
            else { pair("Qty $qty", "Rate $rate"); pair("Amount", amount) }
            if (item.productUnit.isNotBlank()) line("unit: ${item.productUnit}", Align.RIGHT)
            if (bill.gstEnabled && item.gstAmountPaisa > 0)
                line("GST (${CalculationEngine.formatGstRate(item.gstRateBp)}): ${CalculationEngine.formatRupeesRaw(item.gstAmountPaisa)}", Align.RIGHT)
            line("")
        }
        rule()
        pair("Subtotal", CalculationEngine.formatRupeesRaw(bill.subtotalPaisa))
        if (bill.discountPaisa > 0) pair(
            if (bill.discountNote.isBlank()) "Discount" else "Discount (${bill.discountNote})",
            "-${CalculationEngine.formatRupeesRaw(bill.discountPaisa)}"
        )
        if (bill.gstEnabled && bill.gstAmountPaisa > 0) {
            // Show CGST + SGST split for valid GST invoice (intra-state)
            val (cgst, sgst) = CalculationEngine.splitCgstSgst(bill.gstAmountPaisa)
            pair("CGST", CalculationEngine.formatRupeesRaw(cgst))
            pair("SGST", CalculationEngine.formatRupeesRaw(sgst))
        }
        rule()
        pair("TOTAL", "Rs.${CalculationEngine.formatRupeesRaw(bill.totalPaisa)}", bold = true, large = true)
        rule()
        if (showPayment) line("Payment: ${bill.paymentMethod.displayName}")
        if (showPayment && bill.paymentMethod == PaymentMethod.SPLIT) {
            bill.payments.forEach { pair(it.method.displayName, "Rs.${CalculationEngine.formatRupeesRaw(it.amountPaisa)}") }
        }
        if (showPayment && bill.paymentMethod == PaymentMethod.CREDIT) {
            pair("Paid", "Rs.${CalculationEngine.formatRupeesRaw(bill.paidPaisa)}")
            pair("Credit", "Rs.${CalculationEngine.formatRupeesRaw(bill.creditPaisa)}", bold = true)
        }
        if (bill.notes.isNotBlank()) line("Note: ${bill.notes}")
        rule()
        ReceiptFooter.lines(shop.footer).forEachIndexed {index,text->add(Line(text,Align.CENTER,footerStart=index==0))}
    }

    // Reject printer control characters in customer-entered text; retain Unicode scripts.
    fun clean(text: String): String = text.replace(Regex("[\\p{Cc}\\p{Cf}&&[^\u200c\u200d]]"), " ")
        .replace('\u20b9'.toString(), "Rs.").trim()
    private fun points(text: String) = text.codePointCount(0, text.length)
    fun wrap(text: String): List<String> {
        val value = clean(text)
        if (value.isEmpty()) return listOf("")
        val rows = mutableListOf<String>()
        var remaining = value
        while (points(remaining) > WIDTH) {
            val end = remaining.offsetByCodePoints(0, WIDTH)
            val space = remaining.lastIndexOf(' ', end).takeIf { it > end / 2 } ?: end
            rows += remaining.take(space).trimEnd()
            remaining = remaining.substring(space).trimStart()
        }
        if (remaining.isNotEmpty()) rows += remaining
        return rows
    }
}
