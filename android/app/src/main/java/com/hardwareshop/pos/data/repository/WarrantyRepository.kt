package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.database.toDomain
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.PermissionChecker
import com.hardwareshop.pos.util.SessionHolder
import com.hardwareshop.pos.util.WarrantyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owner's serial register for this phone. Included in local backups; not in cloud sync. */
@Singleton
class WarrantyRepository @Inject constructor(private val db: AppDatabase, private val session: SessionHolder) {
    data class Service(
        val id: String,
        val date: String,
        val problem: String,
        val action: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class Record(
        val id: String,
        val productId: Long,
        val productLabel: String,
        val serial: String,
        val customerName: String,
        val customerPhone: String,
        val saleDate: String,
        val warrantyMonths: Int,
        val billId: Long? = null,
        val billNumber: String = "",
        val services: List<Service> = emptyList(),
        val revision: String = UUID.randomUUID().toString(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    ) {
        val expiryDate: String get() = WarrantyRules.expiry(saleDate, warrantyMonths).toString()
        fun expired(today: LocalDate = LocalDate.now()) = WarrantyRules.expired(saleDate, warrantyMonths, today)
    }

    data class Draft(
        val productId: Long = 0,
        val serial: String = "",
        val customerName: String = "",
        val customerPhone: String = "",
        val saleDate: String = LocalDate.now().toString(),
        val warrantyMonths: String = "12",
        val billNumber: String = ""
    ) {
        companion object {
            fun from(record: Record) = Draft(record.productId, record.serial, record.customerName,
                record.customerPhone, record.saleDate, record.warrantyMonths.toString(), record.billNumber)
        }
    }

    private val gson = Gson()
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun owner() = PermissionChecker.enforceOwner(session.current)

    private fun decode(row: SettingsEntity): Record = try {
        val record = requireNotNull(gson.fromJson(row.value, Record::class.java))
        require(record.id.matches(uuidPattern) && row.key == "warranty:${record.id}")
        require(record.productId > 0 && record.productLabel.isNotBlank() && record.productLabel.length <= 400)
        WarrantyRules.serial(record.serial)
        require(record.customerName.isNotBlank() && record.customerName.length <= 100)
        WarrantyRules.phone(record.customerPhone)
        WarrantyRules.expiry(record.saleDate, record.warrantyMonths)
        require(record.revision.matches(uuidPattern) && record.createdAt > 0 && record.updatedAt > 0)
        require((record.billId == null && record.billNumber.isEmpty()) ||
            (record.billId != null && record.billId > 0 && record.billNumber.isNotBlank() && record.billNumber.length <= 100))
        require(record.services.size <= 1000 && record.services.map { it.id }.distinct().size == record.services.size)
        record.services.forEach {
            require(it.id.matches(uuidPattern) && it.createdAt > 0)
            require(!WarrantyRules.date(it.date).isBefore(WarrantyRules.date(record.saleDate)))
            require(it.problem.isNotBlank() && it.problem.length <= 1000 && it.action.isNotBlank() && it.action.length <= 1000)
        }
        record
    } catch (e: Exception) {
        throw IllegalStateException("A warranty record is damaged. Restore a valid backup before changing the register. Existing records were kept.", e)
    }

    private fun records(rows: List<SettingsEntity>): List<Record> = rows.map(::decode).also { list ->
        require(list.map { it.productId to WarrantyRules.serialKey(it.serial) }.distinct().size == list.size) {
            "The register contains duplicate serial numbers for a product. Restore a valid backup before changing it."
        }
    }

    fun observe(): Flow<List<Record>> = flow {
        owner()
        emitAll(db.settingsDao().observeFeatureRecords("warranty:").map { rows -> owner(); records(rows) })
    }.flowOn(Dispatchers.Default)

    fun observeProducts(): Flow<List<Product>> = flow {
        owner()
        emitAll(combine(db.productDao().observeAllActiveProducts(), db.categoryDao().observeCategoryLabels(),
            db.productTypeDao().observeTypeLabels()) { products, categories, types ->
            owner()
            val categoryNames = categories.associate { it.id to it.name }
            val typeNames = types.associate { it.id to it.name }
            products.map { it.toDomain(categoryNames[it.categoryId].orEmpty(), typeNames[it.typeId].orEmpty()) }
        })
    }.flowOn(Dispatchers.Default)

    suspend fun snapshot(): List<Record> {
        owner()
        return records(db.settingsDao().getAllSettings().filter { it.key.startsWith("warranty:") })
    }

    /** New records and edits share validation. Edits always retain the current service history. */
    suspend fun save(draft: Draft, existing: Record? = null): Record = db.withTransaction {
        owner()
        val all = snapshot()
        val current = existing?.let { expected ->
            all.firstOrNull { it.id == expected.id }?.also {
                require(it.revision == expected.revision) { "This record changed. Reopen it before editing." }
            } ?: throw IllegalArgumentException("This record is no longer available. Refresh the register.")
        }
        val product = requireNotNull(db.productDao().getProductById(draft.productId)) { "Choose a saved product" }
        require(product.isActive || current?.productId == product.id) { "Choose an active saved product" }
        val serial = WarrantyRules.serial(draft.serial)
        require(!WarrantyRules.duplicate(product.id, serial, all.filter { it.id != current?.id }.map { it.productId to it.serial })) {
            "This product already has that serial number in the register"
        }
        val customer = draft.customerName.trim()
        require(customer.isNotBlank() && customer.length <= 100) { "Enter a customer name of up to 100 characters" }
        val phone = WarrantyRules.phone(draft.customerPhone)
        val sale = WarrantyRules.saleDate(draft.saleDate)
        val months = WarrantyRules.months(draft.warrantyMonths)
        WarrantyRules.expiry(sale, months)
        require(current?.services?.all { !WarrantyRules.date(it.date).isBefore(WarrantyRules.date(sale)) } != false) {
            "Sale date cannot be after an existing service entry"
        }
        val number = draft.billNumber.trim()
        require(number.length <= 100) { "Bill number is too long" }
        val bill = if (number.isNotEmpty()) {
            requireNotNull(db.billDao().getBillByNumber(number)) { "Bill number was not found. Use an existing bill number or leave it blank." }.also {
                require(it.status == "ACTIVE") { "Only an active bill can be linked" }
                require(db.billDao().getBillItems(it.id).any { line -> line.productId == product.id }) {
                    "That bill does not contain the selected product"
                }
            }
        } else null
        val now = System.currentTimeMillis()
        val record = Record(current?.id ?: UUID.randomUUID().toString(), product.id,
            if (current?.productId == product.id) current.productLabel else product.toDomain().displayLabel.take(400),
            serial, customer, phone, sale, months, bill?.id, bill?.billNumber.orEmpty(),
            current?.services ?: emptyList(), UUID.randomUUID().toString(), current?.createdAt ?: now, now)
        owner()
        db.settingsDao().upsertSetting(SettingsEntity("warranty:${record.id}", gson.toJson(record), now))
        record
    }

    suspend fun addService(record: Record, date: String, problem: String, action: String): Record = db.withTransaction {
        owner()
        val current = snapshot().firstOrNull { it.id == record.id }
            ?: throw IllegalArgumentException("This record is no longer available. Refresh the register.")
        require(current.revision == record.revision) { "This record changed. Reopen it before adding service." }
        require(current.services.size < 1000) { "This record has reached its service history limit" }
        val serviceDate = WarrantyRules.serviceDate(date, current.saleDate)
        val problemText = problem.trim()
        val actionText = action.trim()
        require(problemText.isNotBlank() && problemText.length <= 1000) { "Enter the service problem (up to 1000 characters)" }
        require(actionText.isNotBlank() && actionText.length <= 1000) { "Enter the service action or notes (up to 1000 characters)" }
        val now = System.currentTimeMillis()
        val updated = current.copy(services = current.services + Service(UUID.randomUUID().toString(), serviceDate,
            problemText, actionText, now), revision = UUID.randomUUID().toString(), updatedAt = now)
        owner()
        db.settingsDao().upsertSetting(SettingsEntity("warranty:${updated.id}", gson.toJson(updated), now))
        updated
    }
}
