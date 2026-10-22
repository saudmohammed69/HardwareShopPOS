package com.hardwareshop.pos

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.entity.*
import java.util.Locale

/** Idempotent upgrade: preserves stock, costs, custom selling rates and historical IDs. */
object PipeProductCatalog {
    const val VERSION = "catalog:pipe-dual-rate:v2"
    data class Variant(val name: String, val size: String, val length: Long, val foot: Long)
    val variants = buildList {
        fun group(name: String, sizes: List<String>, lengths: List<Long>, feet: List<Long>) {
            sizes.indices.forEach { add(Variant(name, sizes[it] + " inch", lengths[it]*100, feet[it]*100)) }
        }
        group("PVC Pipe", listOf("1/2","3/4","1","1.25","1.5","2","2.5","3","4","6"), listOf(180,280,400,500,700,800,1000,1300,1650,2180), listOf(20,25,35,35,50,60,70,80,100,150))
        group("PVC Lite Pipe", listOf("1.5","2","2.5","3","4"), listOf(400,470,700,850,1250), listOf(35,40,45,55,80))
        group("UPVC Pipe", listOf("1/2","3/4","1","1.25","1.5","2"), listOf(480,650,720,880,1050,1350), listOf(30,40,50,55,70,80))
        group("CPVC Pipe", listOf("1/2","3/4","1"), listOf(420,620,850), listOf(40,45,60))
        group("Blue Pipe", listOf("1/2","3/4","1"), listOf(350,530,660), listOf(28,35,40))
    }
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).trim()
        .replace(" (length/grade unspecified)", "").replace("pvc pipe lite", "pvc lite pipe")
        .replace("½", "1/2").replace("¾", "3/4").replace("1¼", "1.25").replace("1½", "1.5").replace("2½", "2.5")
        .replace("1 1/4", "1.25").replace("1 1/2", "1.5").replace("2 1/2", "2.5")
        .replace("\"", " inch").replace("”", " inch").replace(Regex("\\s+"), " ")
    fun matches(v: Variant, p: ProductEntity) = normalized(p.name) == normalized(v.name) && normalized(p.size) == normalized(v.size)
    internal suspend fun merge(store: FinalProductCatalog.Store): String {
        if (store.settings().any { it.key == VERSION }) {
            archiveSingleRateDuplicates(store)
            return store.settings().first { it.key == VERSION }.value
        }
        val products = store.products()
        val categories = store.categories().toMutableList(); val types = store.types().toMutableList()
        val report = mutableListOf<String>()
        for (v in variants) {
            val matches = products.filter { matches(v,it) }
            val candidates = matches.filter { it.unit.lowercase() in setOf("length","piece","nos","foot","ft","feet") }
                .sortedWith(compareBy<ProductEntity> { if(it.isPipe) 0 else if(it.unit.equals("Length",true)) 1 else if(it.unit.equals("Foot",true)) 3 else 2 }.thenBy { it.id })
            if (matches.isNotEmpty() && candidates.isEmpty()) { report += "${v.name} ${v.size}: unsupported inventory unit; left unchanged"; continue }
            val old = candidates.firstOrNull()
            val lengthRate = matches.firstOrNull { it.unit.lowercase() in setOf("length","piece","nos") && it.sellingPricePaisa > 0 }?.sellingPricePaisa ?: v.length
            val footRate = matches.firstOrNull { it.unit.lowercase() in setOf("foot","feet","ft") && it.sellingPricePaisa > 0 }?.sellingPricePaisa ?: v.foot
            val lp = if(old?.isPipe == true) old.pipeLengthPricePaisa else lengthRate
            val fp = if(old?.isPipe == true) old.pipeFootPricePaisa else footRate
            if (lp != v.length || fp != v.foot) report += "${v.name} ${v.size}: kept length ${lp/100.0}, foot ${fp/100.0}; supplied ${v.length/100.0}, ${v.foot/100.0}"
            if (old != null) {
                store.updateProduct(old.copy(isPipe = true, pipeLengthPricePaisa = lp, pipeFootPricePaisa = fp, pipeFeetPerLength = 20))
                // Keep legacy rows and historical references, but use one product in the billing picker.
                matches.filter { it.id != old.id }.forEach {
                    store.put(SettingsEntity("pipe:billing-alias:${it.id}", old.id.toString()))
                }
                report += "${v.name} ${v.size}: reused product ${old.id}; ${matches.size-1} legacy records left unchanged"
            } else {
                val categoryName = when(v.name) { "Blue Pipe" -> "Plumbing Accessories"; "PVC Lite Pipe" -> "PVC"; else -> v.name.substringBefore(" ") }
                val category = categories.firstOrNull { it.name.equals(categoryName,true) } ?: CategoryEntity(name=categoryName).let { it.copy(id=store.addCategory(it)).also(categories::add) }
                val typeName = if(v.name=="Blue Pipe") "Blue Pipes" else "Pipe"
                val type = types.firstOrNull { it.categoryId==category.id && it.name.equals(typeName,true) } ?: ProductTypeEntity(categoryId=category.id,name=typeName).let { it.copy(id=store.addType(it)).also(types::add) }
                val id = store.addProduct(ProductEntity(categoryId=category.id,typeId=type.id,name=v.name,size=v.size,unit="Length",sellingPricePaisa=v.length,
                    isPipe=true,pipeLengthPricePaisa=v.length,pipeFootPricePaisa=v.foot,pipeFeetPerLength=20))
                report += "${v.name} ${v.size}: added product $id; cost and stock unconfigured"
            }
        }
        val result=report.joinToString("\n").also { store.put(SettingsEntity(VERSION,it)) }
        archiveSingleRateDuplicates(store)
        return result
    }

    /** Same soft deletion as owner Delete: preserve historical IDs, rates and stock. */
    internal suspend fun archiveSingleRateDuplicates(store: FinalProductCatalog.Store) {
        val products=store.products()
        // Build one index rather than normalizing the full catalogue 54 times on each launch.
        val names=variants.map { normalized(it.name) }.toSet()
        val grouped=products.asSequence().filter { it.isActive }.map { normalized(it.name) to it }
            .filter { (name,_) -> name in names }
            .groupBy({ (name,p) -> name to normalized(p.size) },{ (_,p) -> p })
        val archived=mutableListOf<String>()
        for (variant in variants) {
            val matching=grouped[normalized(variant.name) to normalized(variant.size)].orEmpty()
            val canonical=matching.filter { it.isPipe &&
                it.pipeLengthPricePaisa>0 && it.pipeFootPricePaisa>0 && it.pipeFeetPerLength==20 }
                .minByOrNull { it.id } ?: continue
            matching.filter { !it.isPipe && it.categoryId==canonical.categoryId &&
                it.unit.trim().lowercase(Locale.ROOT) in setOf("length","piece","nos","foot","ft","feet") }
                .forEach { legacy ->
                    store.put(SettingsEntity("pipe:billing-alias:${legacy.id}",canonical.id.toString()))
                    store.updateProduct(legacy.copy(isActive=false,updatedAt=System.currentTimeMillis()))
                    archived += "${legacy.id} -> ${canonical.id}: ${legacy.name} ${legacy.size} (${legacy.unit}); recorded stock ${legacy.currentStock} retained"
                }
        }
        if(archived.isNotEmpty()) {
            val key="catalog:pipe-dual-rate:cleanup-v3"
            val previous=store.settings().firstOrNull { it.key==key }?.value.orEmpty()
            store.put(SettingsEntity(key,listOf(previous,archived.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n")))
        }
    }
}
