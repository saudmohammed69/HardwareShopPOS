package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.BillingProductAliases
import com.hardwareshop.pos.util.ProductOrdering
import org.junit.Assert.*
import org.junit.Test

class BillingProductAliasesTest {
    private fun product(id: Long, pipe: Boolean = true) = Product(id, 1, "PVC", 1, "Pipe",
        "PVC Pipe", "1 inch", "Length", 40000, 0, 0, 9.0, 0.0, null,
        isActive = true, isPipe = pipe)

    @Test fun aliasesUseTheAlreadyLoadedTargetAndPreserveListOrder() {
        val ordinary = product(4, false)
        val canonical = product(1)
        val legacy = product(2)
        assertEquals(listOf(ordinary, canonical), BillingProductAliases.resolve(
            listOf(ordinary, legacy, canonical), mapOf(2L to 1L), listOf(ordinary, legacy, canonical)))
    }
    @Test fun oneBulkTargetSnapshotResolvesMultipleLegacyRowsOnce() {
        val target = product(1)
        assertEquals(listOf(target), BillingProductAliases.resolve(listOf(product(2), product(3)),
            mapOf(2L to 1L, 3L to 1L), listOf(target)))
    }
    @Test fun unavailableTargetsCannotBecomeSellableAliases() {
        val legacy = product(2)
        assertTrue(BillingProductAliases.resolve(listOf(legacy), mapOf(2L to 1L), emptyList()).isEmpty())
        assertTrue(BillingProductAliases.resolve(listOf(legacy), mapOf(2L to 1L),
            listOf(product(1).copy(isActive = false))).isEmpty())
    }
    @Test fun largeCatalogueKeepsNumericOrderingAndAllOriginalRecords() {
        val input = (1L..3000L).reversed().map {
            product(it, false).copy(name = "Product $it", size = "")
        }
        val ordered = ProductOrdering.sorted(input)
        assertEquals((1L..3000L).toList(), ordered.map { it.id })
        assertEquals(input.associateBy { it.id }, ordered.associateBy { it.id })
    }
}
