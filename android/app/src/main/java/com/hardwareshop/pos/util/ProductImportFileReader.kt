package com.hardwareshop.pos.util

import android.content.ContentResolver
import android.net.Uri
import com.hardwareshop.pos.data.repository.ProductImportRow
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.Locale
import java.util.zip.ZipInputStream

object ProductImportFileReader {
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 32 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private const val MAX_COLUMNS = 256
    private const val MAX_CELLS = 1_000_000

    fun read(contentResolver: ContentResolver, uri: Uri): List<ProductImportRow> {
        val bytes = contentResolver.openInputStream(uri)?.use {
            readBounded(it, MAX_FILE_BYTES, "Import file is too large (maximum 8 MB). Split it into smaller files.")
        }
            ?: error("Cannot open selected file")
        val name = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        return if (name.endsWith(".csv")) readCsv(bytes.decodeToString()) else readXlsx(bytes)
    }

    fun readCsvDirect(text: String): List<ProductImportRow> = readCsv(text)

    private fun readCsv(text: String): List<ProductImportRow> {
        var cells = 0
        val rows = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                require(index < MAX_ROWS) { "Import has too many rows. Split it into smaller files." }
                parseCsvLine(line).also {
                    cells += it.size
                    require(cells <= MAX_CELLS) { "Import has too many cells. Split it into smaller files." }
                }
            }
            .toList()
        return rowsToProducts(rows)
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out += cell.toString()
                    require(out.size < MAX_COLUMNS) { "Import has too many columns (maximum 256)." }
                    cell.clear()
                }
                else -> cell.append(ch)
            }
            i++
        }
        require(!quoted) { "CSV contains an unfinished quoted cell. Multiline cells are not supported." }
        out += cell.toString()
        return out
    }

    private fun readXlsx(bytes: ByteArray): List<ProductImportRow> {
        val entries = mutableMapOf<String, ByteArray>()
        var expanded = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 1000) { "Excel file has too many archive entries." }
                if (!entry.isDirectory) {
                    val entryBytes = readBounded(zip, minOf(MAX_ENTRY_BYTES, MAX_EXPANDED_BYTES - expanded),
                        "Excel file expands beyond the safe import limit. Split it into smaller files.")
                    expanded += entryBytes.size
                    if (entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet")) {
                        require(!entries.containsKey(entry.name)) { "Excel file contains duplicate worksheet entries." }
                        entries[entry.name] = entryBytes
                    }
                }
                zip.closeEntry()
            }
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: entries.entries.firstOrNull { it.key.startsWith("xl/worksheets/sheet") }?.value
            ?: error("No worksheet found in Excel file")
        return rowsToProducts(readSheet(sheet, sharedStrings))
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val parser = newParser(xml)
        val strings = mutableListOf<String>()
        var inText = false
        val current = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "t") inText = true
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        strings += current.toString()
                        current.clear()
                    }
                }
            }
        }
        return strings
    }

    private fun readSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = newParser(xml)
        val rows = mutableListOf<MutableList<String>>()
        var currentRow: MutableList<String>? = null
        var currentColumn = 0
        var cellType = ""
        var cellValue = ""
        var inValue = false
        var inInlineText = false
        var cells = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        require(rows.size < MAX_ROWS) { "Import has too many rows. Split it into smaller files." }
                        currentRow = mutableListOf()
                    }
                    "c" -> {
                        currentColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        cellValue = ""
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
                XmlPullParser.TEXT -> {
                    if (inValue || inInlineText) cellValue += parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> currentRow?.let { row ->
                        while (row.size < currentColumn) row += ""
                        row += when (cellType) {
                            "s" -> sharedStrings.getOrNull(cellValue.toIntOrNull() ?: -1).orEmpty()
                            else -> cellValue
                        }
                    }
                    "row" -> currentRow?.let {
                        cells += it.size
                        require(cells <= MAX_CELLS) { "Import has too many cells. Split it into smaller files." }
                        rows += it
                        currentRow = null
                    }
                }
            }
        }
        return rows
    }

    private fun rowsToProducts(rows: List<List<String>>): List<ProductImportRow> {
        if (rows.isEmpty()) return emptyList()
        val headers = rows.first().mapIndexed { index, value -> normalizeHeader(value) to index }.toMap()
        fun idx(vararg names: String) = names.firstNotNullOfOrNull { headers[normalizeHeader(it)] }
        val category = idx("category", "cat")
        val type = idx("type", "product type")
        val item = idx("item name", "item", "product", "product name", "name")
        val size = idx("size")
        val unit = idx("unit", "unit type")
        val buying = idx("buying price", "purchase price", "cost price", "rate")
        val selling = idx("selling price", "sale price", "price")
        val stock = idx("stock", "qty", "quantity")
        val minimum = idx("minimum stock", "min stock")
        val gst = idx("gst", "gst %", "tax")
        val aliases = idx("aliases", "search aliases")

        return rows.drop(1).map { row ->
            ProductImportRow(
                category = row.cell(category),
                type = row.cell(type),
                name = row.cell(item),
                size = row.cell(size),
                unit = row.cell(unit),
                buyingPricePaisa = money(row.cell(buying)),
                sellingPricePaisa = money(row.cell(selling)),
                stock = quantity(row.cell(stock)),
                minimumStock = quantity(row.cell(minimum)),
                gstRateBp = taxRate(row.cell(gst)),
                aliases = row.cell(aliases)
            )
        }.filter { it.name.isNotBlank() || it.category.isNotBlank() || it.type.isNotBlank() }
    }

    private fun List<String>.cell(index: Int?): String =
        index?.let { getOrNull(it) }.orEmpty().trim()

    private fun normalizeHeader(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    private fun money(value: String): Long? {
        val text = cleanNumber(value)
        if (text.isBlank()) return null
        return runCatching { BigDecimal(text).movePointRight(2).longValueExact() }.getOrNull()
    }

    private fun quantity(value: String): Double? {
        val text = cleanNumber(value)
        if (text.isBlank()) return null
        return text.toDoubleOrNull()
    }

    private fun taxRate(value: String): Int? {
        if (value.isBlank()) return null
        val rate = money(value) ?: return -1
        return if (rate in 0L..10000L) rate.toInt() else -1
    }

    private fun cleanNumber(value: String): String =
        value.replace("₹", "").replace("%", "").replace(",", "").trim()

    private fun columnIndex(reference: String): Int {
        if (reference.isBlank()) return 0
        val letters = reference.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
        require(letters.length in 1..3 && reference.drop(letters.length).all { it.isDigit() }) {
            "Invalid Excel cell reference."
        }
        var index = 0
        letters.forEach { index = index * 26 + (it - 'A' + 1) }
        require(index in 1..MAX_COLUMNS) { "Import has too many columns (maximum 256)." }
        return index - 1
    }

    private fun readBounded(input: InputStream, limit: Int, message: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (read == -1) break
            require(output.size() + read <= limit) { message }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun newParser(xml: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
}
