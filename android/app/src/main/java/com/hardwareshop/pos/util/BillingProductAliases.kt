package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product

/** Resolve an already-fetched catalogue in memory; no per-tile database reads. */
object BillingProductAliases {
    fun resolve(products: List<Product>, aliases: Map<Long, Long>, targets: List<Product>): List<Product> {
        val byId = targets.associateBy { it.id }
        return products.mapNotNull { product ->
            val target = aliases[product.id]
            if (target == null) product
            else byId[target]?.takeIf { it.isActive && it.isPipe }
        }.distinctBy { it.id }
    }
}
