package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product
import java.util.Locale

enum class ProductSortOrder(val label: String) {
    DEFAULT("Default"),
    NAME_ASC("Name A–Z"),
    PRICE_ASC("Price low → high"),
    PRICE_DESC("Price high → low"),
    STOCK_ASC("Lowest stock")
}

/** Optional display ordering. Default keeps repository relevance and favourite order. */
object ProductSort {
    fun displayPrice(product: Product): Long =
        if (product.isPipe) product.pipeLengthPricePaisa else product.sellingPricePaisa

    fun products(products: List<Product>, order: ProductSortOrder): List<Product> {
        if (order == ProductSortOrder.DEFAULT) return products.toList()
        return products.withIndex().sortedWith { a, b ->
            val result = when (order) {
                ProductSortOrder.NAME_ASC -> name(a.value.displayLabel).compareTo(name(b.value.displayLabel))
                ProductSortOrder.PRICE_ASC -> displayPrice(a.value).compareTo(displayPrice(b.value))
                ProductSortOrder.PRICE_DESC -> displayPrice(b.value).compareTo(displayPrice(a.value))
                ProductSortOrder.STOCK_ASC -> stock(a.value).compareTo(stock(b.value))
                ProductSortOrder.DEFAULT -> 0
            }
            if (result == 0) a.index.compareTo(b.index) else result
        }.map { it.value }
    }

    /** Reorder family tiles without changing size-choice order or the source catalogue. */
    fun groups(
        shown: List<Product>,
        catalogue: List<Product> = shown,
        order: ProductSortOrder = ProductSortOrder.DEFAULT,
        index: ProductSizeGroups.Index? = null
    ): List<ProductSizeGroups.Group> {
        val groups = index?.groups(shown) ?: ProductSizeGroups.groups(shown, catalogue)
        if (order == ProductSortOrder.DEFAULT) return groups
        return groups.withIndex().sortedWith { a, b ->
            val result = when (order) {
                ProductSortOrder.NAME_ASC -> name(a.value.name).compareTo(name(b.value.name))
                ProductSortOrder.PRICE_ASC -> lowPrice(a.value).compareTo(lowPrice(b.value))
                ProductSortOrder.PRICE_DESC -> highPrice(b.value).compareTo(highPrice(a.value))
                ProductSortOrder.STOCK_ASC -> lowStock(a.value).compareTo(lowStock(b.value))
                ProductSortOrder.DEFAULT -> 0
            }
            if (result == 0) a.index.compareTo(b.index) else result
        }.map { it.value }
    }

    private fun name(value: String) = value.trim().lowercase(Locale.ROOT)
    private fun stock(product: Product) = product.currentStock.takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
    private fun lowPrice(group: ProductSizeGroups.Group) = group.products.minOf(::displayPrice)
    private fun highPrice(group: ProductSizeGroups.Group) = group.products.maxOf(::displayPrice)
    private fun lowStock(group: ProductSizeGroups.Group) = group.products.minOf(::stock)
}
