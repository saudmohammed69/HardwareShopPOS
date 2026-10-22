package com.hardwareshop.pos.util

import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

class UpiPaymentQrTest {
    @Test fun exactPaisaAmounts() {
        assertEquals("1.00", UpiPaymentQr.amount(100))
        assertEquals("850.00", UpiPaymentQr.amount(85000))
        assertEquals("1420.50", UpiPaymentQr.amount(142050))
    }
    @Test(expected=IllegalArgumentException::class) fun missingAddressRejected() {
        UpiPaymentQr.validate("", "Shop")
    }
    @Test(expected=IllegalArgumentException::class) fun missingNameRejected() {
        UpiPaymentQr.validate("merchant@bank", "")
    }
    @Test(expected=IllegalArgumentException::class) fun injectedAddressRejected() {
        UpiPaymentQr.validate("merchant@bank&am=1", "Shop")
    }
    @Test(expected=IllegalArgumentException::class) fun zeroAmountRejected() {
        UpiPaymentQr.amount(0)
    }
    @Test fun printedRasterDecodesToExactPaymentRequest() {
        val uri="upi://pay?pa=merchant%40bank&pn=Hardware%20Shop&am=1420.50&cu=INR"
        val bytes=UpiPaymentQr.raster(uri)
        val pixels=mutableListOf<Int>()
        var at=6 // initial alignment/font commands
        while(at<bytes.size) {
            assertEquals(0x1d, bytes[at].toInt() and 255)
            val height=bytes[at+6].toInt() and 255
            at+=8
            repeat(height*48) {
                val bits=bytes[at++].toInt() and 255
                repeat(8) { bit -> pixels.add(if(bits and (0x80 shr bit)!=0) 0xff000000.toInt() else 0xffffffff.toInt()) }
            }
        }
        val image=RGBLuminanceSource(384,pixels.size/384,pixels.toIntArray())
        assertEquals(uri,QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image))).text)
    }
}
