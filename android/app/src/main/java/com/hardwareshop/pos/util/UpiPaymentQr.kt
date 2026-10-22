package com.hardwareshop.pos.util

import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/** Offline standard UPI request. Printing never confirms a payment. */
object UpiPaymentQr {
    fun amount(totalPaisa: Long): String {
        require(totalPaisa > 0) { "UPI amount must be greater than zero" }
        return BigDecimal.valueOf(totalPaisa, 2).toPlainString()
    }

    fun validate(payee: String, name: String) {
        require(payee.matches(Regex("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+"))) {
            "Enter a valid merchant UPI ID in Settings → UPI Payment"
        }
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) {
            "Enter a valid payee name in Settings → UPI Payment"
        }
    }

    fun uri(payee: String, name: String, totalPaisa: Long): String {
        validate(payee, name)
        return Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", payee).appendQueryParameter("pn", name)
            .appendQueryParameter("am", amount(totalPaisa))
            .appendQueryParameter("cu", "INR").build().toString()
    }

    /** Same GS v 0 raster protocol as ReceiptBuilder; four-module quiet zone,
     * integer module scaling, centred on 384 dots, bounded 128-line strips. */
    fun raster(uri: String): ByteArray {
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val scale = 320 / matrix.width
        require(scale >= 3) { "UPI QR is too dense for this printer; shorten the payee name" }
        val size = matrix.width * scale
        val left = (384 - size) / 2
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1b, 0x61, 0, 0x1d, 0x21, 0))
        var y = 0
        while (y < size) {
            val height = minOf(128, size - y)
            out.write(byteArrayOf(0x1d, 0x76, 0x30, 0, 48, 0, height.toByte(), 0))
            for (row in y until y + height) for (byte in 0 until 48) {
                var value = 0
                for (bit in 0..7) {
                    val x = byte * 8 + bit - left
                    if (x in 0 until size && matrix[x / scale, row / scale]) value = value or (0x80 shr bit)
                }
                out.write(value)
            }
            y += height
        }
        return out.toByteArray()
    }
}
