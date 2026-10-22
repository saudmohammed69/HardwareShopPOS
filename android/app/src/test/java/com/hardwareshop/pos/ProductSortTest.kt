package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.ProductSizeGroups
import com.hardwareshop.pos.util.ProductSort
import com.hardwareshop.pos.util.ProductSortOrder
import org.junit.Assert.*
import org.junit.Test

class ProductSortTest {
    private fun product(id: Long, name: String, price: Long = 100L, stock: Double = 10.0, size: String = "") =
        Product(id, 1, "Hardware", 1, "Items", name, size, "Piece", price, 0, 1800,
            stock, 0.0, null, isActive = true)

    @Test fun defaultPreservesRepositoryRelevanceEvenWhenNamePriceAndStockWouldDiffer() {
        val exact = product(30, "Zinc Screw", 900, 80.0)
        val next = product(10, "Bolt", 100, 1.0)
        val shown = listOf(exact, next)
        assertEquals(shown, ProductSort.products(shown, ProductSortOrder.DEFAULT))
        assertEquals(listOf(30L, 10L), ProductSort.groups(shown).map { it.products.single().id })
    }

    @Test fun sortingDoesNotMutateInputAndReturningToDefaultRestoresItsOrder() {
        val shown = mutableListOf(product(1, "Zinc", 300), product(2, "Bolt", 100), product(3, "Clamp", 200))
        val original = shown.toList()
        val sorted = ProductSort.products(shown, ProductSortOrder.PRICE_ASC)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
        assertEquals(original, shown)
        assertEquals(original, ProductSort.products(shown, ProductSortOrder.DEFAULT))
        assertEquals(original.toSet(), sorted.toSet())
    }

    @Test fun nameSortingIsCaseInsensitiveAndKeepsTiesInTheirOriginalOrder() {
        val shown = listOf(product(5, "Zinc"), product(9, "bolt"), product(2, "Bolt"), product(3, "Clamp"))
        assertEquals(listOf(9L, 2L, 3L, 5L), ProductSort.products(shown, ProductSortOrder.NAME_ASC).map { it.id })
    }

    @Test fun equalPriceAndStockKeepSearchOrFavouriteOrderInBothDirections() {
        val shown = listOf(product(9, "Zinc"), product(2, "Bolt"), product(6, "Clamp"))
        listOf(ProductSortOrder.PRICE_ASC, ProductSortOrder.PRICE_DESC, ProductSortOrder.STOCK_ASC).forEach {
            assertEquals(shown, ProductSort.products(shown, it))
            assertEquals(listOf(9L, 2L, 6L), ProductSort.groups(shown, order = it).map { group -> group.products.single().id })
        }
    }

    @Test fun pipePriceUsesLengthRateAndNeverFootRateOrStockSellingRate() {
        val pipe = product(1, "PVC Pipe", 1).copy(isPipe = true, unit = "Foot", pipeFeetPerLength = 20,
            pipeLengthPricePaisa = 80000, pipeFootPricePaisa = 5000)
        val fitting = product(2, "Elbow", 9000)
        assertEquals(80000L, ProductSort.displayPrice(pipe))
        assertEquals(listOf(2L, 1L), ProductSort.products(listOf(pipe, fitting), ProductSortOrder.PRICE_ASC).map { it.id })
        assertEquals(listOf(1L, 2L), ProductSort.products(listOf(fitting, pipe), ProductSortOrder.PRICE_DESC).map { it.id })
        assertEquals(pipe, ProductSort.products(listOf(pipe), ProductSortOrder.PRICE_ASC).single())
    }

    @Test fun priceComparisonDoesNotOverflowAtLargeValues() {
        val shown = listOf(product(1, "High", Long.MAX_VALUE), product(2, "Low", 1), product(3, "Middle", Long.MAX_VALUE / 2))
        assertEquals(listOf(2L, 3L, 1L), ProductSort.products(shown, ProductSortOrder.PRICE_ASC).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), ProductSort.products(shown, ProductSortOrder.PRICE_DESC).map { it.id })
    }

    @Test fun familyPriceUsesLowestRateAscendingAndHighestRateDescending() {
        val large = product(1, "Paint", 900, size = "1 L")
        val small = product(2, "Paint", 100, size = "100 ml")
        val brush = product(3, "Brush", 400)
        val shown = listOf(brush, large)
        val catalogue = listOf(large, small, brush)
        assertEquals(listOf("Paint", "Brush"), ProductSort.groups(shown, catalogue, ProductSortOrder.PRICE_ASC).map { it.name })
        assertEquals(listOf("Paint", "Brush"), ProductSort.groups(shown, catalogue, ProductSortOrder.PRICE_DESC).map { it.name })
        assertEquals(listOf("Brush", "Paint"), ProductSort.groups(shown, catalogue).map { it.name })
    }

    @Test fun sortingTilesLeavesVariantChoicesAndExactSizeFirstChoiceUntouched() {
        val large = product(1, "Paint", 100, size = "1 L")
        val small = product(2, "Paint", 900, size = "100 ml")
        val brush = product(3, "Brush", 400)
        val catalogue = listOf(large, small, brush)
        val originalChoices = ProductSizeGroups.choices(catalogue, large)
        val expectedExactFirst = listOf(large, small)
        ProductSortOrder.entries.forEach { order ->
            val family = ProductSort.groups(listOf(large, brush), catalogue, order).first { it.name == "Paint" }
            assertEquals(originalChoices, family.products)
            assertEquals(originalChoices, ProductSizeGroups.choices(catalogue, large))
        }
        // Caller relevance remains intact; sorting family tiles never changes the source list.
        ProductSort.groups(expectedExactFirst, catalogue, ProductSortOrder.PRICE_DESC)
        assertEquals(listOf(1L, 2L), expectedExactFirst.map { it.id })
    }

    @Test fun stockUsesEachProductsRecordedUnitAndFamilyLowestVariant() {
        val roll = product(1, "Wire", stock = 2.0).copy(unit = "Roll")
        val piece = product(2, "Bolt", stock = 4.0)
        val large = product(3, "Paint", stock = 50.0, size = "1 L")
        val small = product(4, "Paint", stock = 0.0, size = "100 ml")
        val shown = listOf(piece, large, roll)
        assertEquals(listOf("Paint", "Wire", "Bolt"),
            ProductSort.groups(shown, shown + small, ProductSortOrder.STOCK_ASC).map { it.name })
        assertEquals(listOf(1L, 2L), ProductSort.products(listOf(piece, roll), ProductSortOrder.STOCK_ASC).map { it.id })
        assertEquals("Roll", roll.unit)
        assertEquals(2.0, roll.currentStock, 0.0)
    }

    @Test fun nonFiniteStockIsPlacedAfterRecordedQuantities() {
        val shown = listOf(product(1, "Unknown", stock = Double.NaN), product(2, "Zero", stock = 0.0))
        assertEquals(listOf(2L, 1L), ProductSort.products(shown, ProductSortOrder.STOCK_ASC).map { it.id })
    }
}
