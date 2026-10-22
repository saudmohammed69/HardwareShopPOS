package com.hardwareshop.pos

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProductImportSafetyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun readFile(extension: String, write: (File) -> Unit): List<ProductImportRow> {
        val file = File(context.cacheDir, "audit-import-${java.util.UUID.randomUUID()}.$extension")
        return try {
            write(file)
            ProductImportFileReader.read(context.contentResolver,
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } finally { file.delete() }
    }
    private fun rejected(expected: String, block: () -> Unit) {
        try { block(); fail("Unsafe import should be rejected") }
        catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expected))
        }
    }
    private fun workbook(file: File, xml: String) {
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            it.write(xml.toByteArray()); it.closeEntry()
        }
    }
    @Test fun ordinaryCsvPreservesQuotedNamesAndExactAmounts() {
        val rows = readFile("csv") { it.writeText("Category,Type,Name,Unit,Price,Stock,GST\nPVC,Elbow,\"Elbow, white\",Piece,12.50,20,18\n") }
        assertEquals("Elbow, white", rows.single().name)
        assertEquals(1250L, rows.single().sellingPricePaisa)
        assertEquals(1800, rows.single().gstRateBp)
    }
    @Test fun malformedOrOverflowingTaxNeverWrapsToAnAcceptedRate() {
        val rows = readFile("csv") { it.writeText("Name,Price,GST\nPipe,10,42949672.96\nElbow,5,not-a-rate\n") }
        assertTrue(rows.all { it.gstRateBp == -1 })
    }
    @Test fun oversizedSourceRejectsBeforeParsing() {
        rejected("maximum 8 MB") {
            readFile("csv") { file -> file.outputStream().use { out ->
                val block = ByteArray(1024 * 1024) { ' '.code.toByte() }
                repeat(9) { out.write(block) }
            } }
        }
    }
    @Test fun highlyCompressedWorksheetRejectsWithoutAllocatingEntireExpansion() {
        rejected("safe import limit") {
            readFile("xlsx") { file -> ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val block = ByteArray(1024 * 1024) { ' '.code.toByte() }
                repeat(17) { zip.write(block) }
                zip.closeEntry()
            } }
        }
    }
    @Test fun sparseOversizedColumnRejectsBeforeCreatingHugeRow() {
        rejected("too many columns") {
            readFile("xlsx") { workbook(it, "<worksheet><sheetData><row><c r=\"XFD1\" t=\"inlineStr\"><is><t>Name</t></is></c></row></sheetData></worksheet>") }
        }
    }
    @Test fun ordinaryInlineWorkbookRemainsCompatible() {
        val rows = readFile("xlsx") { workbook(it, """<worksheet><sheetData>
            <row><c r="A1" t="inlineStr"><is><t>Name</t></is></c><c r="B1" t="inlineStr"><is><t>Price</t></is></c></row>
            <row><c r="A2" t="inlineStr"><is><t>Brass elbow</t></is></c><c r="B2"><v>25.50</v></c></row>
            </sheetData></worksheet>""") }
        assertEquals("Brass elbow", rows.single().name)
        assertEquals(2550L, rows.single().sellingPricePaisa)
    }
    @Test fun unfinishedQuotedCsvRejectsInsteadOfImportingDamagedName() {
        rejected("unfinished quoted cell") {
            readFile("csv") { it.writeText("Name,Price\n\"Pipe,10\n") }
        }
    }
    @Test fun invalidTaxRowsDoNotCreateProductsOrGroups() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val session = SessionHolder().apply { setSession(UserSession.OwnerSession("Audit")) }
            val repository = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(),
                db.stockDao(), AuditRepository(db.auditLogDao(), session), session)
            val rows = listOf(-1, 10001).map { ProductImportRow("Invalid category", "Invalid type", "Bad tax item",
                "", "Piece", null, 1000, 5.0, gstRateBp = it) }
            val result = repository.importProducts(rows) as Result.Success
            assertEquals(2, result.data.skipped)
            assertTrue(db.productDao().getAllProducts().isEmpty())
            assertTrue(db.categoryDao().getAllCategories().isEmpty())
            assertTrue(db.productTypeDao().getAllTypes().isEmpty())
        } finally { db.close() }
    }
}
