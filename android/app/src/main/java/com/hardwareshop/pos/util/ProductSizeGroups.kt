package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product
import java.util.Locale

/** Display-only families. Every size choice remains its original inventory product. */
object ProductSizeGroups {
    data class Group(val key: String, val name: String, val products: List<Product>, val sizeFirst: Boolean)
    private val dashes = Regex("[–—]")
    private val whitespace = Regex("\\s+")
    private fun normalized(value: String) = value.lowercase(Locale.ROOT)
        .replace(dashes, "-").replace(whitespace, " ").trim()
    private val materials = setOf("pvc", "cpvc", "upvc")
    private val volume = Regex("\\d+(?:\\.\\d+)?\\s*(?:ml|l|litres?|liters?)(?![a-z])", RegexOption.IGNORE_CASE)
    private val material = Regex("\\b(?:pvc|cpvc|upvc|hdpe|ppr)\\b", RegexOption.IGNORE_CASE)
    private val pipeWord = Regex("\\bpipes?\\b", RegexOption.IGNORE_CASE)
    private val paintWord = Regex("\\bpaints?\\b", RegexOption.IGNORE_CASE)
    fun isPaint(product: Product): Boolean = normalized(product.categoryName) in
        setOf("paints & coatings", "paints", "paint", "primers & wood finishes", "wood care") ||
        (volume.containsMatchIn(product.size) && (normalized(product.categoryName) == "painting tools & solvents" ||
            paintWord.containsMatchIn(product.name)))
    fun sizeFirst(product: Product): Boolean = product.size.isNotBlank() || ProductVariantMetadata.read(product)!=null
    fun hasChoices(catalogue: List<Product>, product: Product): Boolean =
        choices(catalogue,product).map { normalized(ProductVariantMetadata.label(it)) }.distinct().size>1

    fun familyName(product: Product): String {
        // Some owner-created names repeat the explicit size at the end. Remove only
        // that exact suffix from the display family, never a brand, colour or grade.
        val name=product.name.trim()
        val size=product.size.trim()
        return if (size.isNotBlank() && name.endsWith(" $size", ignoreCase=true))
            name.dropLast(size.length).trimEnd(' ', '-', '–', '—').ifBlank { name } else name
    }
    fun key(product: Product): String = if (!sizeFirst(product)) "product:${product.id}" else
        listOf(product.categoryId, product.typeId, normalized(ProductVariantMetadata.parent(product)),
            if (product.isPipe) "length+foot" else normalized(product.unit), product.isPipe,
            if (product.isPipe) product.pipeFeetPerLength else 0).joinToString("|")

    fun choices(catalogue: List<Product>, product: Product): List<Product> =
        ProductOrdering.sorted(catalogue.filter { it.isActive && key(it)==key(product) }.distinctBy { it.id })

    /** Preserve source relevance/favourite order while expanding each family to all saved sizes. */
    fun groups(shown: List<Product>, catalogue: List<Product> = shown): List<Group> =
        if (shown.isEmpty()) emptyList() else Index(catalogue).groups(shown)

    /** Immutable per-catalogue display index. Build off the UI thread, reuse on each keystroke. */
    class Index(catalogue: List<Product>) {
        private val keys = HashMap<Long,String>()
        private val families = HashMap<String,List<Product>>()
        private val displays = HashMap<Long,Group>()
        init {
            val active=catalogue.filter { it.isActive }.distinctBy { it.id }
            active.forEach { keys[it.id]=key(it) }
            active.groupBy { keys.getValue(it.id) }.forEach { (family,members) ->
                val sorted=ProductOrdering.sorted(members)
                families[family]=sorted
                val multiple=sorted.map { normalized(ProductVariantMetadata.label(it)) }.distinct().size>1
                sorted.forEach { product -> displays[product.id]=Group(
                    if(multiple) family else "product:${product.id}", ProductVariantMetadata.parent(product),
                    if(multiple) sorted else listOf(product),multiple) }
            }
        }
        fun choices(product: Product): List<Product> = families[keys[product.id] ?: key(product)].orEmpty()
        fun hasChoices(product: Product): Boolean = displays[product.id]?.sizeFirst ?: false
        fun groups(shown: List<Product>): List<Group> {
            val seen=HashSet<String>()
            return shown.asSequence().filter { it.isActive }.map { product -> displays[product.id]
                ?: Group("product:${product.id}",ProductVariantMetadata.parent(product),listOf(product),false) }
                .filter { seen.add(it.key) }.toList()
        }
    }

    fun quantityLabel(product: Product): String = if (isPaint(product) && volume.containsMatchIn(product.size) &&
        normalized(product.unit) in setOf("piece", "pieces", "nos")) "Number of containers (${product.size})"
        else "Quantity (${product.unit})"
}
