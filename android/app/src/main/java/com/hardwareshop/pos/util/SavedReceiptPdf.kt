package com.hardwareshop.pos.util

import android.content.Context
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.domain.model.PaymentMethod
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved sale values only. PDF generation never assigns a bill number or writes inventory. */
object SavedReceiptPdf {
    fun lines(bill:Bill,shop:SettingsRepository.ShopInfo):List<ShopDocumentPdf.Text> = buildList {
        require(bill.id>0 && bill.billNumber.isNotBlank()) {"Save the bill before sharing a receipt"}
        fun text(value:String,bold:Boolean=false){add(ShopDocumentPdf.Text(value,bold))}
        fun money(value:Long)="INR ${CalculationEngine.formatRupeesRaw(value)}"
        text(shop.name.ifBlank {"Hardware Shop"},true)
        if(shop.address.isNotBlank())text(shop.address)
        if(shop.phone.isNotBlank())text("Shop phone: ${shop.phone}")
        if(bill.shopGstNumber.isNotBlank())text("Shop GSTIN saved on bill: ${bill.shopGstNumber}")
        text("Receipt #${bill.billNumber}",true)
        text("Saved: ${SimpleDateFormat("dd MMM yyyy HH:mm",Locale.ENGLISH).format(Date(bill.createdAt))}")
        text("Status: ${bill.status.name}")
        if(bill.cancellationReason.isNotBlank())text("Cancellation: ${bill.cancellationReason}")
        if(bill.customerName.isNotBlank())text("Customer: ${bill.customerName}")
        if(bill.customerPhone.isNotBlank())text("Customer phone: ${bill.customerPhone}")
        if(bill.customerGstNumber.isNotBlank())text("Customer GSTIN: ${bill.customerGstNumber}")
        text("")
        bill.items.forEachIndexed {index,item->
            text("${index+1}. ${listOf(item.productName,item.productSize).filter {it.isNotBlank()}.joinToString(" · ")}",true)
            if(item.isPipe) {
                text("${item.pipeLengths} full lengths at ${money(item.unitPricePaisa)}; ${CalculationEngine.formatQuantity(item.pipeFeet)} ft at ${money(item.pipeFootPricePaisa)} / ft")
            }else text("${CalculationEngine.formatQuantity(item.quantity)} ${item.productUnit} × ${money(item.unitPricePaisa)}")
            text("Amount: ${money(item.itemTotalPaisa)}")
            if(bill.gstEnabled)text("Saved GST rate: ${CalculationEngine.formatGstRate(item.gstRateBp)}; saved tax: ${money(item.gstAmountPaisa)}")
        }
        text("")
        text("Saved subtotal: ${money(bill.subtotalPaisa)}")
        text("Discount: ${money(bill.discountPaisa)}${if(bill.discountNote.isBlank())"" else " (${bill.discountNote})"}")
        if(bill.gstEnabled) {
            text("Saved GST total: ${money(bill.gstAmountPaisa)}")
            text("CGST / SGST / IGST allocation is not stored in this bill. This receipt does not assign a tax classification.")
        }else text("GST was disabled for this bill; no exemption classification is inferred.")
        text("TOTAL: ${money(bill.totalPaisa)}",true)
        text("Payment: ${bill.paymentMethod.displayName}")
        bill.payments.forEach {text("${it.method.displayName}: ${money(it.amountPaisa)}")}
        text("Paid at checkout: ${money(bill.paidPaisa)}")
        if(bill.paymentMethod==PaymentMethod.CREDIT)text("Credit at checkout: ${money(bill.creditPaisa)} (later ledger payments may change the customer's balance)")
        if(bill.notes.isNotBlank())text("Notes: ${bill.notes}")
        text("")
        ReceiptFooter.lines(shop.receiptFooter).forEach {text(it)}
        text("Shop contact details and footer reflect the current shop settings.")
        text("Receipt copy of the saved transaction. It does not create or replace a tax invoice.")
    }
    fun create(context:Context,bill:Bill,shop:SettingsRepository.ShopInfo):File=
        ShopDocumentPdf.create(context,"RECEIPT #${bill.billNumber}",lines(bill,shop),"receipt")
}
