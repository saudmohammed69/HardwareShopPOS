package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.presentation.worker.product.ProductSelectionUiState
import com.hardwareshop.pos.util.PipeSizeChoices
import org.junit.Assert.*
import org.junit.Test

class PipeSizeChoicesTest {
    private fun pipe(id: Long, size: String, category: String = "PVC") = Product(
        id, 1, category, 1, "Pipe", "$category Pipe", size, "Length", 40000, 0, 0,
        10.0, 0.0, null, isActive = true, isPipe = true,
        pipeLengthPricePaisa = 40000, pipeFootPricePaisa = 3500, pipeFeetPerLength = 20)

    @Test fun oneInchMatchesAcrossMaterialsWithoutOtherSizes() {
        val selected = pipe(1, "1 inch")
        val choices = listOf(selected, pipe(2, "1\"", "UPVC"), pipe(3, "1/2 inch"),
            pipe(4, "1.25 inch"), pipe(5, "1 inch", "CPVC"))
        assertEquals(setOf(1L, 2L, 5L), PipeSizeChoices.matching(choices, selected).map { it.id }.toSet())
    }
    @Test fun halfInchMatchesOnlyHalfInchAndEquivalentNotation() {
        val selected = pipe(1, "1/2 inch")
        val choices = listOf(selected, pipe(2, "½\"", "Blue"), pipe(3, "0.5 inch", "UPVC"), pipe(4, "1 inch"))
        assertEquals(setOf(1L, 2L, 3L), PipeSizeChoices.matching(choices, selected).map { it.id }.toSet())
    }
    @Test fun changingSelectionReplacesTheTopGroup() {
        val choices = listOf(pipe(1, "1 inch"), pipe(2, "1/2 inch"), pipe(3, "1/2 inch", "CPVC"))
        val state = ProductSelectionUiState(availablePipes = choices, pendingProduct = choices[0])
        assertEquals(listOf(1L), state.matchingPipes.map { it.id })
        assertEquals(setOf(2L, 3L), state.copy(pendingProduct = choices[1]).matchingPipes.map { it.id }.toSet())
        assertTrue(state.copy(pendingProduct = choices[0].copy(isPipe = false)).matchingPipes.isEmpty())
    }
    @Test fun inactiveAndOrdinaryProductsAndDuplicatesAreExcluded() {
        val selected = pipe(1, "1 inch")
        val choices = listOf(selected, selected, pipe(2, "1 inch").copy(isActive = false),
            pipe(3, "1 inch").copy(isPipe = false))
        assertEquals(listOf(selected), PipeSizeChoices.matching(choices, selected))
    }
    @Test fun invalidOrAmbiguousSizesDoNotMatch() {
        assertNull(PipeSizeChoices.key("1/0 inch"))
        assertNull(PipeSizeChoices.key("20 mm x 1 inch"))
        assertNull(PipeSizeChoices.key(""))
        assertNull(PipeSizeChoices.key("0 inch"))
        assertEquals("1.25", PipeSizeChoices.key("1¼″"))
    }
}
