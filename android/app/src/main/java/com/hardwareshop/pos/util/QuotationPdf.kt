package com.hardwareshop.pos.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A paginated estimate, saved under the FileProvider's existing cache path. */
object QuotationPdf {
    fun create(context: Context, quote: QuotationRepository.Record, shop: SettingsRepository.ShopInfo): File {
        require(quote.number.matches(Regex("Q-\\d{8}-[A-F0-9]{8}"))) { "Invalid quotation number" }
        val folder = File(context.cacheDir, "quotations")
        check(folder.exists() || folder.mkdirs()) { "Could not create quotation PDF folder" }
        val file = File(folder, "${quote.number}.pdf")
        check(file.canonicalFile.parentFile == folder.canonicalFile) { "Invalid quotation PDF path" }
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f }
        val bold = Paint(paint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = 42f
        fun newPage() {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = 42f
            page!!.canvas.drawText("${quote.number}  ·  QUOTATION", 36f, y, bold)
            page!!.canvas.drawText("Page $pageNumber", 501f, y, paint)
            y += 24f
        }
        fun writeLine(text: String, strong: Boolean = false) {
            if (page == null || y > 786f) newPage()
            page!!.canvas.drawText(text, 36f, y, if (strong) bold else paint)
            y += 17f
        }
        fun paragraph(text: String, strong: Boolean = false) {
            val brush = if (strong) bold else paint
            text.split('\n').forEach { source ->
                var remaining = source
                if (remaining.isEmpty()) writeLine("")
                while (remaining.isNotEmpty()) {
                    var count = brush.breakText(remaining, true, 520f, null).coerceAtLeast(1)
                    if (count < remaining.length) {
                        val space = remaining.lastIndexOf(' ', count - 1)
                        if (space > 0) count = space
                    }
                    writeLine(remaining.take(count), strong)
                    remaining = remaining.drop(count).trimStart()
                }
            }
        }
        fun amount(value: Long) = "INR ${CalculationEngine.formatRupeesRaw(value)}"
        try {
            paragraph(shop.name.ifBlank { "Hardware Shop" }, true)
            if (shop.address.isNotBlank()) paragraph(shop.address)
            if (shop.phone.isNotBlank()) paragraph("Shop phone: ${shop.phone}")
            if (shop.gstNumber.isNotBlank()) paragraph("Shop GSTIN: ${shop.gstNumber}")
            paragraph("Estimate only — this is not a tax invoice.")
            paragraph("Created: ${SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(quote.createdAt))}   Valid through: ${quote.validUntil}")
            paragraph("Customer: ${quote.customerName}", true)
            if (quote.customerPhone.isNotBlank()) paragraph("Phone: ${quote.customerPhone}")
            writeLine("")
            quote.lines.forEachIndexed { index, line ->
                paragraph("${index + 1}. ${line.label}", true)
                paragraph("Quantity: ${line.quantityDisplay}")
                if (line.isPipe) paragraph("Full length: ${amount(line.unitPricePaisa)} each; cut feet: ${amount(line.pipeFootPricePaisa)} per ft")
                else paragraph("Rate: ${amount(line.unitPricePaisa)} per ${line.productUnit}")
                paragraph("Amount: ${amount(line.itemTotalPaisa)}" + if (quote.gstEnabled)
                    "   GST ${CalculationEngine.formatGstRate(line.gstRateBp)}: ${amount(line.gstAmountPaisa)}" else "")
                y += 8f
            }
            writeLine("")
            paragraph("Items amount: ${amount(quote.grossSubtotalPaisa)}")
            paragraph("Discount: ${amount(quote.discountPaisa)}")
            if (quote.gstEnabled) {
                paragraph(if (quote.gstInclusive) "GST included: ${amount(quote.gstAmountPaisa)}" else "GST added: ${amount(quote.gstAmountPaisa)}")
            }
            paragraph("TOTAL: ${amount(quote.totalPaisa)}", true)
            if (quote.notes.isNotBlank()) { writeLine(""); paragraph("Notes / terms", true); paragraph(quote.notes) }
            writeLine("")
            paragraph("Quoted prices are the saved estimate. Confirm current prices and stock before preparing the final bill.")
            page?.let(document::finishPage)
            page = null
            file.outputStream().use(document::writeTo)
        } finally {
            page?.let(document::finishPage)
            document.close()
        }
        return file
    }
}
