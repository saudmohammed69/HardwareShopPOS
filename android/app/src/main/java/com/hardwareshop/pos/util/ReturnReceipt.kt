package com.hardwareshop.pos.util

import com.hardwareshop.pos.data.repository.ReturnDetail

object ReturnReceipt {
    fun build(detail: ReturnDetail, shopName: String, copy: Boolean): List<ReceiptLayout.Line> = buildList {
        fun text(value: String, bold: Boolean = false) { ReceiptLayout.wrap(value).forEach { add(ReceiptLayout.Line(it, bold = bold)) } }
        fun money(label: String, amount: Long) { text("$label: Rs.${CalculationEngine.formatRupeesRaw(amount)}") }
        text(shopName, true); text("RETURN / EXCHANGE", true)
        if (copy) text("REPRINT / COPY", true)
        text("Return: ${detail.header.id}")
        text("Original bill: ${detail.originalNumber}")
        text(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US).format(java.util.Date(detail.header.createdAt)))
        if (detail.customer.isNotBlank()) text("Customer: ${detail.customer}")
        text("-".repeat(32))
        detail.items.forEach { (item, returned) ->
            text("${item.productName} ${item.productSize}", true)
            text("${CalculationEngine.formatQuantity(returned.quantity)} ${item.productUnit}; ${if (returned.restock) "restocked" else "not restocked"}")
            money("Returned value", returned.totalPaisa)
        }
        text("-".repeat(32)); money("Total returned", detail.header.totalPaisa)
        money("Includes reversed GST", detail.header.taxPaisa)
        money("Customer debt reduced", detail.header.creditReductionPaisa)
        money("Refund / exchange allowance", detail.header.refundPaisa)
        detail.replacementNumber?.let { text("Replacement bill: $it"); money("Replacement total", detail.replacementTotal) }
        val net = detail.replacementTotal - detail.header.refundPaisa
        money(if (net >= 0) "Collect from customer" else "Refund to customer", kotlin.math.abs(net))
        text("Method: ${detail.header.method}"); text("Reason: ${detail.header.reason}")
        text("Settlement recorded by shop.")
        text("Keep with original receipt.")
    }
}
