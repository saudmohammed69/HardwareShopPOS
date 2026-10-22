package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.ManualBillItem
import com.hardwareshop.pos.util.PermissionChecker
import com.hardwareshop.pos.util.PipeBilling
import com.hardwareshop.pos.util.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Estimates are local records, included in backups; they never reserve stock or create sales. */
@Singleton
class QuotationRepository @Inject constructor(
    private val db: AppDatabase,
    private val session: SessionHolder,
    private val products: ProductRepository
) {
    data class Line(
        val productId: Long, val productName: String, val productSize: String, val productUnit: String,
        val categoryName: String, val typeName: String, val quantity: Double,
        val unitPricePaisa: Long, val itemTotalPaisa: Long, val gstRateBp: Int,
        val isPipe: Boolean = false, val pipeLengths: Long = 0, val pipeFeet: Double = 0.0,
        val pipeFootPricePaisa: Long = 0, val pipeFeetPerLength: Int = 0, val gstAmountPaisa: Long = 0
    ) {
        val label: String get() = listOf(productName, productSize).filter { it.isNotBlank() }.joinToString(" · ")
        val quantityDisplay: String get() = if (isPipe)
            "$pipeLengths Len + ${CalculationEngine.formatQuantity(pipeFeet)} Ft"
            else "${CalculationEngine.formatQuantity(quantity)} $productUnit"

        private fun snapshotProduct() = Product(productId, 0, categoryName, 0, typeName, productName,
            productSize, productUnit, unitPricePaisa, 0, gstRateBp, 0.0, 0.0, null, isActive = true,
            isPipe = isPipe, pipeLengthPricePaisa = unitPricePaisa,
            pipeFootPricePaisa = pipeFootPricePaisa, pipeFeetPerLength = pipeFeetPerLength)

        fun snapshotCartItem() = CartItem(snapshotProduct(), quantity, unitPricePaisa, itemTotalPaisa,
            pipeLengths = pipeLengths, pipeFeet = pipeFeet, pipeFootPricePaisa = pipeFootPricePaisa)
    }

    data class Record(
        val id: String, val number: String, val customerName: String, val customerPhone: String,
        val validUntil: String, val notes: String, val discountPaisa: Long,
        val gstEnabled: Boolean, val gstInclusive: Boolean, val createdAt: Long,
        val lines: List<Line>, val subtotalPaisa: Long, val gstAmountPaisa: Long, val totalPaisa: Long,
        val revision: String = UUID.randomUUID().toString(),
        /** Derived from an atomic checkout marker; the JSON value is never trusted. */
        val convertedBillId: Long? = null
    ) {
        fun isExpired(today: LocalDate = LocalDate.now()): Boolean = LocalDate.parse(validUntil).isBefore(today)
        val grossSubtotalPaisa: Long get() = lines.fold(0L) { total, line -> Math.addExact(total, line.itemTotalPaisa) }
    }

    data class Conversion(val items: List<CartItem>, val changedPriceCount: Int, val expired: Boolean)
    private val gson = Gson()

    private fun decode(row: SettingsEntity): Record = try {
        val record = gson.fromJson(row.value, Record::class.java)
        require(record.id.matches(Regex("[a-f0-9-]{36}")) && row.key == "quotation:${record.id}")
        require(record.number.matches(Regex("Q-\\d{8}-[A-F0-9]{8}")) && record.revision.isNotBlank() && record.createdAt > 0)
        validateText(record.customerName, record.customerPhone, record.notes)
        validDate(record.validUntil)
        val calculation = calculateSnapshot(record.lines.map { it.snapshotCartItem() }, record.discountPaisa,
            record.gstEnabled, record.gstInclusive)
        require(calculation.subtotalPaisa == record.subtotalPaisa && calculation.gstAmountPaisa == record.gstAmountPaisa &&
            calculation.totalPaisa == record.totalPaisa && calculation.itemGstPaisa == record.lines.map { it.gstAmountPaisa })
        record.copy(convertedBillId = null)
    } catch (e: Exception) {
        throw IllegalStateException("A saved quotation is damaged. Restore a valid backup before using the register.", e)
    }

    private fun withBill(record: Record, marker: SettingsEntity?): Record = record.copy(convertedBillId = marker?.let {
        requireNotNull(it.value.toLongOrNull()?.takeIf { id -> id > 0 }) { "The quotation's bill link is damaged. Restore a valid backup." }
    })

    fun observe(): Flow<List<Record>> = db.settingsDao().observeFeatureRecords("quotation")
        .onStart { PermissionChecker.enforceOwner(session.current) }
        .map { rows ->
            PermissionChecker.enforceOwner(session.current)
            val markers = rows.filter { it.key.startsWith("quotation-bill:") }.associateBy { it.key }
            rows.filter { it.key.startsWith("quotation:") }.map { row ->
                val record = decode(row)
                withBill(record, markers["quotation-bill:${record.id}"])
            }.sortedByDescending { it.createdAt }
        }.flowOn(Dispatchers.Default)

    suspend fun snapshot(): List<Record> {
        PermissionChecker.enforceOwner(session.current)
        val rows = db.settingsDao().getAllSettings()
        val markers = rows.associateBy { it.key }
        return rows.filter { it.key.startsWith("quotation:") }.map { row ->
            val record = decode(row)
            withBill(record, markers["quotation-bill:${record.id}"])
        }.sortedByDescending { it.createdAt }
    }

    suspend fun saveFromCart(items: List<CartItem>, customerName: String, customerPhone: String,
        validUntil: String, notes: String, discountPaisa: Long, gstEnabled: Boolean, gstInclusive: Boolean): Record = db.withTransaction {
        PermissionChecker.enforceOwner(session.current)
        snapshot() // Do not hide unreadable records by appending new ones.
        val name = customerName.trim(); val phone = customerPhone.trim(); val cleanNotes = notes.trim()
        validateText(name, phone, cleanNotes)
        val date = validDate(validUntil.trim())
        require(!date.isBefore(LocalDate.now())) { "Validity date cannot be before today" }
        val calc = calculateSnapshot(items, discountPaisa, gstEnabled, gstInclusive)
        items.filter { it.product.id > 0 }.forEach {
            require(db.productDao().getProductById(it.product.id)?.isActive == true) {
                "${it.product.displayLabel} is no longer active. Review the cart before saving."
            }
        }
        val id = UUID.randomUUID().toString()
        val record = Record(id, "Q-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${id.take(8).uppercase()}",
            name, phone, date.toString(), cleanNotes, discountPaisa, gstEnabled, gstInclusive,
            System.currentTimeMillis(), items.mapIndexed { index, item ->
                Line(item.product.id, item.product.name, item.product.size, item.product.unit,
                    item.product.categoryName, item.product.typeName, item.quantity, item.unitPricePaisa,
                    item.itemTotalPaisa, item.product.gstRateBp, item.product.isPipe, item.pipeLengths,
                    item.pipeFeet, item.pipeFootPricePaisa, item.product.pipeFeetPerLength, calc.itemGstPaisa[index])
            }, calc.subtotalPaisa, calc.gstAmountPaisa, calc.totalPaisa)
        db.settingsDao().upsertSetting(SettingsEntity("quotation:$id", gson.toJson(record)))
        record
    }

    private suspend fun currentRecord(record: Record): Record {
        PermissionChecker.enforceOwner(session.current)
        val row = db.settingsDao().getSetting("quotation:${record.id}") ?: error("This quotation was removed. Refresh the list.")
        val current = decode(row)
        require(current.revision == record.revision) { "This quotation changed. Refresh the list." }
        return withBill(current, db.settingsDao().getSetting("quotation-bill:${record.id}"))
    }

    suspend fun prepareConversion(record: Record): Conversion = db.withTransaction {
        val saved = currentRecord(record)
        require(saved.convertedBillId == null) { "This quotation already has a saved bill" }
        val items = saved.lines.map { line ->
            if (line.productId < 0) line.snapshotCartItem().also(ManualBillItem::validate)
            else {
                val current = products.getProductById(line.productId)
                require(current?.isActive == true) { "${line.label} is unavailable. Reactivate it in Products before billing this quotation." }
                conversionLine(line, current)
            }
        }
        val changed = saved.lines.indices.count { index ->
            val line = saved.lines[index]; val item = items[index]
            line.unitPricePaisa != item.unitPricePaisa || line.pipeFootPricePaisa != item.pipeFootPricePaisa ||
                line.gstRateBp != item.product.gstRateBp || line.itemTotalPaisa != item.itemTotalPaisa
        }
        Conversion(items, changed, saved.isExpired())
    }

    suspend fun remove(record: Record) = db.withTransaction {
        val current = currentRecord(record)
        require(current.convertedBillId == null) { "A billed quotation cannot be deleted" }
        db.settingsDao().deleteSetting("quotation:${record.id}")
    }

    companion object {
        fun defaultValidUntil(): String = LocalDate.now().plusDays(7).toString()

        private fun validDate(value: String): LocalDate {
            require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Enter validity as YYYY-MM-DD" }
            return try { LocalDate.parse(value) } catch (_: Exception) { throw IllegalArgumentException("Enter a real validity date (YYYY-MM-DD)") }
        }

        private fun validateText(name: String, phone: String, notes: String) {
            require(name.isNotBlank() && name.length <= 100 && name.none { it.isISOControl() }) { "Enter a customer name (up to 100 characters)" }
            require(phone.length <= 40 && phone.none { it.isISOControl() }) { "Phone number is too long" }
            require(notes.length <= 1000 && notes.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Notes must be up to 1000 characters" }
        }

        /** Same paisa/discount/GST engine as checkout, including independent pipe bases. */
        fun calculateSnapshot(items: List<CartItem>, discountPaisa: Long, gstEnabled: Boolean,
            gstInclusive: Boolean): CalculationEngine.BillCalculation {
            require(items.isNotEmpty() && items.size <= 300) { "Add 1–300 items before saving a quotation" }
            require(items.map { it.product.id }.distinct().size == items.size) { "Combine duplicate items before saving the quotation" }
            items.forEach { item ->
                require(item.product.id != 0L && item.product.name.isNotBlank() && item.product.unit.isNotBlank()) { "Invalid quotation item" }
                require(item.quantity.isFinite() && item.quantity > 0 && item.unitPricePaisa > 0) { "Enter positive quantities and prices" }
                if (item.product.id < 0) {
                    ManualBillItem.validate(item)
                    require(!item.product.isPipe) { "Manual pipe items are not supported" }
                }
                if (item.product.isPipe) {
                    require(item.pipeLengths > 0 || item.pipeFeet > 0)
                    require(item.pipeFootPricePaisa > 0)
                    require(PipeBilling.stockQuantity(item.product, item.pipeLengths, item.pipeFeet) == item.quantity) { "Invalid pipe quantity" }
                } else require(item.pipeLengths == 0L && item.pipeFeet == 0.0 && item.pipeFootPricePaisa == 0L) { "Invalid item bases" }
                require(PipeBilling.amount(item) == item.itemTotalPaisa) { "Cart total changed. Review the cart before saving." }
            }
            val gross = items.fold(0L) { sum, item -> Math.addExact(sum, item.itemTotalPaisa) }
            require(discountPaisa in 0..gross) { "Discount cannot exceed the quotation amount" }
            val amounts = items.map(PipeBilling::calculation)
            return if (gstEnabled && gstInclusive) CalculationEngine.calculateBillInclusive(amounts, discountPaisa)
                else CalculationEngine.calculateBill(amounts, discountPaisa, gstEnabled)
        }

        fun conversionLine(line: Line, current: Product): CartItem {
            require(current.id == line.productId && current.isActive) { "Quotation item is unavailable" }
            require(current.unit == line.productUnit && current.isPipe == line.isPipe) { "${line.label} changed its selling unit. Review it in Products before billing." }
            return if (line.isPipe) {
                require(current.pipeFeetPerLength == line.pipeFeetPerLength) { "${line.label} changed its full-pipe length. Review it in Products before billing." }
                PipeBilling.item(current, line.pipeLengths, line.pipeFeet)
            } else {
                require(current.sellingPricePaisa > 0) { "Set a selling price for ${line.label}" }
                CartItem(current, line.quantity, current.sellingPricePaisa,
                    CalculationEngine.itemTotalPaisa(line.quantity, current.sellingPricePaisa))
            }
        }
    }
}
