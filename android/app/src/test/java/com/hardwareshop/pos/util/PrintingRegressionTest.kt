package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.data.remote.PrintClaimPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrintingRegressionTest {
    @Test fun cashAndCardDoNotRequireUpiSettingsOrPrintQr() {
        val info = com.hardwareshop.pos.data.repository.SettingsRepository.ShopInfo("Shop", "", "", "")
        for (method in listOf(PaymentMethod.CASH, PaymentMethod.CARD)) {
            val receipt = ReceiptBuilder.buildReceipt(sampleBill().copy(paymentMethod = method), info)
                .toString(Charsets.ISO_8859_1)
            assertFalse(receipt.contains("SCAN & PAY"))
            assertFalse(receipt.contains("\u001dv0"))
        }
    }

    @Test fun longReceiptKeepsQrBeforeFooterAndManualTearFeed() {
        val lines = List(500) { ReceiptLayout.Line("PVC Elbow 1/2 inch") } +
            listOf(ReceiptLayout.Line("Thank You!"), ReceiptLayout.Line("Visit Again Soon"))
        val receipt = ReceiptBuilder.encode(lines,
            "upi://pay?pa=merchant%40bank&pn=Shop&am=1.00&cu=INR", "1.00")
            .toString(Charsets.ISO_8859_1)
        assertTrue(receipt.indexOf("SCAN & PAY Rs.1.00") < receipt.indexOf("Thank You!"))
        assertTrue(receipt.contains("Payment confirmation required"))
        assertTrue(receipt.endsWith("\n\n\n"))
    }

    @Test fun cancelledReceiptIsClearlyMarked() {
        val lines = ReceiptLayout.build(sampleBill().copy(status = BillStatus.CANCELLED), shop())
        assertTrue(lines.any { it.text == "CANCELLED BILL" && it.bold })
    }
    companion object {
        fun sampleBill(): Bill {
            val names = listOf("PVC Elbow" to "1/2\"", "PVC Tee" to "3/4\"", "GI Pipe" to "1\" x 6ft",
                "Ball Valve" to "1/2\"", "Electrical Wire" to "2.5mm", "MCB Switch" to "32A",
                "Junction Box" to "", "PVC Conduit" to "20mm")
            val quantities = listOf(10.0, 5.0, 2.0, 3.0, 15.0, 2.0, 4.0, 6.0)
            val rates = listOf(1200L, 1800L, 22000L, 9500L, 2200L, 18500L, 4500L, 3500L)
            val items = names.mapIndexed { i, (name, size) -> BillItem(i.toLong(), i.toLong(), name, size,
                if (i == 4) "Meter" else "Piece", quantities[i], rates[i], 0,
                CalculationEngine.itemTotalPaisa(quantities[i], rates[i]), 0) }
            return Bill(87, "000087", null, "Suresh", items, 202500, 12500, false, 0, 190000,
                190000, 0, PaymentMethod.CASH, BillStatus.ACTIVE, "", "", false, 1753163040000,
                customerName = "Ramesh Kumar", customerPhone = "9845012345", discountNote = "bulk")
        }
        fun shop() = ReceiptLayout.Shop("SHREE HARDWARE STORE", "9876543210", "MG Road, Bengaluru - 560001", "29ABCDE1234F1Z5")
    }

    @Test fun referenceReceiptIncludesSnapshotsUnitsWorkerAndDiscount() {
        val lines = ReceiptLayout.build(sampleBill(), shop())
        val text = lines.joinToString("\n") { it.text }
        listOf("SHREE HARDWARE STORE", "Customer: Ramesh Kumar", "Phone: 9845012345", "Worker: Suresh",
            "unit: Meter", "Discount (bulk)", "2,025.00", "Rs.1,900.00", "Thank You!").forEach { assertTrue(it, text.contains(it)) }
        assertTrue(lines.first().bold && lines.first().large)
        assertTrue(lines.first { it.text.startsWith("TOTAL") }.large)
        assertTrue(lines.any { it.text.startsWith("Item") && it.text.endsWith("Amt") })
        assertEquals(202500L, sampleBill().items.sumOf { it.itemTotalPaisa })
        assertFalse(text.contains("partial cut"))
    }

    @Test fun longNamesAndAmountsStayWithin32Columns() {
        val original = sampleBill()
        val bill = original.copy(items = listOf(original.items.first().copy(
            productName = "Very long electrical cable for commercial installations and heavy duty hardware",
            productSize = "extra wide", quantity = 123456.75,
            unitPricePaisa = 123456789012L, itemTotalPaisa = 999999999999L)), notes = "Deliver to customer after 5 pm.")
        val lines = ReceiptLayout.build(bill, shop().copy(name = "A very long name of the hardware store with multiple branches"))
        assertTrue(lines.all { it.text.codePointCount(0, it.text.length) <= 32 })
        assertTrue(lines.any { it.text.contains("Note:") })
        assertTrue(lines.any { it.text.contains("Amount") })
    }

    @Test fun localeDoesNotChangeReceiptDigitsAndUnicodeIsPreserved() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("1,234.50", CalculationEngine.formatRupeesRaw(123450))
            assertEquals("2.5", CalculationEngine.formatQuantity(2.5))
            assertEquals("2.5%", CalculationEngine.formatGstRate(250))
            val text = ReceiptLayout.build(sampleBill().copy(customerName = "ರಮೇಶ್ कुमार"), shop()).joinToString { it.text }
            assertTrue(text.contains("ರಮೇಶ್ कुमार"))
            assertFalse(text.contains("?"))
        } finally { Locale.setDefault(old) }
    }

    @Test fun controlBytesAreRemovedAndReprintsAreMarked() {
        val text = ReceiptLayout.build(sampleBill().copy(notes = "Hello\u001b@\nWorld"), shop(), copy = true).joinToString { it.text }
        assertFalse(text.contains('\u001b'))
        assertTrue(text.contains("REPRINT / COPY"))
        assertEquals("ಕ\u200dಕ", ReceiptLayout.clean("ಕ\u200dಕ"))
    }

    @Test fun asciiReceiptEncodesTheLayoutAndFeedsWithoutCutting() {
        val bytes = ReceiptBuilder.encode(ReceiptLayout.build(sampleBill(), shop()))
        val encoded = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(encoded.startsWith("\u001b@"))
        assertTrue(encoded.contains("Customer: Ramesh Kumar"))
        assertTrue(encoded.contains("Rs.1,900.00"))
        assertTrue(encoded.contains("\u001d!\u0001")) // double height, 32-column width retained
        assertFalse(encoded.contains("\u001dV")) // no unconfirmed cutter command
        assertTrue(encoded.endsWith("\n\n\n"))
    }

    @Test fun oversizedPayloadNeverOpensAConnection() = runBlocking {
        val opened = AtomicInteger()
        try {
            engine().send(ByteArray(PrinterTransport.MAX_PAYLOAD_BYTES + 1), listOf({
                opened.incrementAndGet(); FakeConnection()
            }))
            fail()
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, opened.get())
    }

    private open class FakeConnection : PrinterConnection {
        val bytes = ByteArrayOutputStream()
        @Volatile var closed = false
        override fun connect() {}
        override val output: OutputStream get() = bytes
        override fun close() { closed = true }
    }
    private fun engine(connectMs: Long = 200, writeMs: Long = 200) =
        PrinterTransport(connectMs, writeMs, chunkSize = 2, chunkDelayMs = 0, drainMs = 0)

    @Test fun failedConnectClosesSocketAndTriesFallback() = runBlocking {
        val first = object : FakeConnection() { override fun connect() { throw IOException("offline") } }
        val second = FakeConnection()
        engine().send(byteArrayOf(1,2,3), listOf({ first }, { second }))
        assertTrue(first.closed); assertTrue(second.closed)
        assertArrayEquals(byteArrayOf(1,2,3), second.bytes.toByteArray())
    }

    @Test fun timeoutClosesActualBlockedSocketAndLaterJobsStillWork() = runBlocking {
        val closed = CountDownLatch(1)
        val first = object : FakeConnection() {
            override fun connect() { while (!closed.await(10, TimeUnit.MILLISECONDS)) { } }
            override fun close() { super.close(); closed.countDown() }
        }
        val fallback = FakeConnection(); val next = FakeConnection(); val transport = engine(connectMs = 40)
        transport.send(byteArrayOf(4), listOf({first}, {fallback}))
        transport.send(byteArrayOf(5), listOf({next}))
        assertTrue(first.closed)
        assertArrayEquals(byteArrayOf(4), fallback.bytes.toByteArray())
        assertArrayEquals(byteArrayOf(5), next.bytes.toByteArray())
    }

    @Test fun partialWriteIsNotAutomaticallyResent() = runBlocking {
        val fallbackCount = AtomicInteger()
        val partial = object : FakeConnection() {
            override val output = object : OutputStream() {
                override fun write(b: Int) { bytes.write(b); throw IOException("disconnect after accepting byte") }
            }
        }
        try { engine().send(byteArrayOf(1,2,3), listOf({partial}, {fallbackCount.incrementAndGet(); FakeConnection()})); fail() }
        catch (e: PrintDeliveryException) { assertTrue(e.message!!.contains("Check the paper")) }
        assertEquals(1, partial.bytes.size()); assertEquals(0, fallbackCount.get()); assertTrue(partial.closed)
    }

    @Test fun blockedWriteTimesOutAndReleasesQueue() = runBlocking {
        val closed = CountDownLatch(1)
        val hung = object : FakeConnection() {
            override val output = object : OutputStream() {
                override fun write(b: Int) { while (!closed.await(10, TimeUnit.MILLISECONDS)) {} ; throw IOException("closed") }
            }
            override fun close() { super.close(); closed.countDown() }
        }
        val transport = engine(writeMs = 40)
        try { transport.send(byteArrayOf(7), listOf({hung})); fail() } catch (_: PrintDeliveryException) {}
        val next = FakeConnection(); transport.send(byteArrayOf(8), listOf({next}))
        assertTrue(hung.closed); assertArrayEquals(byteArrayOf(8), next.bytes.toByteArray())
    }

    @Test fun cancelledOperationClosesSocketAndFenceFailureSendsNothing() = runBlocking {
        val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        val hung = object : FakeConnection() {
            override fun connect() { entered.countDown(); closed.await() }
            override fun close() { super.close(); closed.countDown() }
        }
        val job = launch(Dispatchers.Default) { engine(connectMs = 5000).send(byteArrayOf(1), listOf({hung})) }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancelAndJoin(); assertTrue(hung.closed)
        val fenced = FakeConnection()
        try { engine().send(byteArrayOf(1), listOf({fenced})) { error("claim lost") }; fail() }
        catch (_: IllegalStateException) {}
        assertEquals(0, fenced.bytes.size()); assertTrue(fenced.closed)
    }

    @Test fun concurrentReceiptsAreSerialized() = runBlocking {
        val active = AtomicInteger(); val highest = AtomicInteger()
        val transport = engine(writeMs = 2000)
        coroutineScope { (1..12).map { async(Dispatchers.Default) {
            transport.send(byteArrayOf(1), listOf({ object : FakeConnection() {
                override fun connect() { highest.accumulateAndGet(active.incrementAndGet()) { a,b -> maxOf(a,b) } }
                override fun close() { if (!closed) { super.close(); active.decrementAndGet() } }
            } }))
        } }.awaitAll() }
        assertEquals(1, highest.get()); assertEquals(0, active.get())
    }

    @Test fun remoteClaimsNeverAutomaticallyRepeatAnUncertainPrint() {
        val p = PrintClaimPolicy
        assertEquals(PrintClaimPolicy.Decision.ACQUIRE, p.decide("PENDING", null, "me", 0, 100))
        assertEquals(PrintClaimPolicy.Decision.BUSY, p.decide("RECEIVED", "other", "me", 200, 100))
        assertEquals(PrintClaimPolicy.Decision.ACQUIRE, p.decide("RECEIVED", "other", "me", 99, 100))
        assertEquals(PrintClaimPolicy.Decision.REVIEW, p.decide("PRINTING", "me", "me", 200, 100))
        assertEquals(PrintClaimPolicy.Decision.BUSY, p.decide("PRINTING", "other", "me", 200, 100))
        assertEquals(PrintClaimPolicy.Decision.REVIEW, p.decide("PRINTING", "other", "me", 99, 100))
        assertEquals(PrintClaimPolicy.Decision.ALREADY_SENT, p.decide("PRINTED", "me", "me", 0, 100))
    }
}
