package com.hardwareshop.pos.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.domain.model.PaymentMethod
import java.io.ByteArrayOutputStream

object ReceiptBuilder {
    fun buildReceipt(bill: Bill, shopInfo: SettingsRepository.ShopInfo, copy: Boolean = bill.isPrinted): ByteArray {
        // A payment QR is optional on an issued receipt. Its setup must not block printing.
        val qrAmount=when(bill.paymentMethod) {
            PaymentMethod.UPI->bill.totalPaisa
            PaymentMethod.SPLIT->bill.payments.filter {it.method==PaymentMethod.UPI}.fold(0L){sum,payment->Math.addExact(sum,payment.amountPaisa)}
            else->0L
        }
        val upi = if (qrAmount > 0) {
            try { UpiPaymentQr.uri(shopInfo.upiId, shopInfo.upiName, qrAmount) }
            catch (_: IllegalArgumentException) { null }
        } else null
        return encode(ReceiptLayout.build(bill, ReceiptLayout.Shop(shopInfo.name, shopInfo.phone, shopInfo.address, shopInfo.gstNumber,shopInfo.receiptFooter), copy),
            upi, if(upi != null) UpiPaymentQr.amount(qrAmount) else null)
    }

    fun testPage(): ByteArray = encode(listOf(
        ReceiptLayout.Line("HARDWARE SHOP POS", ReceiptLayout.Align.CENTER, bold = true, large = true),
        ReceiptLayout.Line("58mm printer test", ReceiptLayout.Align.CENTER),
        ReceiptLayout.Line("-".repeat(32)),
        ReceiptLayout.Line("PVC Elbow 1/2\"", bold = true),
        ReceiptLayout.Line("10  12.00  120.00", ReceiptLayout.Align.RIGHT),
        ReceiptLayout.Line("unit: Piece", ReceiptLayout.Align.RIGHT),
        ReceiptLayout.Line("-".repeat(32)),
        ReceiptLayout.Line("TOTAL                  Rs.120.00", bold = true, large = true),
        ReceiptLayout.Line("Check alignment and paper output", ReceiptLayout.Align.CENTER)
    ))

    /** ESC/POS font A is 32 columns at 384 dots; non-ASCII rows use Android font shaping. */
    fun encode(lines: List<ReceiptLayout.Line>, upiUri: String? = null, upiAmount: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1b, 0x40, 0x1b, 0x4d, 0x00, 0x1b, 0x20, 0x00, 0x1b, 0x33, 28))
        val markedFooter=lines.indexOfFirst {it.footerStart}
        lines.forEachIndexed { index, row ->
            if (upiUri != null && (if(markedFooter>=0)index==markedFooter else index==lines.lastIndex-1 && row.text=="Thank You!")) {
                out.write(UpiPaymentQr.raster(upiUri))
                out.write(byteArrayOf(0x1b, 0x61, 1))
                out.write("\nSCAN & PAY Rs.$upiAmount\nPayment confirmation required\n".toByteArray(Charsets.US_ASCII))
            }
            if (row.text.isEmpty()) {
                // Compact spacing between products, like the supplied receipt sample.
                out.write(byteArrayOf(0x1b, 0x4a, 6))
            } else if (row.text.all { it.code in 32..126 }) {
                val alignment = when (row.align) {
                    ReceiptLayout.Align.LEFT -> 0
                    ReceiptLayout.Align.CENTER -> 1
                    ReceiptLayout.Align.RIGHT -> 2
                }
                out.write(byteArrayOf(0x1b, 0x61, alignment.toByte(), 0x1b, 0x45, if (row.bold) 1 else 0,
                    0x1d, 0x21, if (row.large) 0x01 else 0x00))
                out.write(row.text.toByteArray(Charsets.US_ASCII))
                out.write(0x0a)
            } else {
                out.write(byteArrayOf(0x1b, 0x61, 0, 0x1b, 0x45, 0, 0x1d, 0x21, 0))
                raster(out, row)
            }
            require(out.size() <= PrinterTransport.MAX_PAYLOAD_BYTES - 16) {
                "Receipt is too large. Split it into smaller bills."
            }
        }
        // Feed for manual tear. The Veer has no confirmed cutter; do not send a cut command.
        out.write(byteArrayOf(0x1b, 0x61, 0, 0x1b, 0x45, 0, 0x1d, 0x21, 0, 0x0a, 0x0a, 0x0a))
        return out.toByteArray()
    }

    private fun raster(out: ByteArrayOutputStream, row: ReceiptLayout.Line) {
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (row.large) 28f else 20f
            typeface = Typeface.create("monospace", if (row.bold) Typeface.BOLD else Typeface.NORMAL)
            isAntiAlias = true
        }
        val alignment = when (row.align) {
            ReceiptLayout.Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
            ReceiptLayout.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            ReceiptLayout.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(row.text, 0, row.text.length, paint, 384)
            .setAlignment(alignment).setIncludePad(true).build()
        val bitmap = createBitmap(384, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            layout.draw(canvas)
            // Small GS v 0 stripes keep printer buffers and allocation sizes bounded.
            var y = 0
            while (y < bitmap.height) {
                val height = minOf(128, bitmap.height - y)
                out.write(byteArrayOf(0x1d, 0x76, 0x30, 0, 48, 0, height.toByte(), 0))
                for (line in y until y + height) for (xByte in 0 until 48) {
                    var bits = 0
                    for (bit in 0..7) {
                        val pixel = bitmap[xByte * 8 + bit, line]
                        if ((Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3 < 160)
                            bits = bits or (0x80 shr bit)
                    }
                    out.write(bits)
                }
                y += height
            }
        } finally { bitmap.recycle() }
    }
}
