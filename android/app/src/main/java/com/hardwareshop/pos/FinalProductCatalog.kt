package com.hardwareshop.pos

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.ProductDraft
import com.hardwareshop.pos.domain.model.ProductForm
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Approved master and store workbooks. Room transactions make upgrades retry-safe. */
object FinalProductCatalog {
    internal const val VERSION = "catalog:final-2026-10-08:v1"
    internal const val STORE_VERSION = "catalog:store-2026-10-09:v1"
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    internal data class Row(
        val id: String, val category: String, val type: String, val name: String,
        val size: String, val unit: String, val selling: String, val purchase: String,
        val gst: String, val stock: String, val minimum: String
    )

    internal interface Store {
        suspend fun settings(): List<SettingsEntity>
        suspend fun put(setting: SettingsEntity)
        suspend fun remove(key: String)
        suspend fun categories(): List<CategoryEntity>
        suspend fun types(): List<ProductTypeEntity>
        suspend fun products(): List<ProductEntity>
        suspend fun addCategory(value: CategoryEntity): Long
        suspend fun addType(value: ProductTypeEntity): Long
        suspend fun addProduct(value: ProductEntity): Long
        suspend fun updateProduct(value: ProductEntity)
    }

    internal class RoomStore(private val db: AppDatabase) : Store {
        override suspend fun settings() = db.settingsDao().getAllSettings()
        override suspend fun put(setting: SettingsEntity) = db.settingsDao().upsertSetting(setting)
        override suspend fun remove(key: String) = db.settingsDao().deleteSetting(key)
        override suspend fun categories() = db.categoryDao().getAllCategories()
        override suspend fun types() = db.productTypeDao().getAllTypes()
        override suspend fun products() = db.productDao().getAllProducts()
        override suspend fun addCategory(value: CategoryEntity) = db.categoryDao().insertCategory(value)
        override suspend fun addType(value: ProductTypeEntity) = db.productTypeDao().insertType(value)
        override suspend fun addProduct(value: ProductEntity) = db.productDao().insertProduct(value)
        override suspend fun updateProduct(value: ProductEntity) = db.productDao().updateProduct(value)
    }

