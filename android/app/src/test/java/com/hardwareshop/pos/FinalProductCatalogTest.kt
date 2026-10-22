package com.hardwareshop.pos

import com.google.gson.Gson
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.ProductDraft
import com.hardwareshop.pos.domain.model.ProductForm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FinalProductCatalogTest {
    private val gson = Gson()
    private fun row(id: String = "one", name: String = "Cup brush", price: String = "138") =
        FinalProductCatalog.Row("final-$id", "Hardware", "Brushes", name, "", "Piece", price, "", "18", "", "")

    private class MemoryStore : FinalProductCatalog.Store {
        val notes = linkedMapOf<String, SettingsEntity>()
        val cats = mutableListOf<CategoryEntity>()
        val kinds = mutableListOf<ProductTypeEntity>()
        val items = mutableListOf<ProductEntity>()
        override suspend fun settings() = notes.values.toList()
        override suspend fun put(setting: SettingsEntity) { notes[setting.key] = setting }
        override suspend fun remove(key: String) { notes.remove(key) }
        override suspend fun categories() = cats.toList()
        override suspend fun types() = kinds.toList()
        override suspend fun products() = items.toList()
        override suspend fun addCategory(value: CategoryEntity): Long = (cats.size + 1L).also { cats += value.copy(id = it) }
        override suspend fun addType(value: ProductTypeEntity): Long = (kinds.size + 1L).also { kinds += value.copy(id = it) }
        override suspend fun addProduct(value: ProductEntity): Long = (items.size + 1L).also { items += value.copy(id = it) }
        override suspend fun updateProduct(value: ProductEntity) { items[items.indexOfFirst { it.id == value.id }] = value }
    }

    @Test fun pricedRowsPublishAndUnpricedRowsStayPending() = runBlocking {
        val store = MemoryStore()
        FinalProductCatalog.merge(store, listOf(row(), row("two", "Missing price", "")), 10)
        assertEquals(1, store.items.size)
        val p = store.items.single()
        assertEquals(13800L, p.sellingPricePaisa)
        assertEquals(1800, p.gstRateBp)
        assertEquals(0.0, p.currentStock, 0.0)
        val drafts = store.notes.filterKeys { it.startsWith("product:draft:") }
        assertEquals(1, drafts.size)
        val draft = gson.fromJson(drafts.values.single().value, ProductDraft::class.java)
        assertEquals("18", draft.form.gstPercent)
        assertEquals("", draft.form.sellingPrice)
        assertTrue(ProductForm(categoryId = 1, typeId = 1, name = p.name, sellingPrice = "138")
            .gaps(requireSize = false).isEmpty())
    }

    @Test fun restartDoesNotDuplicateOrResetOwnerEdits() = runBlocking {
        val store = MemoryStore()
        FinalProductCatalog.merge(store, listOf(row()), 10)
        store.items[0] = store.items[0].copy(sellingPricePaisa = 19900, currentStock = 7.0, gstRateBp = 1200)
        val edited = store.items.single()
        FinalProductCatalog.merge(store, listOf(row()), 20)
        assertEquals(listOf(edited), store.items)
    }

    @Test fun existingProductKeepsStockAndPriceButReceivesApprovedGst() = runBlocking {
        val store = MemoryStore()
        store.cats += CategoryEntity(id = 1, name = "Hardware")
        store.kinds += ProductTypeEntity(id = 1, categoryId = 1, name = "Brushes")
        store.items += ProductEntity(id = 1, categoryId = 1, typeId = 1, name = "Cup brush", unit = "Piece",
            sellingPricePaisa = 14500, currentStock = 9.0, purchasePricePaisa = 10000, gstRateBp = 0)
        FinalProductCatalog.merge(store, listOf(row()), 10)
        assertEquals(1, store.items.size)
        assertEquals(14500L, store.items.single().sellingPricePaisa)
        assertEquals(9.0, store.items.single().currentStock, 0.0)
        assertEquals(10000L, store.items.single().purchasePricePaisa)
        assertEquals(1800, store.items.single().gstRateBp)
    }

    @Test fun groutCorrectionIsAppliedOnce() = runBlocking {
        val store = MemoryStore()
        val r = row(name = "Grout – Ivory", price = "130")
        store.cats += CategoryEntity(id = 1, name = r.category)
        store.kinds += ProductTypeEntity(id = 1, categoryId = 1, name = r.type)
        store.items += ProductEntity(id = 1, categoryId = 1, typeId = 1, name = r.name, unit = r.unit,
            sellingPricePaisa = 4715)
        FinalProductCatalog.merge(store, listOf(r), 10)
        assertEquals(13000L, store.items.single().sellingPricePaisa)
        store.items[0] = store.items[0].copy(sellingPricePaisa = 14000)
        FinalProductCatalog.merge(store, listOf(r), 20)
        assertEquals(14000L, store.items.single().sellingPricePaisa)
    }

    @Test fun disabledProductsAreNotReactivated() = runBlocking {
        val store = MemoryStore()
        store.cats += CategoryEntity(id = 1, name = "Hardware")
        store.kinds += ProductTypeEntity(id = 1, categoryId = 1, name = "Brushes")
        store.items += ProductEntity(id = 1, categoryId = 1, typeId = 1, name = "Cup brush", unit = "Piece",
            sellingPricePaisa = 14500, isActive = false)
        FinalProductCatalog.merge(store, listOf(row()), 10)
        assertEquals(1, store.items.size)
        assertFalse(store.items.single().isActive)
    }

    @Test fun untouchedLegacyDraftIsArchivedAndPublished() = runBlocking {
        val store = MemoryStore()
        val old = ShopItemCatalog.items().first { !it.selling.isNullOrBlank() }
        store.cats += CategoryEntity(id = 1, name = old.group.category)
        store.kinds += ProductTypeEntity(id = 1, categoryId = 1, name = old.group.type)
        val form = ProductForm(categoryId = 1, typeId = 1, name = old.displayName,
            size = old.size.orEmpty(), unit = old.unit.orEmpty(), sellingPrice = old.selling.orEmpty(),
            purchasePrice = old.buying, gstPercent = "", stock = old.stock, minimumStock = "", aliases = old.searchTerms)
        val draft = ProductDraft(old.id, form = form)
        store.put(SettingsEntity("product:catalog-data:${old.id}", gson.toJson(old)))
        store.put(SettingsEntity("product:draft:${old.id}", gson.toJson(draft)))
        val r = row().copy(category = old.group.category, type = old.group.type, name = old.displayName,
            size = old.size.orEmpty(), unit = old.unit.orEmpty())
        FinalProductCatalog.merge(store, listOf(r), 10)
        assertEquals(1, store.items.size)
        assertFalse(store.notes.containsKey("product:draft:${old.id}"))
        assertEquals(gson.toJson(draft), store.notes["${FinalProductCatalog.VERSION}:archive:${old.id}"]?.value)
    }

    @Test fun editedLegacyDraftIsPreservedWithoutDuplicateProduct() = runBlocking {
        val store = MemoryStore()
        val old = ShopItemCatalog.items().first { !it.selling.isNullOrBlank() }
        val draft = ProductDraft(old.id, form = ProductForm(name = old.displayName,
            size = old.size.orEmpty(), unit = old.unit.orEmpty(), sellingPrice = "999"))
        store.put(SettingsEntity("product:catalog-data:${old.id}", gson.toJson(old)))
        store.put(SettingsEntity("product:draft:${old.id}", gson.toJson(draft)))
        val r = row().copy(name = old.displayName, size = old.size.orEmpty(), unit = old.unit.orEmpty())
        FinalProductCatalog.merge(store, listOf(r), 10)
        assertTrue(store.items.isEmpty())
        assertEquals(gson.toJson(draft), store.notes["product:draft:${old.id}"]?.value)
    }

    @Test fun entireApprovedAssetHas1522ProductsAnd240Drafts() = runBlocking {
        val file = listOf(File("src/main/assets/final_catalog.json"), File("app/src/main/assets/final_catalog.json"))
            .first { it.exists() }
        val rows = gson.fromJson(file.readText(), Array<FinalProductCatalog.Row>::class.java).toList()
        val store = MemoryStore()
        FinalProductCatalog.merge(store, rows, 10)
        assertEquals(1522, store.items.size)
        assertEquals(240, store.notes.keys.count { it.startsWith("product:draft:") })
        assertTrue(store.items.all { it.gstRateBp == 1800 && it.sellingPricePaisa > 0 })
        assertTrue(store.items.filter { it.name in setOf("Grout – Ivory", "Grout – Coffee Brown") }
            .also { assertEquals(2, it.size) }.all { it.sellingPricePaisa == 13000L })
        FinalProductCatalog.merge(store, rows, 20)
        assertEquals(1522, store.items.size)
        assertEquals(240, store.notes.keys.count { it.startsWith("product:draft:") })
    }

    @Test fun invalidPriceFailsBeforeAnyWrites() = runBlocking {
        val store = MemoryStore()
        try {
            FinalProductCatalog.merge(store, listOf(row(price = "-1")), 10)
            fail("Invalid price must fail")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.items.isEmpty())
        assertTrue(store.notes.isEmpty())
    }

    private fun asset(name: String): List<FinalProductCatalog.Row> {
        val file = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.exists() }
        return gson.fromJson(file.readText(), Array<FinalProductCatalog.Row>::class.java).toList()
    }

    @Test fun allFiveSheetsMergeInto1698ProductsAnd237UnpricedDrafts() = runBlocking {
        val store = MemoryStore()
        val master = asset("final_catalog.json")
        val supplement = asset("store_catalog.json")
        assertEquals(206, supplement.size)
        assertEquals(173, supplement.count { it.id.startsWith("final-store-") })
        FinalProductCatalog.mergeBoth(store, master, supplement, 10)
        assertEquals(1698, store.items.size)
        assertEquals(237, store.notes.keys.count { it.startsWith("product:draft:") })
        assertTrue(store.items.all { it.gstRateBp == 1800 && it.sellingPricePaisa > 0 })
        assertEquals(3, store.notes.keys.count { it.startsWith("${FinalProductCatalog.STORE_VERSION}:archive:") })
        FinalProductCatalog.mergeBoth(store, master, supplement, 20)
        assertEquals(1698, store.items.size)
        assertEquals(237, store.notes.keys.count { it.startsWith("product:draft:") })
    }

    @Test fun previousApkUpgradesWithoutResettingMasterPricesOrStock() = runBlocking {
        val store = MemoryStore()
        val master = asset("final_catalog.json")
        val supplement = asset("store_catalog.json")
        FinalProductCatalog.merge(store, master, 10)
        val rainIndex = store.items.indexOfFirst { it.name == "Raincoat" }
        store.items[rainIndex] = store.items[rainIndex].copy(sellingPricePaisa = 29900, currentStock = 7.0)
        val edited = store.items[rainIndex]
        FinalProductCatalog.mergeBoth(store, master, supplement, 20)
        assertEquals(1698, store.items.size)
        assertEquals(edited, store.items[rainIndex])
        assertEquals(237, store.notes.keys.count { it.startsWith("product:draft:") })
        assertEquals(25000L, store.items.single { it.name == "CPVC Ball Valve" && it.size == "3/4 inch" }.sellingPricePaisa)
    }

    @Test fun masterPricesWinAndDifferentSellingUnitsStaySeparate() = runBlocking {
        val store = MemoryStore()
        FinalProductCatalog.mergeBoth(store, asset("final_catalog.json"), asset("store_catalog.json"), 10)
        assertEquals(28500L, store.items.single { it.name == "Raincoat" }.sellingPricePaisa)
        assertEquals(47000L, store.items.single { it.name == "UPVC Ball Valve" && it.size == "1.5 inch" }.sellingPricePaisa)
        val pipe = store.items.filter { it.name == "Garden Pipe" && it.size == "½\"" }
        assertEquals(2, pipe.size)
        assertEquals(165000L, pipe.single { it.unit == "Roll" }.sellingPricePaisa)
        assertEquals(5500L, pipe.single { it.unit == "Meter" }.sellingPricePaisa)
        assertEquals(5, store.items.count { it.name == "C-Clip" && it.unit == "Pack" })
        assertEquals(234900L, store.items.single { it.name == "Candes Ceiling Fan" }.sellingPricePaisa)
    }

    @Test fun supplementalPriceDoesNotOverwriteEditedOrDiscardedPendingDraft() = runBlocking {
        val master = row(price = "")
        val supplement = master.copy(selling = "75")
        val store = MemoryStore()
        FinalProductCatalog.merge(store, listOf(master), 10)
        val draftKey = "product:draft:${master.id}"
        val original = gson.fromJson(store.notes.getValue(draftKey).value, ProductDraft::class.java)
        val edited = original.copy(form = original.form.copy(stock = "9"))
        store.put(SettingsEntity(draftKey, gson.toJson(edited)))
        FinalProductCatalog.mergeBoth(store, listOf(master), listOf(supplement), 20)
        assertTrue(store.items.isEmpty())
        assertEquals(gson.toJson(edited), store.notes.getValue(draftKey).value)

        val discarded = MemoryStore()
        FinalProductCatalog.merge(discarded, listOf(master), 10)
        discarded.remove(draftKey)
        discarded.put(SettingsEntity("product:done:${master.id}", "discarded"))
        FinalProductCatalog.mergeBoth(discarded, listOf(master), listOf(supplement), 20)
        assertTrue(discarded.items.isEmpty())
        assertFalse(discarded.notes.containsKey(draftKey))
    }

    @Test fun invalidSupplementFailsBeforeMasterIsWritten() = runBlocking {
        val store = MemoryStore()
        try {
            FinalProductCatalog.mergeBoth(store, listOf(row()), listOf(row("bad", price = "-5")), 10)
            fail("Invalid supplement must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.items.isEmpty())
        assertTrue(store.notes.isEmpty())
    }
}
