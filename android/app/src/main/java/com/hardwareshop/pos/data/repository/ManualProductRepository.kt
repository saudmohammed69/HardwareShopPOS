package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class ManualProductSuggestion(
    val key: String, val billId: Long, val itemId: Long, val name: String,
    val pricePaisa: Long, val status: String = "PENDING", val productId: Long? = null
)
data class ManualBillReview(val bill: BillEntity, val items: List<BillItemEntity>, val manualItems: List<ManualProductSuggestion>)

/** Suggestions come only from saved bill snapshots, including bills received through sync.
 * Review decisions and edits are separate from the issued bill and never rewrite it. */
@Singleton
class ManualProductRepository @Inject constructor(
    private val db: AppDatabase,
    private val products: ProductRepository,
    private val session: SessionHolder
) {
    companion object {
        const val CATEGORY = "Manually Added Products"
        fun key(itemId: Long) = "manual-product:bill-item:$itemId"
    }

    private fun suggestion(item: BillItemEntity, row: SettingsEntity?): ManualProductSuggestion {
        require(item.productId == 0L)
        val json = row?.let { JsonParser.parseString(it.value).asJsonObject }
        val name = json?.get("name")?.asString ?: item.productName
        val price = json?.get("pricePaisa")?.asBigDecimal?.longValueExact() ?: item.unitPricePaisa
        val status = json?.get("status")?.asString ?: "PENDING"
        require(status in setOf("PENDING", "ACCEPTED", "REJECTED"))
        ManualBillItem.create(name, java.math.BigDecimal.valueOf(price, 2).toPlainString(), emptyList())
        return ManualProductSuggestion(key(item.id), item.billId, item.id, name, price, status,
            json?.get("productId")?.asLong)
    }

    fun observeBills(): Flow<List<ManualBillReview>> = flow {
        PermissionChecker.enforceOwner(session.current)
        emitAll(combine(db.billDao().observeManualBills(), db.settingsDao().observeManualProducts()) { bills, rows ->
            PermissionChecker.enforceOwner(session.current)
            val decisions = rows.associateBy { it.key }
            bills.map { bill ->
                val items = db.billDao().getBillItems(bill.id)
                ManualBillReview(bill, items, items.filter { it.productId == 0L }.map { suggestion(it, decisions[key(it.id)]) })
            }
        })
    }

    private suspend fun load(key: String): ManualProductSuggestion {
        PermissionChecker.enforceOwner(session.current)
        val id = key.removePrefix("manual-product:bill-item:").toLongOrNull()
        require(id != null && id > 0 && key == ManualProductRepository.key(id)) { "Invalid manual bill item" }
        val item = requireNotNull(db.billDao().getBillItemById(id)) { "Bill item is unavailable" }
        requireNotNull(db.billDao().getBillById(item.billId)) { "Bill is unavailable" }
        return suggestion(item, db.settingsDao().getSetting(key))
    }

    private suspend fun save(item: ManualProductSuggestion) {
        val json = JsonObject().apply {
            addProperty("name", item.name)
            addProperty("pricePaisa", item.pricePaisa)
            addProperty("status", item.status)
            item.productId?.let { addProperty("productId", it) }
        }
        db.settingsDao().upsertSetting(SettingsEntity(item.key, json.toString()))
    }

    suspend fun edit(key: String, name: String, price: String) = db.withTransaction {
        val current = load(key)
        require(current.status == "PENDING") { "This item has already been reviewed" }
        val line = ManualBillItem.create(name, price, emptyList())
        save(current.copy(name = line.product.name, pricePaisa = line.unitPricePaisa))
    }

    suspend fun accept(key: String): Long? {
        PermissionChecker.enforceOwner(session.current)
        return db.withTransaction {
            val item = load(key)
            if (item.status == "ACCEPTED") return@withTransaction item.productId
            require(item.status == "PENDING") { "This item was rejected" }
            val category = db.categoryDao().getAllCategories().firstOrNull { it.name == CATEGORY }
            val categoryId = category?.id ?: db.categoryDao().insertCategory(CategoryEntity(name = CATEGORY))
            if (category?.isActive == false) db.categoryDao().setActive(categoryId, true)
            val type = db.productTypeDao().getTypesForCategory(categoryId).firstOrNull { it.name == "General" }
            val typeId = type?.id ?: db.productTypeDao().insertType(ProductTypeEntity(categoryId = categoryId, name = "General"))
            if (type?.isActive == false) db.productTypeDao().setActive(typeId, true)
            val existing = db.productDao().getAllProducts().firstOrNull {
                it.categoryId == categoryId && it.typeId == typeId && it.isActive &&
                    it.name.equals(item.name, ignoreCase = true) && it.size.isEmpty() &&
                    it.unit == "Piece" && it.sellingPricePaisa == item.pricePaisa && it.gstRateBp == 1800
            }
            val id = existing?.id ?: when (val result = products.createProduct(
                categoryId, typeId, item.name, "", "Piece", item.pricePaisa, 0, 1800, 0.0, 0.0, null
            )) {
                is Result.Success -> result.data
                is Result.Error -> error(result.message)
                else -> error("Cannot create product")
            }
            save(item.copy(status = "ACCEPTED", productId = id))
            id
        }
    }

    suspend fun reject(key: String) = db.withTransaction {
        val item = load(key)
        require(item.status != "ACCEPTED") { "This item has already been accepted" }
        save(item.copy(status = "REJECTED"))
    }
}
