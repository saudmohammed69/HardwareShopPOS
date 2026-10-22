package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Exact diameter matching across pipe materials; it never changes prices or cart quantities. */
object PipeSizeChoices {
    fun key(size: String): String? {
        val text = size.trim().lowercase(Locale.ROOT)
            .replace("½", " 1/2").replace("¼", " 1/4").replace("¾", " 3/4")
            .replace(Regex("\\s+"), " ").trim()
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?(?:\\s+[0-9]+\\s*/\\s*[0-9]+)?|[0-9]+\\s*/\\s*[0-9]+)\\s*(inch(?:es)?|in|[\"″”])?$").matchEntire(text)
            ?: return null
        val quantity = match.groupValues[1].trim()
        return runCatching {
            val value = if ('/' !in quantity) BigDecimal(quantity) else {
                val parts = quantity.replace(Regex("\\s*/\\s*"), "/").split(' ')
                val fraction = parts.last().split('/')
                BigDecimal(fraction[0]).divide(BigDecimal(fraction[1]), 8, RoundingMode.UNNECESSARY)
                    .add(if (parts.size > 1) BigDecimal(parts.first()) else BigDecimal.ZERO)
            }
            value.takeIf { it > BigDecimal.ZERO }?.stripTrailingZeros()?.toPlainString()
        }.getOrNull()
    }

    fun matching(products: List<Product>, selected: Product?): List<Product> {
        if (selected?.isPipe != true) return emptyList()
        val diameter = key(selected.size) ?: return emptyList()
        return products.filter { it.isPipe && it.isActive && key(it.size) == diameter }
            .distinctBy { it.id }
            .sortedWith(compareBy<Product> { it.categoryName.lowercase(Locale.ROOT) }
                .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }
}