    fun install(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                com.hardwareshop.pos.util.BillingLoadDiagnostics.event("startup-catalog-query-start")
                val db = AppDatabase.getInstance(app)
                if (db.settingsDao().getSetting(VERSION) != null &&
                    db.settingsDao().getSetting(STORE_VERSION) != null) {
                    com.hardwareshop.pos.util.BillingLoadDiagnostics.event("startup-pipe-transaction-wait")
                    db.withTransaction {
                        com.hardwareshop.pos.util.BillingLoadDiagnostics.event("startup-pipe-transaction-start")
                        PipeProductCatalog.merge(RoomStore(db))
                        com.hardwareshop.pos.util.BillingLoadDiagnostics.event("startup-pipe-transaction-end")
                    }
                    return@launch
                }
                val rows = app.assets.open("final_catalog.json").bufferedReader().use {
                    gson.fromJson(it, Array<Row>::class.java).toList()
                }
                val supplement = app.assets.open("store_catalog.json").bufferedReader().use {
                    gson.fromJson(it, Array<Row>::class.java).toList()
                }
                withTimeout(30_000) { db.categoryDao().observeAllCategories().first { it.isNotEmpty() } }
                db.withTransaction { mergeBoth(RoomStore(db), rows, supplement, System.currentTimeMillis()); PipeProductCatalog.merge(RoomStore(db)) }
                Timber.i("Approved catalogue installed: priced items in Products; unpriced items in Pending")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No completion marker on failure; the whole transaction rolls back.
                Timber.e(e, "Catalogue install failed; retained existing data and will retry next start")
            } finally {
                com.hardwareshop.pos.util.BillingLoadDiagnostics.event("startup-catalog-finished")
                running.set(false)
            }
        }
    }

    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    internal fun identity(name: String, size: String, unit: String) =
        listOf(name, size, unit).joinToString("\u001f") { normalized(it) }

    internal fun validate(rows: List<Row>) {
        require(rows.map { it.id }.distinct().size == rows.size) { "Duplicate catalogue IDs" }
        require(rows.map { listOf(normalized(it.category), normalized(it.type), identity(it.name, it.size, it.unit)) }
            .distinct().size == rows.size) { "Duplicate catalogue products" }
        rows.forEach { r ->
            require(r.id.startsWith("final-") && r.name.isNotBlank() && r.category.isNotBlank() &&
                r.type.isNotBlank() && r.unit.isNotBlank())
            require(r.gst == "18")
            require(r.selling.isBlank() || (ProductForm.scaled(r.selling) ?: 0) > 0)
            listOf(r.purchase, r.stock, r.minimum).forEach { require(it.isBlank() || ProductForm.scaled(it) != null) }
        }
    }

    /** Called inside a transaction; exposed through Store for deterministic JVM regression tests. */
    internal suspend fun mergeBoth(store: Store, master: List<Row>, supplement: List<Row>, now: Long) {
        // Validate both sources before touching either catalogue.
        validate(master)
        validate(supplement)
        merge(store, master, now)
        merge(store, supplement, now, STORE_VERSION, master.associateBy { it.id })
    }

    internal suspend fun merge(store: Store, rows: List<Row>, now: Long,
                               completionKey: String = VERSION, baseline: Map<String, Row> = emptyMap()) {
        validate(rows)
        val settings = store.settings().associateBy { it.key }.toMutableMap()
        if (completionKey in settings) return
        suspend fun put(key: String, value: String) {
            val setting = SettingsEntity(key, value, now)
            store.put(setting)
            settings[key] = setting
        }
        val categories = store.categories().toMutableList()
        val types = store.types().toMutableList()
        val products = store.products().toMutableList()

        // Recognise the previous bundled drafts, not unrelated owner-created drafts.
        // Archive only exact untouched imports. Edited/unreadable drafts are kept.
        val protected = mutableSetOf<String>()
        val legacyProducts = mutableMapOf<String, Long>()
        settings.values.toList().filter { it.key.startsWith("product:catalog-data:master-item-") }.forEach { metadata ->
            val old = runCatching { gson.fromJson(metadata.value, ShopItemCatalog.Item::class.java) }.getOrNull()
                ?: return@forEach
            val id = metadata.key.removePrefix("product:catalog-data:")
            val key = identity(old.displayName, old.size.orEmpty(), old.unit.orEmpty().ifBlank { "Piece" })
            val done = settings["product:done:$id"]
            if (done?.value == "discarded") protected += key
            done?.value?.toLongOrNull()?.let { legacyProducts[key] = it }
            val savedDraft = settings["product:draft:$id"] ?: return@forEach
            val draft = runCatching { gson.fromJson(savedDraft.value, ProductDraft::class.java) }.getOrNull()
            val form = draft?.form
            val category = categories.firstOrNull { it.id == form?.categoryId }
            val type = types.firstOrNull { it.id == form?.typeId }
            val untouched = draft?.id == id && form != null && draft.productId == null && draft.ignoredOptional.isEmpty() &&
                form == ProductForm(categoryId = form.categoryId, typeId = form.typeId,
                    name = old.displayName, size = old.size.orEmpty(), unit = old.unit.orEmpty(),
                    sellingPrice = old.selling.orEmpty(), purchasePrice = old.buying,
                    gstPercent = "", stock = old.stock, minimumStock = "", aliases = old.searchTerms) &&
                category?.name == old.group.category && type?.name == old.group.type
            if (untouched && completionKey == VERSION) {
                put("$VERSION:archive:$id", savedDraft.value)
                store.remove(savedDraft.key)
                settings.remove(savedDraft.key)
            } else if (!untouched) {
                protected += key
                form?.let { protected += identity(it.name, it.size, it.unit) }
            }
        }

        suspend fun resolveCategory(name: String): CategoryEntity {
            categories.firstOrNull { normalized(it.name) == normalized(name) }?.let { return it }
            val value = CategoryEntity(name = name, displayOrder = categories.size, createdAt = now, updatedAt = now)
            return value.copy(id = store.addCategory(value)).also { categories += it }
        }
        suspend fun resolveType(categoryId: Long, name: String): ProductTypeEntity {
            types.firstOrNull { it.categoryId == categoryId && normalized(it.name) == normalized(name) }?.let { return it }
            val value = ProductTypeEntity(categoryId = categoryId, name = name, displayOrder = types.size,
                createdAt = now, updatedAt = now)
            return value.copy(id = store.addType(value)).also { types += it }
        }

        rows.forEach { r ->
            val doneKey = "product:done:${r.id}"
            if (doneKey in settings) return@forEach
            val key = identity(r.name, r.size, r.unit)
            if (key in protected) return@forEach
            val category = resolveCategory(r.category)
            val type = resolveType(category.id, r.type)
            // Never reactivate an owner-disabled group or product.
            if (!category.isActive || !type.isActive) return@forEach
            val matches = products.filter { it.categoryId == category.id && it.typeId == type.id &&
                identity(it.name, it.size, it.unit) == key }
            if (matches.size > 1) return@forEach // ambiguous existing data: do not change it
            val existing = legacyProducts[key]?.let { id -> products.firstOrNull { it.id == id } }
                ?: matches.singleOrNull()
            if (existing != null) {
                if (existing.isActive) {
                    val grout = normalized(r.name) in setOf("grout – ivory", "grout – coffee brown")
                    val updated = existing.copy(gstRateBp = 1800,
                        sellingPricePaisa = if (grout) 13000 else existing.sellingPricePaisa,
                        updatedAt = now)
                    if (existing.gstRateBp != updated.gstRateBp || existing.sellingPricePaisa != updated.sellingPricePaisa)
                        store.updateProduct(updated)
                }
                put(doneKey, existing.id.toString())
                return@forEach
            }
            val form = ProductForm(categoryId = category.id, typeId = type.id, name = r.name,
                size = r.size, unit = r.unit, sellingPrice = r.selling, purchasePrice = r.purchase,
                gstPercent = "18", stock = r.stock, minimumStock = r.minimum)
            val draftKey = "product:draft:${r.id}"
            val previousDraft = settings[draftKey]
            if (r.selling.isNotBlank() && previousDraft != null) {
                val draft = runCatching { gson.fromJson(previousDraft.value, ProductDraft::class.java) }.getOrNull()
                val original = baseline[r.id]
                // Promote only the untouched unpriced master draft. Never erase
                // the owner's edited name, price, stock, photo or other details.
                if (original == null || original.selling.isNotBlank() || draft?.id != r.id ||
                    draft.productId != null || draft.ignoredOptional.isNotEmpty() ||
                    draft.form != form.copy(sellingPrice = original.selling)) return@forEach
            }
            if (r.selling.isBlank()) {
                // Preserve a draft edited since an earlier partial/versioned import.
                if (draftKey !in settings) put(draftKey, gson.toJson(ProductDraft(r.id, form = form, updatedAt = now)))
            } else {
                val product = ProductEntity(categoryId = category.id, typeId = type.id, name = r.name,
                    size = r.size, unit = r.unit, sellingPricePaisa = checkNotNull(ProductForm.scaled(r.selling)),
                    purchasePricePaisa = ProductForm.scaled(r.purchase) ?: 0, gstRateBp = 1800,
                    currentStock = r.stock.toDoubleOrNull() ?: 0.0, minimumStock = r.minimum.toDoubleOrNull() ?: 0.0,
                    createdAt = now, updatedAt = now)
                val productId = store.addProduct(product)
                products += product.copy(id = productId)
                put(doneKey, productId.toString())
                if (previousDraft != null) {
                    put("$completionKey:archive:${r.id}", previousDraft.value)
                    store.remove(draftKey)
                    settings.remove(draftKey)
                }
            }
        }
        put(completionKey, "installed")
    }
}
